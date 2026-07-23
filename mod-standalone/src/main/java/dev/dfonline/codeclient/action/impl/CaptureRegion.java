package dev.dfonline.codeclient.action.impl;

import com.dfvibe.Chat;
import com.dfvibe.Log;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.dfonline.codeclient.Callback;
import dev.dfonline.codeclient.CodeClient;
import dev.dfonline.codeclient.Utility;
import dev.dfonline.codeclient.action.Action;
import dev.dfonline.codeclient.data.DFItem;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.SignBlockEntity;
import net.minecraft.block.entity.SkullBlockEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.AttributeModifierSlot;
import net.minecraft.component.type.AttributeModifiersComponent;
import net.minecraft.component.type.ContainerComponent;
import net.minecraft.entity.attribute.EntityAttributeModifier;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.c2s.play.CreativeInventoryActionC2SPacket;
import net.minecraft.network.packet.c2s.play.PickItemFromBlockC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.network.packet.s2c.play.ScreenHandlerSlotUpdateS2CPacket;
import net.minecraft.registry.Registries;
import net.minecraft.state.property.Property;
import net.minecraft.util.Identifier;
import net.minecraft.util.collection.DefaultedList;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.TreeMap;
import java.util.zip.GZIPOutputStream;

/**
 * Capture a region's EXACT live state - every block + block-state, plus the block-entity data DF can
 * reproduce: SIGN TEXT (front + back, styled, plus dye color/glow, read client-side during the sweep),
 * PLAYER-HEAD textures (picked from the server, profile included), STORAGE CONTAINER contents + custom
 * name + lock key (picked with data), and lectern books / jukebox discs (containers to DF). Written to
 * a compact capture directory the codec ({@code dfpy.py genschem}) turns into a DiamondFire build-loader
 * project (ChangeSign / SignColor / SetHead / SetContainer / SetContainerName / LockContainer fixtures).
 * Block-entities DF has no action for (banner patterns) are placed as plain blocks.
 *
 * Dev mode only. Movement:
 *   - DEFAULT: a creative GLIDE - moves up to {@link #FAST_STEP} blocks/tick toward the target by sending move
 *     packets (automating the creative flight you already have in dev mode); it is NOT an instant teleport
 *     (those get rejected). The block-load sweep just needs each chunk loaded, so it glides fast with only a
 *     short dwell per waypoint; the genuinely slow part is getting within reach of each chest.
 *   - -nomove: don't move; the player sweeps manually and we read/pick what they pass within reach.
 */
public class CaptureRegion extends Action {
    /** The region box [x1,y1,z1,x2,y2,z2] to capture. Set by SyncService.schematicSave. */
    public static volatile int[] REGION;
    /** -nomove: don't move; the player sweeps the region manually. */
    public static volatile boolean NO_MOVE = false;
    /**
     * -nochest: capture the build's SHAPE only - never pick a container. Containers are still placed (their
     * block + block-state are read during the sweep), they just come out EMPTY, with no custom name and no
     * lock. Player heads and sign text are unaffected (heads need a pick, but there is usually a handful of
     * them; signs are read client-side and cost nothing). The PICK phase is where a capture spends nearly all
     * of its time - one flight + server round-trip per container - so a chest-heavy build captures far faster
     * when you only want the structure.
     */
    public static volatile boolean NO_CHEST = false;
    /** Where the capture files are written (the schem project's .capture/ dir). */
    public static volatile Path OUT_DIR;
    /** ~20s keep-alive ping sink so a long capture doesn't time out the WS poll. Set by the Capture verb. */
    public static volatile java.util.function.Consumer<String> HEARTBEAT;
    /** Counts of what was captured, for the "capture done ..." WS reply. Set when WRITE completes. */
    public static volatile String SUMMARY = "";
    private static volatile boolean RUNNING = false;       // a capture is in progress (for /df end compile)
    private static volatile boolean FINISH_NOW = false;    // /df end compile: write what we have and stop

    /** /df end compile: ask the running capture to write what it has now and finish. False if none running. */
    public static boolean requestFinish() {
        if (!RUNNING) return false;
        FINISH_NOW = true;
        return true;
    }

    /** Hard-cancel teardown (/df end): a cancelled capture never reaches complete(), so drop the RUNNING
     *  flag here - otherwise a later /df end compile "finishes" a capture that no longer exists. */
    public static void clearActive() {
        RUNNING = false;
        FINISH_NOW = false;
    }

    private static final int OFFHAND_SLOT = 45;
    private static final double FAST_STEP = 10.0;          // glide speed (blocks/tick) - what the user wants for the default
    private static final double REACH_BOOST = 60.0;        // offhand +reach so we can pick chests from afar
    private static final double PICK_REACH = 55.0;         // read a container within this many blocks of a stop
    private static final double PICK_APPROACH = 45.0;      // approach an out-of-reach chest only to here (inside PICK_REACH) - never fly onto it
    private static final double COVER_RADIUS = 32.0;       // "swept a spot" = within 32 (horizontal) blocks of it
    private static final int SWEEP_SETTLE_TICKS = 5;       // brief dwell at a waypoint so its chunks deliver
    private static final int READ_CELL_BUDGET = 40000;     // max block reads per tick (keep one tick cheap)
    private static final int MAX_PALETTE = 65535;          // blocks.gz is uint16; index 0 is air
    private static final int MAX_PICK_RETRIES = 3;
    private static final int JOB_WATCHDOG_TICKS = 400;     // give up on one chest after ~20s so we never hang
    private static final int ARRIVE_CONFIRM = 3;           // ticks to hold at a chest (confirm position settled) before flipping to creative

    private enum Phase { SWEEP, READ_TAIL, PICK, WRITE, DONE }
    private Phase phase = Phase.SWEEP;

    private int x1, y1, z1, x2, y2, z2, W, H, L;
    /**
     * Palette indices (0 = air), stored as one {@code short[]} slab per 16x16 chunk column and allocated only
     * when that chunk turns out to hold a non-air block. A mega plot is 256x512x256 = 33.5M cells; as one flat
     * {@code int[]} that was 134MB and blew the old cell cap outright. Two changes make any plot fit: the values
     * are palette indices written to disk as uint16, so {@code short} is the true width (halves it), and a
     * capture box is mostly empty air, so all-air chunks now cost nothing at all.
     */
    private short[][] chunkGrid;
    private int cellsPerChunk;                             // 16 * 16 * H
    private final LinkedHashMap<String, Integer> palette = new LinkedHashMap<>(); // key -> index (1-based; air implicit 0)
    private final JsonArray entities = new JsonArray();
    /** Block-entities that need a server pick to read (containers for contents/name/lock, player heads
     *  for their profile texture). Signs need no pick - their text is client-side, read during the sweep. */
    private record PickJob(BlockPos pos, boolean head) {}
    private final List<PickJob> pickJobs = new ArrayList<>();

    // per-chunk read state (read each chunk the instant it loads near the player, not whole Z-planes)
    private int cx0, cz0, cxCount, czCount, chunksTotal, chunksRead;
    private boolean[] chunkRead;
    private int readStallTicks = 0;

    private int sweepTargetIdx = -1;                       // chunkRead index we're currently gliding toward (adaptive sweep)
    private int sweepSettle = 0;
    private boolean sweepArriving = false;
    private List<Vec3d> covSamples;
    private boolean[] covered;
    private int coveredCount = 0;

    private int pickIdx = 0;
    private boolean pickPlanned = false;                   // pick route ordered (once, when PICK starts)
    private boolean picking = false;
    private int pickRetries = 0;
    private int pickWait = 0;
    private int jobTicks = 0;
    private int arriveConfirm = 0;
    private BlockPos pendingPos = null;
    private boolean pickRetryRound = false;                // second pass over given-up picks (once)
    private final List<PickJob> pickGaveUp = new ArrayList<>(); // picks the watchdog skipped (tp'd away mid-read)
    private int picksLost = 0;                             // still unread after the retry round -> reported, never silent

    private Vec3d moveTarget = null;                       // glide destination (default mode)
    private double glideArrive = 1.0;                      // stop gliding within this many blocks of moveTarget
    private Vec3d holdPos = null;                          // where to pin the player between moves (default mode)
    private boolean reachEquipped = false;
    private int heartbeatTick = 0, reportTick = 0;
    private int containersRead = 0;
    private int signsRead = 0;
    private int headsRead = 0;
    private boolean paletteFull = false;                   // >65535 distinct block-states: the extras became air

    private boolean sendingOurs = false; // true only while WE send a move packet, so it isn't suppressed below
    private volatile boolean serverMoved = false; // a server teleport arrived (plot code / anti-cheat moved us)
    private int interferenceCount = 0;            // how many times the plot yanked us around (for reporting)

    public CaptureRegion(Callback callback) {
        super(callback);
    }

    @Override
    public boolean onSendPacket(Packet<?> packet) {
        // FREEZE the player: while WE control position (not -nomove), drop the player's OWN movement packets so
        // they physically can't move/drift/fight us. Our move packets pass via the sendingOurs flag. Look-only
        // packets are allowed (camera stays free). EXCEPTION: while a server teleport is being applied
        // (serverMoved), vanilla's confirm + position-sync move MUST go through - suppressing it leaves the
        // teleport unfinalized and the server just teleports us again.
        if (!NO_MOVE && !sendingOurs && !serverMoved
                && (packet instanceof PlayerMoveC2SPacket.PositionAndOnGround
                    || packet instanceof PlayerMoveC2SPacket.Full)) {
            return true;
        }
        return super.onSendPacket(packet);
    }

    private void ourSend(Packet<?> p) {
        var net = CodeClient.MC.getNetworkHandler();
        if (net == null) return;
        sendingOurs = true;
        try { net.sendPacket(p); } finally { sendingOurs = false; }
    }

    private static boolean creative() {
        try { return CodeClient.MC.interactionManager != null
                && CodeClient.MC.interactionManager.getCurrentGameMode() == GameMode.CREATIVE; }
        catch (Throwable t) { return false; }
    }

    @Override
    public boolean wantsNoClip() { return false; }

    @Override
    public void init() {
        int[] b = REGION;
        if (b == null) { Chat.error("No capture box was set."); complete("error"); return; }
        x1 = Math.min(b[0], b[3]); x2 = Math.max(b[0], b[3]);
        y1 = Math.min(b[1], b[4]); y2 = Math.max(b[1], b[4]);
        z1 = Math.min(b[2], b[5]); z2 = Math.max(b[2], b[5]);
        W = x2 - x1 + 1; H = y2 - y1 + 1; L = z2 - z1 + 1;
        long cells = (long) W * H * L;

        cx0 = x1 >> 4; cz0 = z1 >> 4;
        cxCount = (x2 >> 4) - cx0 + 1; czCount = (z2 >> 4) - cz0 + 1;
        chunksTotal = cxCount * czCount;

        // Refuse only what genuinely cannot fit. The worst case is every chunk holding a block, i.e. one slab
        // each; we allow that to use up to half the heap. A mega plot (256x512x256) needs ~67MB of slabs, so it
        // fits comfortably - and in practice mostly-air chunks never allocate, so real usage is far below this.
        cellsPerChunk = 16 * 16 * H;
        long worstBytes = (long) chunksTotal * cellsPerChunk * 2L;
        long allowed = Math.max(256L << 20, Runtime.getRuntime().maxMemory() / 2);
        if (worstBytes > allowed) {
            Chat.error("Region is too large to capture (" + cells + " blocks, up to " + (worstBytes >> 20)
                    + "MB of block data; this game has room for " + (allowed >> 20) + "MB). Shrink it with"
                    + " /df region, or give Minecraft more RAM.");
            complete("error"); return;
        }
        chunkGrid = new short[chunksTotal][];
        RUNNING = true;
        FINISH_NOW = false;

        chunkRead = new boolean[chunksTotal];

        buildCoverageSamples();

        equipReach();
        if (!NO_MOVE && !creative())
            Chat.warn("Fast capture needs creative flight (dev mode). "
                    + "If you rubber-band or it won't move, use /df schematic save <name> -nomove and fly it yourself.");

        com.dfvibe.Progress.start("Capture", this::progressDone, this::progressTotal);
        Chat.info("Capturing " + W + "x" + H + "x" + L + " region" + (NO_MOVE ? " - fly through it slowly." : "") + "...");
        Log.step("capture begin: " + W + "x" + H + "x" + L + " (" + cells + " cells), mode="
                + (NO_MOVE ? "nomove" : "glide"));
    }

    private int progressDone() { return phase == Phase.PICK ? pickIdx : chunksRead; }
    private int progressTotal() {
        return phase == Phase.PICK ? Math.max(1, pickJobs.size()) : Math.max(1, chunksTotal);
    }

    /** Nearest still-unread chunk to the player (adaptive sweep target), or -1 if all read. */
    private int nearestUnreadChunkIdx() {
        var pl = CodeClient.MC.player;
        if (pl == null) return -1;
        Vec3d p = pl.getEntityPos();
        int best = -1;
        double bestD = Double.MAX_VALUE;
        for (int i = 0; i < chunkRead.length; i++) {
            if (chunkRead[i]) continue;
            double dx = ((cx0 + (i % cxCount)) << 4) + 8 - p.x;
            double dz = ((cz0 + (i / cxCount)) << 4) + 8 - p.z;
            double d = dx * dx + dz * dz;
            if (d < bestD) { bestD = d; best = i; }
        }
        return best;
    }

    private void buildCoverageSamples() {
        covSamples = new ArrayList<>();
        for (int x = x1; x <= x2; x += 16)
            for (int z = z1; z <= z2; z += 16)
                covSamples.add(new Vec3d(x, y1, z));
        covered = new boolean[covSamples.size()];
    }

    private void markCoverage() {
        if (covSamples == null || CodeClient.MC.player == null) return;
        Vec3d p = CodeClient.MC.player.getEntityPos();
        for (int i = 0; i < covSamples.size(); i++) {
            if (covered[i]) continue;
            Vec3d s = covSamples.get(i);
            double dx = s.x - p.x, dz = s.z - p.z;
            if (dx * dx + dz * dz <= COVER_RADIUS * COVER_RADIUS) { covered[i] = true; coveredCount++; }
        }
    }

    private boolean boxFullyCovered() {
        return covSamples != null && !covSamples.isEmpty() && coveredCount >= covSamples.size();
    }

    private boolean moving() { return moveTarget != null; }

    /** Head toward a target with the creative glide. arrive = stop within this dist. (precise is retained in
     *  the signature for the call sites' intent; the glide always lands within arrive of the target.) */
    private void goTo(Vec3d target, double arrive, boolean precise) {
        moveTarget = target;
        glideArrive = arrive;
    }

    /** Move toward moveTarget: glide up to FAST_STEP/tick (automating creative flight). Clears moveTarget on arrival. */
    private void glideStep() {
        var player = CodeClient.MC.player;
        var net = CodeClient.MC.getNetworkHandler();
        if (player == null || net == null || moveTarget == null) return;
        Vec3d p = player.getEntityPos();
        double dist = p.distanceTo(moveTarget);
        if (dist <= glideArrive) { moveTarget = null; holdPos = p; return; }
        // Move incrementally (NOT an instant jump - that rubber-bands).
        double step = Math.min(FAST_STEP, dist - glideArrive);
        Vec3d next = (step < 0.05) ? moveTarget : p.add(moveTarget.subtract(p).normalize().multiply(step));
        player.setVelocity(0, 0, 0);
        player.setPosition(next);
        player.setOnGround(false);
        ourSend(new PlayerMoveC2SPacket.PositionAndOnGround(next.x, next.y, next.z, false, false));
        holdPos = next;
        if (next == moveTarget) { moveTarget = null; }
    }

    /** Lock the player firmly in place - used between moves so they can't drift / get tp'd back. The creative
     *  flight ability is re-asserted (a gamemode packet can reset flying=false) so gravity doesn't pull us off
     *  holdPos; the y=-1e6 float-tick is an anti-kick that also stops the server re-processing this as movement. */
    private void holdTeleport() {
        var player = CodeClient.MC.player;
        var net = CodeClient.MC.getNetworkHandler();
        if (player == null || net == null || holdPos == null) return;
        var im = CodeClient.MC.interactionManager;
        if (im != null && im.getCurrentGameMode() == GameMode.CREATIVE) player.getAbilities().flying = true;
        player.setVelocity(0, 0, 0);
        player.setPosition(holdPos);
        player.setOnGround(false);
        ourSend(new PlayerMoveC2SPacket.PositionAndOnGround(holdPos.x, holdPos.y, holdPos.z, false, false));
        // Anti-kick float-tick; also stops the server re-processing this pin as real movement.
        ourSend(new PlayerMoveC2SPacket.PositionAndOnGround(holdPos.x, -1.0E6, holdPos.z, false, false));
    }

    @Override
    public void tick() {
        if (chunkGrid == null) return;
        if (FINISH_NOW && phase != Phase.WRITE && phase != Phase.DONE) {
            FINISH_NOW = false;
            Log.step("capture finishing early (/df end compile) - writing what's captured so far");
            phase = Phase.WRITE;
            tickWrite();
            return;
        }
        if (++heartbeatTick % 400 == 0 && HEARTBEAT != null) {
            try { HEARTBEAT.accept("scan-progress capture " + phase + " chests=" + containersRead); } catch (Throwable ignored) {}
        }
        if (++reportTick % 80 == 0 && (phase == Phase.SWEEP || phase == Phase.PICK))
            Chat.info("capture: " + (phase == Phase.PICK
                    ? "reading chest/head " + pickIdx + "/" + pickJobs.size()
                    : "loading " + chunksRead + "/" + chunksTotal + " chunks, " + pickJobs.size() + " chests/heads found"));

        // ACCEPT a server teleport (the plot's game code moved us, or the server corrected our motion):
        // drop every stale anchor/leg and re-plan from wherever we landed next tick. The adaptive sweep
        // and the pick-distance checks are position-relative, so re-routing is free - the one thing we
        // must NOT do is keep teleporting ourselves back to the old spot (the server just re-teleports
        // us, forever, and chunks churn the whole time).
        if (serverMoved) {
            serverMoved = false;
            interferenceCount++;
            moveTarget = null;
            holdPos = null;     // holdTeleport()/glideStep() re-anchor at the REAL position next tick
            sweepTargetIdx = -1;
            sweepArriving = false;
            sweepSettle = 0;
            arriveConfirm = 0;
            if (interferenceCount <= 3 || interferenceCount % 10 == 0)
                Chat.info("capture: the plot moved us (" + interferenceCount + "x) - continuing from where we landed.");
            Log.step("capture: server teleport accepted (" + interferenceCount + "x) - re-routing");
            return; // let vanilla finish applying the teleport before we anchor/act on the new position
        }
        var pl = CodeClient.MC.player;
        if (pl != null && !NO_MOVE) {
            // The plot MOUNTED us on an entity (Ride action, boat/horse minigames): while riding, our
            // position follows the VEHICLE and every position pin we send is overridden - the capture
            // just gets dragged around. Dismount immediately (sneak is the vanilla dismount signal,
            // sent as a player-input packet) and re-anchor wherever we end up.
            if (pl.hasVehicle()) {
                interferenceCount++;
                var net = CodeClient.MC.getNetworkHandler();
                if (net != null) {
                    net.sendPacket(new net.minecraft.network.packet.c2s.play.PlayerInputC2SPacket(
                            new net.minecraft.util.PlayerInput(false, false, false, false, false, true, false)));
                    net.sendPacket(new net.minecraft.network.packet.c2s.play.PlayerInputC2SPacket(pl.getLastPlayerInput()));
                }
                pl.stopRiding();
                holdPos = null;
                Log.step("capture: plot mounted us on a vehicle - dismounted (" + interferenceCount + "x)");
                return;
            }
            // The plot KILLED us: the death screen blocks everything and the capture hangs forever.
            // Respawn instantly; the respawn arrives as a server teleport and re-routes like any other.
            if (pl.isDead() || pl.getHealth() <= 0) {
                pl.requestRespawn();
                Log.step("capture: we died - auto-respawning and re-routing");
                return;
            }
        }
        if (com.dfvibe.SyncService.PAUSED) {
            if (!NO_MOVE) holdTeleport();
            return;
        }
        // Keep the player pinned in place whenever we're not actively gliding, so they don't drift or get
        // tp'd back between moves.
        if (!NO_MOVE && moveTarget == null) holdTeleport();
        markCoverage();
        if (com.dfvibe.Eta.isActive() && CodeClient.MC.player != null)
            com.dfvibe.Eta.sample(CodeClient.MC.player.getEntityPos(), progressDone(), progressTotal());
        if (phase == Phase.SWEEP || phase == Phase.READ_TAIL) drainReadable();
        // Sweep target loaded en route - stop gliding to it and retarget the next nearest unread chunk.
        if (phase == Phase.SWEEP && sweepTargetIdx >= 0 && chunkRead[sweepTargetIdx] && moveTarget != null) {
            moveTarget = null;
            if (CodeClient.MC.player != null) holdPos = CodeClient.MC.player.getEntityPos();
        }
        if (moveTarget != null) { glideStep(); return; }

        switch (phase) {
            case SWEEP -> tickSweep();
            case READ_TAIL -> tickReadTail();
            case PICK -> tickPick();
            case WRITE -> tickWrite();
            default -> {}
        }
    }

    private void tickSweep() {
        if (NO_MOVE) {
            if (readDone() && boxFullyCovered()) phase = Phase.PICK;
            return;
        }
        if (sweepArriving) { sweepArriving = false; sweepSettle = SWEEP_SETTLE_TICKS; return; }
        if (sweepSettle > 0) { sweepSettle--; return; }   // brief dwell so the chunks around us deliver + get read
        if (readDone()) { phase = Phase.READ_TAIL; return; }
        // Adaptive: work off LOADED chunks. Read whatever's loaded (drainReadable, in tick()), then glide toward the
        // nearest still-unread chunk; everything that loads en route is read, so we only stop ~once per render-distance
        // worth of area instead of visiting every cell. Retarget when our current target gets read.
        if (sweepTargetIdx < 0 || chunkRead[sweepTargetIdx]) sweepTargetIdx = nearestUnreadChunkIdx();
        if (sweepTargetIdx < 0) { phase = Phase.READ_TAIL; return; }
        int cx = cx0 + (sweepTargetIdx % cxCount), cz = cz0 + (sweepTargetIdx / cxCount);
        goTo(new Vec3d((cx << 4) + 8, y2 + 3, (cz << 4) + 8), 2.0, true); // precise: reach-completing 40 blocks from a chunk doesn't guarantee it loads
        sweepArriving = true;
    }

    private void tickReadTail() {
        if (readDone()) { phase = Phase.PICK; return; }
        if (drainReadable() == 0) {
            if (++readStallTicks > 60) {
                int missed = chunksTotal - chunksRead;
                Chat.warn("Couldn't load " + missed + " chunk(s) of the region (left as air). Re-run with -nomove and fly through them yourself, or shrink the region.");
                for (int i = 0; i < chunkRead.length; i++) if (!chunkRead[i]) { chunkRead[i] = true; chunksRead++; }
            }
        } else readStallTicks = 0;
    }

    // --- block-grid reading (per chunk, as each loads) ---
    private boolean readDone() { return chunksRead >= chunksTotal; }

    private int drainReadable() {
        ClientWorld world = CodeClient.MC.world;
        if (world == null || readDone()) return 0;
        int did = 0, budget = READ_CELL_BUDGET;
        for (int i = 0; i < chunkRead.length && budget > 0; i++) {
            if (chunkRead[i]) continue;
            int cx = cx0 + (i % cxCount), cz = cz0 + (i / cxCount);
            if (!world.getChunkManager().isChunkLoaded(cx, cz)) continue;
            readChunkColumn(world, cx, cz);
            chunkRead[i] = true; chunksRead++; did++;
            budget -= 256 * H;
        }
        return did;
    }

    private void readChunkColumn(ClientWorld world, int cx, int cz) {
        int bx1 = Math.max(x1, cx << 4), bx2 = Math.min(x2, (cx << 4) + 15);
        int bz1 = Math.max(z1, cz << 4), bz2 = Math.min(z2, (cz << 4) + 15);
        BlockPos.Mutable pos = new BlockPos.Mutable();
        for (int x = bx1; x <= bx2; x++)
            for (int y = y1; y <= y2; y++)
                for (int z = bz1; z <= bz2; z++) {
                    pos.set(x, y, z);
                    BlockState state = world.getBlockState(pos);
                    if (state.isAir()) continue;
                    setCell(x, y, z, intern(keyFor(state)));
                    if (state.hasBlockEntity()) {
                        BlockEntity be = world.getBlockEntity(pos);
                        if (be instanceof SignBlockEntity sign) recordSign(pos, sign); // text is client-side
                        else if (be instanceof SkullBlockEntity skull) {
                            // Only player heads with an owner/profile carry a texture worth reproducing;
                            // plain skulls are fully described by their block state.
                            if (skull.getOwner() != null) pickJobs.add(new PickJob(pos.toImmutable(), true));
                        }
                        else if (be instanceof net.minecraft.block.entity.LecternBlockEntity) {
                            // A lectern is a 1-slot container to DF (SetContainer places the book), but its
                            // BE doesn't implement Inventory - detect it explicitly. Only pick when it
                            // actually holds a book (the block state says so).
                            if (!NO_CHEST && state.getOrEmpty(net.minecraft.state.property.Properties.HAS_BOOK).orElse(false))
                                pickJobs.add(new PickJob(pos.toImmutable(), false));
                        }
                        else if (be instanceof Inventory) { if (!NO_CHEST) pickJobs.add(new PickJob(pos.toImmutable(), false)); }
                    }
                }
    }

    /** Slab index of the chunk owning this block column - the same ordering {@link #chunkRead} uses. */
    private int chunkOf(int x, int z) { return ((z >> 4) - cz0) * cxCount + ((x >> 4) - cx0); }

    /** Offset of (x,y,z) inside its chunk's slab. */
    private int cellOf(int x, int y, int z) { return (((x & 15) << 4) | (z & 15)) * H + (y - y1); }

    private void setCell(int x, int y, int z, int paletteIdx) {
        int c = chunkOf(x, z);
        short[] slab = chunkGrid[c];
        if (slab == null) chunkGrid[c] = slab = new short[cellsPerChunk]; // first block here: air chunks never allocate
        slab[cellOf(x, y, z)] = (short) paletteIdx;
    }

    /** Palette index at (x,y,z), 0 (air) when that chunk never held a block. */
    private int getCell(int x, int y, int z) {
        short[] slab = chunkGrid[chunkOf(x, z)];
        return slab == null ? 0 : (slab[cellOf(x, y, z)] & 0xffff);
    }

    private int intern(String key) {
        Integer v = palette.get(key);
        if (v != null) return v;
        if (palette.size() >= MAX_PALETTE) { paletteFull = true; return 0; } // uint16 grid is full: emit air rather than wrap to a wrong block
        int i = palette.size() + 1;
        palette.put(key, i);
        return i;
    }

    private static String keyFor(BlockState state) {
        String id = Registries.BLOCK.getId(state.getBlock()).toString();
        var entries = state.getEntries();
        if (entries.isEmpty()) return id;
        TreeMap<String, String> props = new TreeMap<>();
        for (var e : entries.entrySet()) props.put(e.getKey().getName(), nameOf(e.getKey(), e.getValue()));
        StringBuilder sb = new StringBuilder(id).append('|');
        boolean first = true;
        for (var e : props.entrySet()) { if (!first) sb.append(','); sb.append(e.getKey()).append('=').append(e.getValue()); first = false; }
        return sb.toString();
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static String nameOf(Property p, Comparable v) { return p.name(v); }

    /** Order the chest/head pick jobs into a greedy nearest-neighbor route from where the sweep ended.
     *  They were queued in chunk-DISCOVERY order, which zigzags back and forth across the whole region -
     *  at the eventless crawl that back-and-forth WAS most of the capture time. */
    private void planPicks() {
        if (pickJobs.size() < 3) return;
        List<PickJob> remaining = new ArrayList<>(pickJobs);
        pickJobs.clear();
        Vec3d cur = CodeClient.MC.player != null ? CodeClient.MC.player.getEntityPos()
                : Vec3d.ofCenter(remaining.get(0).pos());
        while (!remaining.isEmpty()) {
            int best = 0;
            double bestD = Double.MAX_VALUE;
            for (int i = 0; i < remaining.size(); i++) {
                double d = cur.squaredDistanceTo(Vec3d.ofCenter(remaining.get(i).pos()));
                if (d < bestD) { bestD = d; best = i; }
            }
            PickJob next = remaining.remove(best);
            pickJobs.add(next);
            cur = Vec3d.ofCenter(next.pos());
        }
    }

    // --- container picking (reach-based, glide/fly-adjacent fallback) ---
    private void tickPick() {
        if (!pickPlanned) { pickPlanned = true; planPicks(); }
        if (pickIdx >= pickJobs.size()) {
            // ONE retry round over everything the watchdog skipped (usually because the plot teleported us
            // away mid-read) before writing - a skipped chest is silently-missing data in the capture.
            if (!pickRetryRound && !pickGaveUp.isEmpty()) {
                pickRetryRound = true;
                Chat.info("capture: retrying " + pickGaveUp.size() + " unread chest(s)/head(s)...");
                pickJobs.addAll(pickGaveUp);
                pickGaveUp.clear();
                return;
            }
            picksLost += pickGaveUp.size();
            pickGaveUp.clear();
            phase = Phase.WRITE;
            return;
        }
        BlockPos target = pickJobs.get(pickIdx).pos();
        var player = CodeClient.MC.player;
        if (player == null) return;

        if (++jobTicks > JOB_WATCHDOG_TICKS) { giveUpPick("watchdog"); return; }
        if (picking) {
            if (++pickWait > 40) {
                picking = false;
                if (++pickRetries > MAX_PICK_RETRIES) giveUpPick("no response");
                else goTo(Vec3d.ofCenter(target).add(0, 1.5, 0), 2.0, true); // ranged pick failing - land next to it and retry point-blank
            }
            return;
        }
        double d2 = player.getEntityPos().squaredDistanceTo(Vec3d.ofCenter(target));
        if (d2 <= PICK_REACH * PICK_REACH || pickRetries > 0) {
            pendingPos = target; picking = true; pickWait = 0;
            sendPick(target);
        } else {
            arriveConfirm = 0;
            // Approach only until the chest is comfortably INSIDE pick reach (~45 blocks out), never onto it -
            // the +reach offhand picks from ~55, so flying the last 45 blocks per chest was pure wasted time.
            goTo(Vec3d.ofCenter(target).add(0, 1.5, 0), PICK_APPROACH, false);
        }
    }

    private void advancePick() { pickIdx++; pickRetries = 0; picking = false; pendingPos = null; jobTicks = 0; arriveConfirm = 0; }

    /** A pick that couldn't be read this pass. Queue it for the (single) retry round instead of dropping
     *  it silently - "silently missing chest contents" is capture corruption, not an inconvenience. */
    private void giveUpPick(String why) {
        PickJob job = pickJobs.get(pickIdx);
        Log.step("capture: gave up on chest/head at " + job.pos() + " (" + why + ")"
                + (pickRetryRound ? " - UNREAD (already retried)" : " - queued for a retry round"));
        pickGaveUp.add(job);
        advancePick();
    }

    private void sendPick(BlockPos pos) {
        var net = CodeClient.MC.getNetworkHandler();
        if (net == null) return;
        try { Utility.makeHolding(ItemStack.EMPTY); } catch (Throwable ignored) {}
        net.sendPacket(new PickItemFromBlockC2SPacket(pos, true)); // includeData=true -> full contents NBT
    }

    @Override
    public boolean onReceivePacket(Packet<?> packet) {
        // A server teleport = the plot's own code (a live game teleports players around) or an anti-cheat
        // correction. Note it and let vanilla apply it - tick() ACCEPTS the new position and re-routes.
        // The old behaviour silently kept pinning the player back to the STALE hold point, which the game
        // saw as the player "escaping" and teleported them again - the endless tp-back fight the user hit.
        if (packet instanceof net.minecraft.network.packet.s2c.play.PlayerPositionLookS2CPacket
                && !NO_MOVE) {
            serverMoved = true;
        }
        var pl = CodeClient.MC.player;
        if (!NO_MOVE && pl != null) {
            // EAT knockback/launches aimed at us (LaunchPlayer, explosions, projectile hits): they arrive
            // as a velocity packet, not a teleport, so the accept-and-re-route path never sees them - the
            // shove is simply cancelled at the source. Our position pin would fight the drift anyway; this
            // stops the player visibly rocketing off mid-read.
            if (packet instanceof net.minecraft.network.packet.s2c.play.EntityVelocityUpdateS2CPacket vel
                    && vel.getEntityId() == pl.getId()) {
                return true;
            }
            // CLOSE plot GUIs (shops/menus a game opens on players): an open screen covers the capture and
            // its slot updates can be mistaken for our chest-pick responses. Tell the server we closed it.
            if (packet instanceof net.minecraft.network.packet.s2c.play.OpenScreenS2CPacket open) {
                var net = CodeClient.MC.getNetworkHandler();
                if (net != null) net.sendPacket(
                        new net.minecraft.network.packet.c2s.play.CloseHandledScreenC2SPacket(open.getSyncId()));
                Log.step("capture: plot opened a GUI - closed it");
                return true;
            }
        }
        if (!picking || pickIdx >= pickJobs.size()) return false;
        if (!(packet instanceof ScreenHandlerSlotUpdateS2CPacket slot)) return false;
        ItemStack stack = slot.getStack();
        if (stack == null || stack.isEmpty()) return false;
        PickJob job = pickJobs.get(pickIdx);
        if (job.head()) {
            if (!stack.isOf(Items.PLAYER_HEAD)) return false;    // not our pick response
            recordHead(stack);
        } else {
            // Accept the picked block's own item (chest, barrel, furnace, shulker, ... - the old
            // Items.CHEST check silently dropped every non-chest container's contents), falling back
            // to "has a container component" for blocks whose item mapping is odd.
            var expected = CodeClient.MC.world.getBlockState(job.pos()).getBlock().asItem();
            if (!(expected != Items.AIR && stack.isOf(expected)) && DFItem.of(stack).getContainer() == null)
                return false;
            recordContainer(stack);
        }
        try { Utility.makeHolding(stack); } catch (Throwable ignored) {}
        advancePick();
        return true;
    }

    private void recordContainer(ItemStack stack) {
        JsonArray items = new JsonArray();
        ContainerComponent container = DFItem.of(stack).getContainer();
        if (container != null) {
            DefaultedList<ItemStack> slots = DefaultedList.ofSize(27, ItemStack.EMPTY);
            container.copyTo(slots);                      // preserves slot indices
            for (int i = 0; i < slots.size(); i++) {
                ItemStack it = slots.get(i);
                if (it.isEmpty()) continue;
                String snbt = rawItemSnbt(it);
                if (snbt == null || snbt.isEmpty() || snbt.equals("{}")) continue;
                JsonObject e = new JsonObject();
                e.addProperty("slot", i);
                e.addProperty("snbt", snbt);
                items.add(e);
            }
        } else {
            // Blocks whose single item rides in raw BE data instead of a container component:
            // a lectern's Book, a jukebox's RecordItem. Both are item-shaped compounds already,
            // and DF sets them via the same container actions (slot 0).
            var beData = stack.get(DataComponentTypes.BLOCK_ENTITY_DATA);
            if (beData != null) {
                var nbt = beData.copyNbtWithoutId();
                for (String key : new String[]{"Book", "RecordItem"}) {
                    if (nbt.get(key) instanceof net.minecraft.nbt.NbtCompound item && !item.isEmpty()) {
                        JsonObject e = new JsonObject();
                        e.addProperty("slot", 0);
                        e.addProperty("snbt", item.toString());
                        items.add(e);
                        break;
                    }
                }
            }
        }
        net.minecraft.text.Text nm = stack.get(DataComponentTypes.CUSTOM_NAME);
        String lock = lockSnbt(stack);
        if (items.isEmpty() && nm == null && lock == null) return; // plain empty container: the block itself is enough
        JsonObject o = new JsonObject();
        o.addProperty("kind", "container");
        o.addProperty("x", pendingPos.getX() - x1);
        o.addProperty("y", pendingPos.getY() - y1);
        o.addProperty("z", pendingPos.getZ() - z1);
        o.addProperty("block", Registries.BLOCK.getId(CodeClient.MC.world.getBlockState(pendingPos).getBlock()).getPath());
        if (nm != null) o.addProperty("name", toMiniMessage(nm)); // -> SetContainerName on load
        if (lock != null) o.addProperty("lock", lock);            // -> LockContainer on load
        o.add("items", items);
        entities.add(o);
        containersRead++;
    }

    /** The picked container's lock component as SNBT (the codec extracts the key for LockContainer), or null. */
    private String lockSnbt(ItemStack stack) {
        try {
            var lock = stack.get(DataComponentTypes.LOCK);
            if (lock == null) return null;
            NbtElement enc = net.minecraft.inventory.ContainerLock.CODEC.encodeStart(
                    CodeClient.MC.player.getRegistryManager().getOps(NbtOps.INSTANCE), lock).getOrThrow();
            return enc.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    /** A picked player head, texture/profile and all - the codec emits SetHead(loc, item(snbt)). */
    private void recordHead(ItemStack stack) {
        String snbt = rawItemSnbt(stack);
        if (snbt == null || snbt.isEmpty() || snbt.equals("{}")) return;
        JsonObject o = new JsonObject();
        o.addProperty("kind", "head");
        o.addProperty("x", pendingPos.getX() - x1);
        o.addProperty("y", pendingPos.getY() - y1);
        o.addProperty("z", pendingPos.getZ() - z1);
        o.addProperty("snbt", snbt);
        entities.add(o);
        headsRead++;
    }

    /** A sign's text, read straight from the client-side block entity during the sweep. Both sides are
     *  captured as MiniMessage (styled) lines plus their dye color + glow - the codec emits ChangeSign
     *  per non-blank line and SignColor when the side isn't default black/non-glowing. */
    private void recordSign(BlockPos pos, SignBlockEntity sign) {
        JsonArray front = signSide(sign.getFrontText());
        JsonArray back = signSide(sign.getBackText());
        if (front == null && back == null) return;        // completely blank sign: the block itself is enough
        JsonObject o = new JsonObject();
        o.addProperty("kind", "sign");
        o.addProperty("x", pos.getX() - x1);
        o.addProperty("y", pos.getY() - y1);
        o.addProperty("z", pos.getZ() - z1);
        o.add("front", front != null ? front : new JsonArray());
        o.add("back", back != null ? back : new JsonArray());
        if (front != null) {
            o.addProperty("frontColor", sign.getFrontText().getColor().getId());
            o.addProperty("frontGlow", sign.getFrontText().isGlowing());
        }
        if (back != null) {
            o.addProperty("backColor", sign.getBackText().getColor().getId());
            o.addProperty("backGlow", sign.getBackText().isGlowing());
        }
        entities.add(o);
        signsRead++;
    }

    /** One sign side's 4 lines as MiniMessage strings, or null if the whole side is blank. */
    private JsonArray signSide(net.minecraft.block.entity.SignText side) {
        JsonArray out = new JsonArray();
        boolean any = false;
        for (int i = 0; i < 4; i++) {
            net.minecraft.text.Text line = side.getMessage(i, false);
            String s = line == null ? "" : toMiniMessage(line);
            if (!s.isBlank()) any = true;
            out.add(s);
        }
        return any ? out : null;
    }

    /** Native styled Text -> MiniMessage (what the codec's comp() takes), falling back to plain text. */
    private static String toMiniMessage(net.minecraft.text.Text text) {
        try {
            var adventure = net.kyori.adventure.platform.modcommon.impl.NonWrappingComponentSerializer.INSTANCE
                    .deserialize(text);
            return dev.dfonline.codeclient.hypercube.HypercubeMiniMessage.MM.serialize(adventure);
        } catch (Throwable t) {
            return text.getString();
        }
    }

    private String rawItemSnbt(ItemStack stack) {
        try {
            NbtElement enc = ItemStack.CODEC.encodeStart(
                    CodeClient.MC.player.getRegistryManager().getOps(NbtOps.INSTANCE), stack).getOrThrow();
            return enc.toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    // --- equip an offhand +reach item so chests pick from afar (mirrors ScanPlot) ---
    private void equipReach() {
        var nh = CodeClient.MC.getNetworkHandler();
        if (nh == null) return;
        ItemStack item = new ItemStack(Items.STICK);
        var mods = AttributeModifiersComponent.builder()
                .add(EntityAttributes.BLOCK_INTERACTION_RANGE,
                        new EntityAttributeModifier(Identifier.of("dfvibe", "capture_reach"), REACH_BOOST,
                                EntityAttributeModifier.Operation.ADD_VALUE),
                        AttributeModifierSlot.OFFHAND)
                .build();
        item.set(DataComponentTypes.ATTRIBUTE_MODIFIERS, mods);
        nh.sendPacket(new CreativeInventoryActionC2SPacket(OFFHAND_SLOT, item));
        reachEquipped = true;
    }

    private void clearReach() {
        if (!reachEquipped) return;
        var nh = CodeClient.MC.getNetworkHandler();
        if (nh != null) nh.sendPacket(new CreativeInventoryActionC2SPacket(OFFHAND_SLOT, ItemStack.EMPTY));
        reachEquipped = false;
    }

    // --- write the capture files, then finish ---
    private void tickWrite() {
        phase = Phase.DONE;
        try {
            Files.createDirectories(OUT_DIR);
            // meta.json goes LAST: genschem requires it, so a capture that dies mid-write leaves a
            // directory the codec rejects outright instead of a plausible-looking partial capture.
            writePalette();
            writeBlocks();
            Files.writeString(OUT_DIR.resolve("entities.json"), entities.toString());
            writeMeta();
            SUMMARY = "blocks " + nonAir() + " palette " + palette.size() + " containers " + containersRead
                    + " signs " + signsRead + " heads " + headsRead
                    + (picksLost > 0 ? " UNREAD " + picksLost : "");
            if (picksLost > 0)
                Chat.warn(picksLost + " chest(s)/head(s) could NOT be read (even after a retry round) - "
                        + "their contents are MISSING from this capture. Re-run the save to get a clean 1-1 copy.");
            if (paletteFull)
                Chat.warn("This build uses more than " + MAX_PALETTE + " distinct block-states - the capture"
                        + " format can't index them all, so the extras were saved as AIR. Capture it in pieces.");
            if (interferenceCount > 0)
                Chat.info("capture: the plot's code moved us " + interferenceCount
                        + "x during the scan (re-routed each time).");
            Log.step("capture wrote " + OUT_DIR + ": " + SUMMARY);
            complete("ok");
        } catch (Exception e) {
            Chat.error("Capture write failed: " + e.getMessage());
            complete("error");
        }
    }

    private long nonAir() {
        long n = 0;
        for (short[] slab : chunkGrid) {                  // unallocated slab == all air
            if (slab == null) continue;
            for (short v : slab) if (v != 0) n++;         // cells outside the box stay 0, so they never count
        }
        return n;
    }

    private void writeMeta() throws Exception {
        JsonObject meta = new JsonObject();
        JsonArray min = new JsonArray(); min.add(x1); min.add(y1); min.add(z1);
        JsonArray dims = new JsonArray(); dims.add(W); dims.add(H); dims.add(L);
        meta.add("min", min);
        meta.add("dims", dims);
        meta.addProperty("dtype", "uint16");
        Files.writeString(OUT_DIR.resolve("meta.json"), new GsonBuilder().create().toJson(meta));
    }

    private void writePalette() throws Exception {
        JsonArray pal = new JsonArray();
        pal.add("minecraft:air");
        for (String key : palette.keySet()) pal.add(key); // insertion order == index order (1-based)
        Files.writeString(OUT_DIR.resolve("palette.json"), pal.toString());
    }

    /** Little-endian uint16 per cell, x,y,z C-order - streamed straight into the gzip file. (Building the whole
     *  raw buffer, then a second compressed one in memory, doubled a big region's footprint for no reason.) */
    private void writeBlocks() throws Exception {
        byte[] row = new byte[L * 2];                     // one z-run at a time; never the whole region
        try (var out = Files.newOutputStream(OUT_DIR.resolve("blocks.gz"));
             var buffered = new java.io.BufferedOutputStream(out, 1 << 16);
             GZIPOutputStream gz = new GZIPOutputStream(buffered)) {
            for (int x = x1; x <= x2; x++)
                for (int y = y1; y <= y2; y++) {
                    for (int z = z1; z <= z2; z++) {
                        int v = getCell(x, y, z);
                        int i = (z - z1) * 2;
                        row[i] = (byte) (v & 0xff);
                        row[i + 1] = (byte) ((v >> 8) & 0xff);
                    }
                    gz.write(row);
                }
        }
    }

    private void complete(String how) {
        RUNNING = false;
        FINISH_NOW = false;
        try { clearReach(); } catch (Throwable ignored) {}
        try { com.dfvibe.Progress.finish(); } catch (Throwable ignored) {}
        try { com.dfvibe.Eta.end(); } catch (Throwable ignored) {}
        try { if (CodeClient.MC.getNetworkHandler() != null) Utility.makeHolding(ItemStack.EMPTY); } catch (Throwable ignored) {}
        try { if (CodeClient.MC.player != null) CodeClient.MC.player.noClip = false; } catch (Throwable ignored) {}
        if (!"ok".equals(how) && SUMMARY.isEmpty()) SUMMARY = how;
        phase = Phase.DONE;
        callback();
    }
}
