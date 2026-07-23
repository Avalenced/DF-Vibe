package dev.dfonline.codeclient.action.impl;

import com.dfvibe.Log;
import dev.dfonline.codeclient.Callback;
import dev.dfonline.codeclient.CodeClient;
import dev.dfonline.codeclient.Utility;
import dev.dfonline.codeclient.action.Action;
import dev.dfonline.codeclient.hypercube.template.Template;
import dev.dfonline.codeclient.location.Dev;
import dev.dfonline.codeclient.location.Plot;
import net.minecraft.block.entity.SignBlockEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.c2s.play.CreativeInventoryActionC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInputC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.network.packet.s2c.play.ScreenHandlerSlotUpdateS2CPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.PlayerInput;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.regex.Pattern;

public class ScanPlot extends Action {
    /**
     * Calibration switch: when true, EVERY line is rebuilt from blocks (SalvageLine) instead of read as
     * a DF template - so a normal plot's salvage output can be diffed against a regular pull to prove the
     * reconstruction is byte-correct before it's trusted on real over-limit lines. Set per-scan by the
     * "scandeep" API verb (/df pull -verify) and reset when the scan ends. Off for normal pulls.
     */
    public static volatile boolean FORCE_SALVAGE = false;
    /**
     * -nomove: the scanner NEVER flies the player. It grabs every line whose starter is within (extended)
     * reach of where you stand, then tells you which way to fly for the rest; you reposition and pull again
     * (a pull keeps the .py files it already wrote, so repeated -nomove pulls accumulate). Set per-scan by
     * the mod before the scan and reset after. Also a safe way to scan when auto-flight is rubber-banding.
     */
    public static volatile boolean NO_MOVE = false;
    /**
     * No-owner deploy: after a line's template has been READ (captured for the backup), BREAK its starter block
     * so the very same scan both backs the old code up AND clears the codespace - because {@code /plot clear} is
     * owner-only and errors for a builder. Set by the {@code scanclear} WS verb, reset when the scan ends. The
     * scan's normal traversal (reach + sweep + chunk-loading) already visits every line, so this reuses all of
     * it instead of a second pass.
     */
    public static volatile boolean BREAK_AFTER_READ = false;
    /** /df showmissingchest: highlight every chest/line the running scan still hasn't read (via particles drawn
     *  by {@link dev.dfonline.codeclient.dev.MissingChestRenderer}), so you can see what's left. */
    public static volatile boolean SHOW_MISSING = false;
    public static boolean toggleShowMissing() { SHOW_MISSING = !SHOW_MISSING; return SHOW_MISSING; }

    /** Positions still to scan, for the highlighter: every walked salvage job's unread chests (the granular
     *  -chests pieces) + every discovered-but-ungrabbed line starter. Empty unless a scan is running and the
     *  toggle is on; capped so a huge plot can't spawn thousands of particles per tick. */
    public static java.util.List<BlockPos> missingTargets() {
        ScanPlot s = ACTIVE;
        if (s == null || !SHOW_MISSING) return java.util.List.of();
        java.util.ArrayList<BlockPos> out = new java.util.ArrayList<>();
        for (SalvageLine job : s.salvageJobs.values())
            if (job != null && !job.done) out.addAll(job.unreadChests());
        if (s.blocks != null)
            for (BlockPos b : s.blocks)
                if (!s.scanned.containsKey(b) && !s.skipped.contains(b)) { out.add(b); if (out.size() >= 500) break; }
        return out;
    }
    /** Set by /df end compile: the running scan finishes NOW with the lines grabbed so far (instead of being
     *  hard-cancelled and losing them). Honored at the top of tick(); reset when consumed or on a new scan. */
    public static volatile boolean STOP_NOW = false;
    /** Save-as-you-go (-save): if set, called with each line's base64 token the instant it's grabbed, so the
     *  pull can checkpoint progress to disk during a long scan. Null = collect-at-end as normal. */
    public static volatile java.util.function.Consumer<String> LINE_SINK = null;
    private static final int OFFHAND_SLOT = 45;
    private static final double REACH_RADIUS = 50.0;       // pick a starter within this many blocks (reach item gives ~64)
    private static final double COVER_RADIUS = 32.0;       // "swept a spot" = flew within 32 blocks of it (the scan can't end until you've been within 32 of every point)
    private boolean reachEquipped = false;
    private boolean useReach = false;                       // offhand +reach equipped: pick lines without flying onto each
    private double pickRangeSq;                             // how close (squared) to a line we must be to pick it
    /** The running scan, so the action-bar progress bar (com.dfvibe.Progress) can read its counts. */
    private static volatile ScanPlot ACTIVE;
    /**
     * A sink the scan pings every ~20s with "scan-progress N/M" so the websocket stays alive through a
     * very long scan (a -chests rebuild can run 15-20 min). Without it the client's poll timed out while
     * the scan kept ticking, and the eventual result was sent to a dead socket. Set by the Scan handler.
     */
    public static volatile java.util.function.Consumer<String> HEARTBEAT;
    private int heartbeatTick = 0;
    /**
     * The codespace box [x1,y1,z1,x2,y2,z2] for -nomove: defines what "the whole codespace" is, so a
     * continuous -nomove only finishes once you've physically been within reach of all of it (loading every
     * chunk first, which is what stops -chests reading unloaded garbage). Set by the mod from /df region, or
     * the detected plot. When set, the scan is region-DRIVEN and works even outside dev mode.
     */
    public static volatile int[] REGION;
    private int[] box;                              // resolved box for this run (REGION snapshot)
    private int[] etaBox;                           // codespace volume used to seed the time estimate
    private java.util.List<Vec3d> covSamples;       // grid sample points across the box
    private boolean[] covered;                       // which samples the player has been within reach of
    private int coveredCount = 0;
    private int reportTick = 0, discoverTick = 0;
    private Vec3d lastDiscoverPos = null;
    private int maxLineY = Integer.MIN_VALUE;        // highest layer any line was found on (for the "fly up" hint only)
    /** Lines resolved so far (read + un-readable), for the progress bar. */
    public static int progressScanned() { ScanPlot s = ACTIVE; return s == null ? 0 : s.scanned.size() + s.skipped.size(); }
    /** Lines discovered so far (grows as the sweep loads chunks), for the progress bar. */
    public static int progressTotal() { ScanPlot s = ACTIVE; return s == null || s.blocks == null ? 0 : s.blocks.size(); }
    /** /df end compile: ask the running scan to finish immediately with the lines it has. Returns false if no
     *  scan is running. The pull then decompiles + writes those (partial) lines as a normal result. */
    public static boolean requestFinish() {
        if (ACTIVE == null) return false;
        STOP_NOW = true;
        return true;
    }
    /** Hard-cancel teardown (/df end): a cancelled scan never reaches complete(), so drop the ACTIVE pointer
     *  here - otherwise progress counts, the missing-chest highlighter and /df end compile keep reading the
     *  dead scan. */
    public static void clearActive() {
        ACTIVE = null;
        STOP_NOW = false;
    }
    private static final Vec3d goToOffset = new Vec3d(0, 1.5, 0);
    private final ArrayList<ItemStack> returnList;
    private java.util.Set<BlockPos> blocks = null;
    private HashMap<BlockPos, ItemStack> scanned = null;
    // Lines we couldn't read AND couldn't salvage - skip them so the scan/pull doesn't hang. They won't
    // be in the pull.
    private final java.util.Set<BlockPos> skipped = new java.util.HashSet<>();
    // Lines DF refused to hand over as a template ("Exceeded the code data size limit"): instead of
    // dropping them, rebuild each from its physical blocks (SalvageLine) once the normal pickups finish.
    private final java.util.Set<BlockPos> toSalvage = new java.util.LinkedHashSet<>();
    // -nomove + -chests: a SalvageLine per line, kept across visits so a line whose far chests were out of
    // reach RESUMES (with its already-read args) as the player flies closer, instead of being dropped. Plus
    // the player position each line last DEFERRED from, so we don't re-attempt a stalled line every tick -
    // we wait until the player has actually moved toward it.
    private final java.util.HashMap<BlockPos, SalvageLine> salvageJobs = new java.util.HashMap<>();
    private final java.util.HashMap<BlockPos, Vec3d> salvageDeferredAt = new java.util.HashMap<>();
    private static final double SALVAGE_RETRY_MOVE_SQ = 8.0 * 8.0; // re-attempt a deferred line once moved 8+ blocks
    private Action step = null;
    // Chunk-loading sweep: waypoints down the length of the codespace, visited BEFORE pickup so every
    // chunk (hence every line-starter sign) loads. The client only holds a window of chunks around the
    // player, so without this a single scan misses far lines and /df pull silently drops them.
    private java.util.List<Vec3d> sweep = null;
    private int sweepIdx = 0;
    private int sweepSettle = 0;                     // ticks to wait at a waypoint (chunks finishing load) before re-scanning
    // ~0.5s pause at each waypoint. Shorter than the old 1s because we ALSO discover lines continuously while
    // FLYING now (discoverNearPlayer in tick), so most signs are already found before we even stop - the settle
    // is just a final catch, and the loop-until-dry re-sweep still backstops anything missed.
    private static final int SWEEP_SETTLE_TICKS = 10;
    private int sweepPass = 0;                        // how many full sweeps we've flown (we repeat until one finds nothing new)
    private int blocksAtPassStart = 0;               // blocks.size() when the current sweep pass began (to detect "found new")
    private static final int MAX_SWEEP_PASSES = 6;   // hard cap so flaky chunk-loading can't loop us forever

    public ScanPlot(Callback callback, ArrayList<ItemStack> scanList) {
        super(callback);
        this.returnList = scanList;
        // Dev mode only: every scan requires DF's dev location (a region box only changes HOW lines are
        // discovered, never where the player is allowed to be).
        if (!(CodeClient.location instanceof Dev)) {
            throw new IllegalStateException("Player must be in dev mode.");
        }
    }

    @Override
    public void init() {
        box = REGION;
        if (box != null) {
            // Region-driven discovery: find line-starter signs by scanning the box ourselves (no Dev needed,
            // no chunk-loading flight - -nomove loads chunks as the player flies through manually).
            blocks = new java.util.LinkedHashSet<>(regionScanForSigns(box));
            scanned = new HashMap<>();
            buildCoverageSamples(box);
            for (BlockPos b : blocks) if (b.getY() > maxLineY) maxLineY = b.getY();
            etaBox = box.clone();
        } else if (CodeClient.location instanceof Dev plot) {
            var found = plot.scanForSigns(Plot.lineStarterPattern, Pattern.compile(".*"));
            blocks = new java.util.LinkedHashSet<>(realStarters(found != null ? found.keySet() : java.util.Set.of()));
            scanned = new HashMap<>(blocks.size());
            // Build the chunk-loading sweep: fly down the centre of the codespace, a waypoint every
            // ~32 blocks (2 chunks) of length, at floor+2. Visiting these loads every chunk column so
            // the re-scan at each waypoint sees all the line-starters before we start picking up.
            var size = plot.assumeSize();
            double cx = plot.getX() - size.codeWidth / 2.0;
            int floor = plot.getFloorY();
            int z0 = plot.getZ(), z1 = plot.getZ() + size.codeLength;
            // The whole code volume (X spans the codeWidth WEST of the origin, Y from the floor up past the
            // highest line) - used to seed the up-front time estimate.
            int topY = floor;
            for (BlockPos b : blocks) if (b.getY() > topY) topY = b.getY();
            etaBox = new int[]{plot.getX() - size.codeWidth, floor, z0, plot.getX(), topY + 5, z1};
            sweep = new ArrayList<>();
            // A waypoint every ~48 blocks (3 chunks). The client loads chunks well beyond that as we fly, and
            // discovery now runs continuously DURING flight, so we don't need a stop every 2 chunks - fewer,
            // wider stops = a faster sweep. The far wall is always included so the tail end is covered.
            for (int z = z0; z <= z1; z += 48) sweep.add(new Vec3d(cx, floor + 2, z + 0.5));
            sweep.add(new Vec3d(cx, floor + 2, z1 + 0.5)); // always include the far wall
        }
        ACTIVE = this;
        STOP_NOW = false; // never inherit a stale early-finish request from a prior scan
        // Use the offhand +reach item for the normal scan too (default on): one position covers a whole
        // cluster of lines, so we fly FAR less - the difference between a big plot finishing inside the
        // websocket timeout and not. -nomove forces it on; otherwise it follows the salvageReach config.
        useReach = NO_MOVE
                || (com.dfvibe.DfVibeClient.config != null && com.dfvibe.DfVibeClient.config.salvageReach);
        pickRangeSq = useReach ? (REACH_RADIUS * REACH_RADIUS) : 9.0; // ~3 blocks without reach (must be on top)
        if (useReach) equipReach();
        com.dfvibe.Progress.start(FORCE_SALVAGE ? "Rebuild" : (NO_MOVE ? "Grab" : "Pull"),
                ScanPlot::progressScanned, ScanPlot::progressTotal);
        // Time estimate (the SLOW path only: -chests rebuilds; a plain pull is fast and keeps the simple
        // observed-rate ETA). Seed from the codespace box so we have a real travel+scan ETA INSTANTLY
        // (before any chunk loads), then refine from observed speed/grab-rate.
        if (FORCE_SALVAGE && CodeClient.MC.player != null && etaBox != null) {
            com.dfvibe.Eta.begin(etaBox, FORCE_SALVAGE, 9.0, CodeClient.MC.player.getEntityPos());
            com.dfvibe.Chat.info(com.dfvibe.Eta.instantSummary(CodeClient.MC.player.getEntityPos())); // "when do I come back"
        }
    }

    /** Finish the scan: stop the progress bar, empty the held slot/offhand, and return the collected lines. */
    private void complete() {
        try { com.dfvibe.Progress.finish(); } catch (Throwable ignored) {}
        try { com.dfvibe.Eta.end(); } catch (Throwable ignored) {}
        ACTIVE = null;
        BREAK_AFTER_READ = false;  // never leak the no-owner break into the next scan
        try { if (CodeClient.MC.player != null) CodeClient.MC.player.noClip = false; } catch (Throwable ignored) {} // never leave scan noclip on
        try { Utility.makeHolding(ItemStack.EMPTY); } catch (Throwable ignored) {} // clear the last picked item from hand
        clearReach();
        returnList.clear();
        returnList.addAll(scanned.values());
        callback();
    }

    /** Put a big +block-interaction-range item in the offhand so -nomove can pick distant line-starters. */
    private void equipReach() {
        var nh = CodeClient.MC.getNetworkHandler();
        if (nh == null) return;
        ItemStack item = new ItemStack(net.minecraft.item.Items.STICK);
        var mods = net.minecraft.component.type.AttributeModifiersComponent.builder()
                .add(net.minecraft.entity.attribute.EntityAttributes.BLOCK_INTERACTION_RANGE,
                        new net.minecraft.entity.attribute.EntityAttributeModifier(
                                net.minecraft.util.Identifier.of("dfvibe", "scan_reach"), 60.0,
                                net.minecraft.entity.attribute.EntityAttributeModifier.Operation.ADD_VALUE),
                        net.minecraft.component.type.AttributeModifierSlot.OFFHAND)
                .build();
        item.set(net.minecraft.component.DataComponentTypes.ATTRIBUTE_MODIFIERS, mods);
        nh.sendPacket(new CreativeInventoryActionC2SPacket(OFFHAND_SLOT, item));
        reachEquipped = true;
    }

    private void clearReach() {
        if (!reachEquipped) return;
        var nh = CodeClient.MC.getNetworkHandler();
        if (nh != null) nh.sendPacket(new CreativeInventoryActionC2SPacket(OFFHAND_SLOT, ItemStack.EMPTY));
        reachEquipped = false;
    }

    private boolean inReach(BlockPos pos) {
        return CodeClient.MC.player.getEntityPos().squaredDistanceTo(pos.toCenterPos()) <= pickRangeSq;
    }

    /** Record a grabbed line and, under -save, stream its token out immediately so the pull can checkpoint it. */
    private void recordLine(BlockPos pos, ItemStack stack) {
        scanned.put(pos, stack);
        var sink = LINE_SINK;
        if (sink != null) {
            try {
                String data = Utility.templateDataItem(stack);
                if (data != null) sink.accept(data);
            } catch (Throwable ignored) {}
        }
        // No-owner deploy: now that this line is safely captured (it's our backup), break it so the scan also
        // CLEARS the codespace. Whole line goes when its starter block is broken.
        if (BREAK_AFTER_READ) breakStarter(pos);
    }

    /**
     * Break a line's starter MATERIAL block (one block EAST of its west-facing starter sign) with a sneak
     * (shift) left-click - in DiamondFire the whole line is removed with its starter. Creative insta-break:
     * START_DESTROY then ABORT, mirroring the swap-replace in PlaceTemplates. Used only by the no-owner deploy
     * (BREAK_AFTER_READ) to clear the codespace when {@code /plot clear} is refused (owner-only). Relies on the
     * same offhand +reach the scan picks lines with, so a whole cluster breaks from one stop.
     */
    private void breakStarter(BlockPos signPos) {
        var net = CodeClient.MC.getNetworkHandler();
        var player = CodeClient.MC.player;
        if (net == null || player == null) return;
        BlockPos mat = signPos.east(); // material block sits one EAST of the starter sign (per the physical layout)
        boolean sneaky = !player.isSneaking();
        if (sneaky) net.sendPacket(new PlayerInputC2SPacket(new PlayerInput(false, false, false, false, false, true, false)));
        net.sendPacket(new PlayerActionC2SPacket(PlayerActionC2SPacket.Action.START_DESTROY_BLOCK, mat, Direction.UP));
        net.sendPacket(new PlayerActionC2SPacket(PlayerActionC2SPacket.Action.ABORT_DESTROY_BLOCK, mat, Direction.UP));
        if (sneaky) net.sendPacket(new PlayerInputC2SPacket(CodeClient.MC.player.getLastPlayerInput()));
    }

    /** Find line-starter signs by scanning a box directly (DF-location-independent). Signs sit on layer
     *  floors (multiples of 5 in Y), so we step Y by 5; only LOADED chunks return block entities. */
    private java.util.Set<BlockPos> regionScanForSigns(int[] b) {
        java.util.LinkedHashSet<BlockPos> out = new java.util.LinkedHashSet<>();
        var world = CodeClient.MC.world;
        if (world == null) return out;
        int x1 = Math.min(b[0], b[3]), x2 = Math.max(b[0], b[3]);
        int y1 = Math.min(b[1], b[4]), y2 = Math.max(b[1], b[4]);
        int z1 = Math.min(b[2], b[5]), z2 = Math.max(b[2], b[5]);
        int ys = y1 + ((5 - (y1 % 5 + 5) % 5) % 5); // first multiple of 5 >= y1
        for (int y = ys; y <= y2; y += 5)
            for (int x = x1; x <= x2; x++)
                for (int z = z1; z <= z2; z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    // A real line-starter sign sits on the WEST face of its code-block material (one block
                    // EAST). A floating/decorative sign - common just outside the codespace - has AIR there;
                    // skip it so it isn't grabbed as a line that can never be read (3 stray signs = 3 "missing"
                    // lines). isColumnLoaded() is exactly this east-of-sign block check.
                    if (world.getBlockEntity(pos) instanceof net.minecraft.block.entity.SignBlockEntity sign
                            && Plot.lineStarterPattern.matcher(sign.getFrontText().getMessage(0, false).getString()).matches()
                            && !world.getBlockState(pos.east()).isAir())
                        out.add(pos);
                }
        return out;
    }

    /** Lay a coarse grid of sample points across the box; -nomove is "done" only when the player has been
     *  within reach of every sample (i.e. physically swept the whole codespace, loading all its chunks). */
    private void buildCoverageSamples(int[] b) {
        covSamples = new ArrayList<>();
        int x1 = Math.min(b[0], b[3]), x2 = Math.max(b[0], b[3]);
        int y1 = Math.min(b[1], b[4]), y2 = Math.max(b[1], b[4]);
        int z1 = Math.min(b[2], b[5]), z2 = Math.max(b[2], b[5]);
        int midX = (x1 + x2) / 2; // codespace is thin in X (~20) - one column of samples
        int midY = (y1 + y2) / 2; // Y is IRRELEVANT to coverage: chunks load full-height, so flying the length once
        // at ANY Y loads every layer's chunks. Coverage is therefore horizontal-only (a Z strip) - "have I flown
        // the length + loaded all chunks", NOT "have I been within 32 of every point in 3D" (the old vertical
        // zig-zag that made finishing crawl). Discovery reads the full Y range of the loaded chunks (discoverNearPlayer).
        for (int z = z1; z <= z2; z += 16) // fine spacing so "covered" approximates "within 32 of every point"
            covSamples.add(new Vec3d(midX, midY, z));
        covered = new boolean[covSamples.size()];
    }

    /** Mark every sample the player is now within COVER_RADIUS of (HORIZONTAL X/Z only - Y is ignored because
     *  chunks load full-height, so being over a spot at any height counts as having loaded its whole column). */
    private void markCoverage() {
        if (covSamples == null) return;
        Vec3d p = CodeClient.MC.player.getEntityPos();
        for (int i = 0; i < covSamples.size(); i++) {
            if (covered[i]) continue;
            Vec3d s = covSamples.get(i);
            double dx = s.x - p.x, dz = s.z - p.z;
            if (dx * dx + dz * dz <= COVER_RADIUS * COVER_RADIUS) { covered[i] = true; coveredCount++; }
        }
    }

    /** Covered/total over the region box's Z strip. Coverage is horizontal (chunks are full-height), so this is
     *  "have I flown the whole length + loaded every chunk", satisfied by ONE pass at any height - no vertical
     *  zig-zag. Discovery (full-Y over the loaded chunks) then finds every line at every layer as we pass. */
    private int[] relevantCoverage() {
        if (covSamples == null) return new int[]{0, 0};
        return new int[]{coveredCount, covSamples.size()};
    }

    private boolean boxFullyCovered() {
        if (covSamples == null || covSamples.isEmpty()) return false;
        return coveredCount >= covSamples.size();
    }

    /** Nearest line-starter that's in reach right now and not yet handled, or null. */
    private BlockPos nearestInReach() {
        Vec3d p = CodeClient.MC.player.getEntityPos();
        BlockPos best = null;
        double bestSq = Double.MAX_VALUE;
        for (BlockPos pos : blocks) {
            if (scanned.containsKey(pos) || skipped.contains(pos) || toSalvage.contains(pos)) continue;
            double d = p.squaredDistanceTo(pos.toCenterPos());
            if (d <= pickRangeSq && d < bestSq) { bestSq = d; best = pos; }
        }
        return best;
    }

    /** Throttled chat nudge toward the next unswept area. Only NORTH/SOUTH (Z) and UP/DOWN (Y) - the
     *  codespace is too thin in X for east/west to be meaningful. */
    private void maybeReportDirection(Vec3d best, boolean toPendingLine) {
        if (++reportTick % 60 != 0) return; // ~every 3s
        Vec3d p = CodeClient.MC.player.getEntityPos();
        // Suggest ONE straight axis at a time (never a diagonal): go north/south first (lines run along Z),
        // then up/down (layers stack in Y), and only east/west last (the codespace is thin in X).
        String d = axisToward(best.x - p.x, best.y - p.y, best.z - p.z);
        int[] cov = relevantCoverage();
        int pct = cov[1] == 0 ? 0 : (int) Math.round(100.0 * cov[0] / cov[1]);
        String dir = d.isEmpty() ? "" : " (" + d + ")";
        if (toPendingLine)
            com.dfvibe.Chat.info("-nomove " + pct + "% swept - " + pendingCount()
                    + " line(s) loaded but not yet grabbed; follow the particles" + dir + " back to them.");
        else
            com.dfvibe.Chat.info("-nomove " + pct + "% swept - follow the white particles" + dir
                    + " to the last unswept ground; I'm grabbing lines as you pass.");
    }

    private int pendingCount() {
        int n = 0;
        for (BlockPos pos : blocks) if (!scanned.containsKey(pos) && !skipped.contains(pos)) n++;
        return n;
    }

    /** A single straight cardinal/vertical direction to move along, prioritised N/S (Z) then up/down (Y) then
     *  E/W (X) - so guidance is always one clean axis (north, then up, …), never a confusing diagonal, and
     *  east/west is only ever suggested once the other two axes are lined up. "" = already aligned. */
    private static String axisToward(double dx, double dy, double dz) {
        if (dz > 8) return "south (+Z)";
        if (dz < -8) return "north (-Z)";
        if (dy > 8) return "up";
        if (dy < -8) return "down";
        if (dx > 8) return "east (+X)";
        if (dx < -8) return "west (-X)";
        return "";
    }

    /**
     * Re-run the sign scan after the flight has loaded more chunks; returns how many NEW line-starters
     * turned up. On a big plot the client only holds a window of chunks around the player, so a single
     * scan at the start misses every line in a not-yet-loaded chunk. Re-scanning as we fly (the flight
     * loads chunks along the way) catches them - without this, /df pull silently drops far lines and
     * /df fill thinks they're missing and places duplicates.
     */
    private int rescan() {
        if (!(CodeClient.location instanceof Dev plot)) return 0;
        var found = plot.scanForSigns(Plot.lineStarterPattern, Pattern.compile(".*"));
        if (found == null) return 0;
        int before = blocks.size();
        blocks.addAll(realStarters(found.keySet()));
        return blocks.size() - before;
    }

    /** Scanning flies the player line-to-line with the hop mover, which wants NoClip (wall-sanitized
     *  teleport movement). */
    @Override
    public boolean wantsNoClip() {
        return true;
    }

    @Override
    public boolean onReceivePacket(Packet<?> packet) {
        if (step != null && step.onReceivePacket(packet)) return true;
        return false;
    }

    /**
     * True if a picked-up template actually belongs to the line whose starter SIGN is at {@code pos}.
     * The scan's pickup consumes the next slot-update bearing a template, but a STALE update left over from
     * a previous line (whose pickup overlapped - e.g. because this line's chunk briefly unloaded so DF never
     * answered) would otherwise be filed under THIS position: a phantom duplicate of the other line, and this
     * line silently dropped. (Diffing repeat pulls showed missing-line count == phantom-duplicate count - the
     * tell-tale of exactly this race.) We match the template's first-block name against the sign; a mismatch
     * means it's not our line's response, so we ignore it and keep waiting - and a line DF never cleanly hands
     * over falls through to block-salvage instead of being mis-recorded. The sign's line-1 text IS the
     * canonical action/func name (the -chests salvage path builds correct lines straight from it).
     */
    private boolean templateMatchesSign(Template t, BlockPos pos) {
        if (t.blocks == null || t.blocks.isEmpty()) return false;
        var first = t.blocks.get(0);
        String tName = first.data != null ? first.data : first.action;
        if (tName == null) return false; // a starter always has a name; no name -> not the response we want
        if (!(CodeClient.MC.world.getBlockEntity(pos) instanceof SignBlockEntity sign)) return false; // not loaded -> retry/salvage
        String want = sign.getFrontText().getMessage(1, false).getString().trim();
        return !want.isEmpty() && want.equals(tName.trim());
    }

    @Nullable
    private BlockPos findNextBlock() {
        // Prefer the player's current layer (drain it before changing height), then nearest
        // horizontally - same as the placer, so the scan glides above the chests instead of bobbing.
        Vec3d player = CodeClient.MC.player.getEntityPos();
        int playerLayer = (int) Math.floor(player.y / 5.0);
        BlockPos best = null;
        double bestMetric = Double.MAX_VALUE;
        for (BlockPos pos : blocks) {
            if (scanned.containsKey(pos) || skipped.contains(pos) || toSalvage.contains(pos)) continue; // done, dead, or queued for salvage
            Vec3d c = pos.toCenterPos();
            double dx = c.x - player.x, dz = c.z - player.z;
            double horiz = Math.sqrt(dx * dx + dz * dz);
            int opLayer = (int) Math.floor(c.y / 5.0);
            double metric = horiz + 100000.0 * Math.abs(playerLayer - opLayer);
            if (metric < bestMetric) {
                bestMetric = metric;
                best = pos;
            }
        }
        return best;
    }

    private void next() {
        // NO_MOVE (player flies) uses a position-centric loop: grab every line in reach across the whole
        // codespace, defer the rest, and only finish once it's all swept.
        if (NO_MOVE) { nextNoMove(); return; }
        if (FORCE_SALVAGE) {
            // Calibration: route every discovered line through block-salvage instead of template pickup.
            for (BlockPos p : blocks) if (!scanned.containsKey(p) && !skipped.contains(p)) toSalvage.add(p);
            if (!toSalvage.isEmpty()) { startSalvage(); return; }
            if (rescan() > 0) { next(); return; }
            complete();
            return;
        }
        BlockPos block = findNextBlock();
        // Out of known lines? Re-scan first - the flight just loaded more chunks, which may reveal
        // line-starters that were invisible (unloaded) at the start. Only finish when a fresh scan
        // turns up nothing new, so we never silently drop far lines on a big plot.
        if (block == null && rescan() > 0) {
            block = findNextBlock();
        }
        if (block == null) {
            // Every line has been picked up (or queued). Rebuild the over-limit ones from blocks, one at
            // a time, before finishing - so they end up in the pull instead of silently missing.
            if (!toSalvage.isEmpty()) { startSalvage(); return; }
            complete(); // -nomove coverage/direction is reported by SyncService after the pull
            return;
        }
        final BlockPos target = block; // effectively-final capture for the lambda
        // Already in reach? Pick it straight from here - no flight. The whole line's template comes from
        // its STARTER, so length is moot, and with the reach item one spot covers a cluster of lines. This
        // is what makes a big pull fast enough to finish inside the websocket timeout.
        if (inReach(target)) {
            step = new PickUpBlock(target, () -> this.step = null);
            step.init();
            return;
        }
        step = new GoTo(target.toCenterPos().add(goToOffset), () -> {
            this.step = new PickUpBlock(target, () -> {
                this.step = null;
//                next(progress + 1);
            });
            this.step.init();
        });
        step.init();
    }

    /**
     * Rebuild one over-limit line from its physical blocks. On success its reconstructed template joins
     * {@code scanned} exactly as a normal pickup would; on failure it falls through to {@code skipped}.
     * Either way the line leaves {@code toSalvage}, so {@link #next()} moves on to the rest.
     */
    /**
     * Continuous -nomove tick: grab every line currently in reach (template-pickup, or salvage under -chests),
     * mark coverage, and - crucially - DON'T finish until the player has been within reach of the whole
     * codespace box. While there's nothing in reach and the box isn't swept yet, we just nudge them toward
     * the nearest unswept spot and wait; they fly there manually (loading those chunks) and we grab as they pass.
     */
    private void nextNoMove() {
        markCoverage();
        discoverNearPlayer();              // grab whatever's loaded AROUND you - fly anywhere, it keeps finding lines
        if (FORCE_SALVAGE) {
            // -chests: resume the nearest line we can make progress on right now (chunk loaded, a chest in
            // reach, and not just deferred from this exact spot). Its SalvageLine persists between visits, so
            // a line whose far chests were out of reach earlier picks up where it left off as you fly closer.
            BlockPos s = pickNoMoveSalvage();
            if (s != null) { resumeSalvage(s); return; }
        } else {
            BlockPos t = nearestInReach();
            if (t != null) { step = new PickUpBlock(t, () -> this.step = null); step.init(); return; }
        }
        if (!toSalvage.isEmpty()) { startSalvage(); return; } // finish queued salvage before idling

        // The scan can ONLY end when BOTH are true:
        //   1. every line we've LOADED has been scanned (nothing discovered-but-ungrabbed), and
        //   2. you've been within 32 blocks of every point in the codespace.
        // Until then it waits and guides you - first back to any line it loaded but couldn't grab, then to
        // ground you haven't swept yet.
        BlockPos pend = nearestPending();
        boolean allCovered = boxFullyCovered();
        if (pend == null && allCovered) {
            int sk = skipped.size();
            com.dfvibe.Chat.good("-nomove: whole codespace swept, " + scanned.size() + " line(s)"
                    + (sk > 0 ? " (" + sk + " unreadable)" : "") + ".");
            complete();
            return;
        }
        Vec3d aim = (pend != null) ? pend.toCenterPos() : nearestUncovered();
        if (aim == null) return;
        if ((discoverTick & 1) == 0) drawGuide(aim); // every 2 ticks - redraws fast enough that the short-lived line stays solid
        maybeReportDirection(aim, pend != null);
    }

    /** A discovered (loaded at some point) line that still hasn't been scanned - nearest to the player. The
     *  scan won't end while any of these remain, so it guides you back to them. */
    private BlockPos nearestPending() {
        Vec3d p = CodeClient.MC.player.getEntityPos();
        BlockPos best = null;
        double bestSq = Double.MAX_VALUE;
        for (BlockPos pos : blocks) {
            if (scanned.containsKey(pos) || skipped.contains(pos)) continue; // already grabbed (or tried + failed)
            double d = p.squaredDistanceTo(pos.toCenterPos());
            if (d < bestSq) { bestSq = d; best = pos; }
        }
        return best;
    }

    /** Discover loaded line-starters in a box AROUND the player so you can fly anywhere and it keeps
     *  compiling lines as their chunks load. CLIPPED to the codespace region when one is set, so it never
     *  grabs (or guides you toward) code from a neighbouring plot you can't reach. Throttled + only when moved. */
    private void discoverNearPlayer() {
        if (++discoverTick % 20 != 0) return; // ~1s
        Vec3d p = CodeClient.MC.player.getEntityPos();
        if (lastDiscoverPos != null && p.squaredDistanceTo(lastDiscoverPos) < 64) return; // not moved enough
        lastDiscoverPos = p;
        int[] near = {(int) p.x - 70, (int) p.y - 45, (int) p.z - 70, (int) p.x + 70, (int) p.y + 45, (int) p.z + 70};
        if (box != null) {
            // Chunks load full-height: once the X/Z footprint around us is loaded, EVERY Y layer is readable. So
            // scan the FULL box Y range (not just ±45 around the player) - one horizontal pass then discovers lines
            // at every height, no vertical zig-zag. X/Z stay windowed (±70) so cost stays bounded to loaded chunks.
            near[1] = Math.min(box[1], box[4]);
            near[4] = Math.max(box[1], box[4]);
            near = intersect(near, box); // confine discovery to the set region - nothing from other plots
            if (near == null) return;    // player is entirely outside the region right now: nothing here is ours
        }
        for (BlockPos b : regionScanForSigns(near)) {
            if (!inBox(b)) continue;     // belt-and-braces: never add a starter outside the codespace box
            blocks.add(b);
            if (b.getY() > maxLineY) maxLineY = b.getY();
        }
    }

    /** True if pos is inside the codespace region (always true when no region is set). */
    private boolean inBox(BlockPos pos) {
        if (box == null) return true;
        return pos.getX() >= Math.min(box[0], box[3]) && pos.getX() <= Math.max(box[0], box[3])
                && pos.getY() >= Math.min(box[1], box[4]) && pos.getY() <= Math.max(box[1], box[4])
                && pos.getZ() >= Math.min(box[2], box[5]) && pos.getZ() <= Math.max(box[2], box[5]);
    }

    /** Intersection of two [x1,y1,z1,x2,y2,z2] boxes, or null if they don't overlap. */
    private static int[] intersect(int[] a, int[] b) {
        int x1 = Math.max(Math.min(a[0], a[3]), Math.min(b[0], b[3]));
        int x2 = Math.min(Math.max(a[0], a[3]), Math.max(b[0], b[3]));
        int y1 = Math.max(Math.min(a[1], a[4]), Math.min(b[1], b[4]));
        int y2 = Math.min(Math.max(a[1], a[4]), Math.max(b[1], b[4]));
        int z1 = Math.max(Math.min(a[2], a[5]), Math.min(b[2], b[5]));
        int z2 = Math.min(Math.max(a[2], a[5]), Math.max(b[2], b[5]));
        if (x1 > x2 || y1 > y2 || z1 > z2) return null;
        return new int[]{x1, y1, z1, x2, y2, z2};
    }

    /** Nearest box sample the player hasn't been within reach of yet (for the guide line / nudge), or null. */
    private Vec3d nearestUncovered() {
        if (covSamples == null) return null;
        Vec3d p = CodeClient.MC.player.getEntityPos();
        Vec3d best = null;
        double bestSq = Double.MAX_VALUE;
        for (int i = 0; i < covSamples.size(); i++) {
            if (covered[i]) continue;
            Vec3d s = covSamples.get(i);
            double dx = s.x - p.x, dz = s.z - p.z, d = dx * dx + dz * dz; // nearest by horizontal X/Z
            if (d < bestSq) { bestSq = d; best = s; }
        }
        return best;
    }

    /** Draw a white particle line from the player toward where they should fly next. */
    private void drawGuide(Vec3d target) {
        if (CodeClient.MC.world == null || CodeClient.MC.player == null) return;
        Vec3d p0 = CodeClient.MC.player.getEntityPos().add(0, 1.0, 0);
        // Trace the route as STRAIGHT axis-aligned segments in the same priority the text gives - north/south
        // first, then up/down, then east/west LAST - instead of a single diagonal. You can't fly a clean
        // diagonal in Minecraft, so this shows exactly the straight legs to follow, one axis at a time.
        Vec3d p1 = new Vec3d(p0.x, p0.y, target.z);       // N/S
        Vec3d p2 = new Vec3d(p0.x, target.y, target.z);   // up/down
        Vec3d p3 = new Vec3d(target.x, target.y, target.z); // E/W
        int budget = 64;
        budget = drawSegment(p0, p1, budget);
        budget = drawSegment(p1, p2, budget);
        drawSegment(p2, p3, budget);
    }

    /** Draw a straight run of short-lived END_ROD dots (one per block) from a to b, capped at {@code budget}
     *  dots; returns the remaining budget. */
    private int drawSegment(Vec3d a, Vec3d b, int budget) {
        Vec3d d = b.subtract(a);
        double len = d.length();
        if (len < 0.5 || budget <= 0) return budget;
        Vec3d stepv = d.normalize();                      // a dot every block
        int n = Math.min(budget, (int) Math.round(len));
        Vec3d cur = a;
        for (int i = 0; i < n; i++) {
            cur = cur.add(stepv);
            var pt = CodeClient.MC.particleManager.addParticle(net.minecraft.particle.ParticleTypes.END_ROD,
                    cur.x, cur.y, cur.z, 0.0, 0.0, 0.0);
            if (pt != null) pt.setMaxAge(3); // short life: the old line dies before the next redraw -> sharp
        }
        return budget - n;
    }

    /**
     * -nomove: pick the nearest line we can usefully salvage from where the player stands right now -
     * not already done, its column chunk loaded, with at least one unread chest in reach, and not one we
     * just deferred from this same spot (which would tight-loop). Returns its starter, or null if nothing
     * is actionable here (the caller then guides the player toward the nearest unfinished line).
     */
    private BlockPos pickNoMoveSalvage() {
        Vec3d p = CodeClient.MC.player.getEntityPos();
        BlockPos best = null;
        double bestSq = Double.MAX_VALUE;
        for (BlockPos s : blocks) {
            if (scanned.containsKey(s) || skipped.contains(s)) continue;
            SalvageLine job = salvageJobs.get(s);
            boolean walked = job != null && job.isWalked();
            // The starter's chunk is only needed to WALK the column (read its blocks). Once a line is walked
            // its chests are known positions, and reading one needs nothing but pick range. Gating a walked
            // line on its starter DEADLOCKED every long line: a chest near the far end is beyond render
            // distance of the starter, so no single position ever satisfied both "starter loaded" and "chest
            // in reach" - the sweep flew onto the chest, this loop refused the line, and after a few cycles
            // the whole line was skipped as unreadable.
            if (!walked && !isColumnLoaded(s)) continue;                                 // chunk not here yet
            boolean chestInReach = walked && job.hasUnreadChestInReach(p);
            // A walked line with NO chest in reach can make no progress from here - skip it (the sweep flies
            // us closer). A not-yet-walked line we always let try once (it walks, reads what's reachable).
            if (walked && !chestInReach) continue;
            // "Don't retry a line we just deferred from <8 blocks" prevents a tight loop when no progress is
            // possible - but if a chest IS in reach now we CAN make progress (read it, or give it up after
            // retries), so the guard must NOT block that. This was the auto-sweep LIVELOCK: it flew us right
            // onto the deferred chest, then this guard refused to read it, so it flew there forever.
            if (!chestInReach) {
                Vec3d at = salvageDeferredAt.get(s);
                if (at != null && p.squaredDistanceTo(at) < SALVAGE_RETRY_MOVE_SQ) continue; // moved too little since defer
            }
            // Rank WALKED lines by their nearest unread CHEST, not their starter: as the player flies
            // through a dense codespace, the thing about to leave reach is a chest mid-column - reading
            // in chest order harvests everything they pass instead of revisiting whole lines by starter
            // (the "I flew right through them and they didn't save" report). Unwalked lines still rank
            // by starter (their chests aren't known until walked).
            double d;
            if (walked) {
                BlockPos c = job.nearestUnreadChest(p);
                d = (c != null) ? p.squaredDistanceTo(c.toCenterPos()) : p.squaredDistanceTo(s.toCenterPos());
            } else {
                d = p.squaredDistanceTo(s.toCenterPos());
            }
            if (d < bestSq) { bestSq = d; best = s; }
        }
        return best;
    }

    /** Start (or resume) the -nomove SalvageLine for this line; it reads every reachable chest then either
     *  finishes (scanned/skipped) or defers for a later visit, via {@link #salvageJobs}. */
    private void resumeSalvage(final BlockPos pos) {
        SalvageLine job = salvageJobs.get(pos);
        if (job == null) {
            job = new SalvageLine(pos, () -> onSalvageReturn(pos));
            salvageJobs.put(pos, job);
            this.step = job;
            this.step.init();
        } else {
            this.step = job; // resume the same instance next tick (keeps read args, walked state, etc.)
        }
    }

    /** A -nomove salvage session returned: record a finished line, or note a deferral so we resume it later. */
    private void onSalvageReturn(BlockPos pos) {
        this.step = null;
        SalvageLine job = salvageJobs.get(pos);
        if (job == null) return;
        if (job.done) {
            if (job.resultCode != null) recordLine(pos, Utility.makeTemplate(job.resultCode));
            else skipped.add(pos);
            salvageJobs.remove(pos);
            salvageDeferredAt.remove(pos);
        } else {
            // Deferred: more chests remain but none in reach. Remember where we were so we don't retry until
            // the player has flown closer; the line stays "pending" so the scan can't finish without it.
            salvageDeferredAt.put(pos, CodeClient.MC.player.getEntityPos());
        }
    }

    /** Is the line's column loaded (its starter material, one EAST of the sign, present)? */
    private boolean isColumnLoaded(BlockPos starter) {
        var world = CodeClient.MC.world;
        return world != null && !world.getBlockState(starter.east()).isAir();
    }

    /** Keep only signs whose code-block material is present (one block EAST); drops floating/decorative signs
     *  (air to the east) so they aren't grabbed as unreadable "lines". Safe at discovery time - a sign that
     *  was found has a loaded chunk, so its adjacent block state is reliable. */
    private java.util.Set<BlockPos> realStarters(java.util.Collection<BlockPos> signs) {
        var world = CodeClient.MC.world;
        java.util.LinkedHashSet<BlockPos> out = new java.util.LinkedHashSet<>();
        if (world != null) for (BlockPos p : signs) if (!world.getBlockState(p.east()).isAir()) out.add(p);
        return out;
    }

    private void startSalvage() {
        final BlockPos pos = nearestSalvage();
        final SalvageLine[] holder = new SalvageLine[1];
        holder[0] = new SalvageLine(pos, () -> {
            String code = holder[0].resultCode;
            if (code != null) recordLine(pos, Utility.makeTemplate(code));
            else skipped.add(pos);
            toSalvage.remove(pos);
            this.step = null;
        });
        this.step = holder[0];
        this.step.init();
    }

    /** The queued-for-salvage line nearest the player (same layer-first metric as the pickup picker), so
     *  salvage flies in a sensible order instead of jumping around the codespace at random. */
    private BlockPos nearestSalvage() {
        Vec3d player = CodeClient.MC.player.getEntityPos();
        int playerLayer = (int) Math.floor(player.y / 5.0);
        BlockPos best = null;
        double bestMetric = Double.MAX_VALUE;
        for (BlockPos pos : toSalvage) {
            Vec3d c = pos.toCenterPos();
            double dx = c.x - player.x, dz = c.z - player.z;
            double metric = Math.sqrt(dx * dx + dz * dz) + 100000.0 * Math.abs(playerLayer - (int) Math.floor(c.y / 5.0));
            if (metric < bestMetric) { bestMetric = metric; best = pos; }
        }
        return best != null ? best : toSalvage.iterator().next();
    }

    @Override
    public void tick() {
        if (blocks == null) return;
        // Feed the time estimator the live player position + counts every tick (before any early return), so the
        // observed flight speed and grab rate stay current whether we're flying out, sweeping, or grabbing.
        if (CodeClient.MC.player != null)
            com.dfvibe.Eta.sample(CodeClient.MC.player.getEntityPos(), progressScanned(), progressTotal());
        // /df end compile: stop here and return everything grabbed so far (the pull writes it as a partial
        // result) instead of hard-cancelling and losing the whole scan. Abandons any in-flight pickup - fine,
        // a later pull re-grabs it.
        if (STOP_NOW) {
            STOP_NOW = false;
            Log.step("scan finishing early (/df end compile) - " + scanned.size() + " line(s) grabbed");
            complete();
            return;
        }
        // Keep the websocket alive on long scans: ping progress every ~20s (400 ticks).
        if (++heartbeatTick % 400 == 0 && HEARTBEAT != null) {
            try { HEARTBEAT.accept("scan-progress " + progressScanned() + "/" + progressTotal()); } catch (Throwable ignored) {}
        }
        // /df stop pause: freeze progress in place, but keep the heartbeat above alive; don't step/grab
        // until /df resume.
        if (com.dfvibe.SyncService.PAUSED) {
            return;
        }
        // Sweep: discover line-starters WHILE flying a sweep leg too (cheap bounded scan, self-throttled),
        // not only during the stop. So most signs are found en route and the per-waypoint settle can be short -
        // the sweep finishes faster without losing lines (loop-until-dry still re-checks).
        if (!NO_MOVE && sweep != null && step instanceof GoTo) discoverNearPlayer();
        if (step != null) { step.tick(); return; }
        // Phase 1 - chunk-loading sweep: fly each waypoint, SETTLE so the chunks we just flew into finish
        // delivering their sign block-entities, THEN re-scan. The settle is essential: re-scanning the instant
        // we arrived ran before the new chunks' signs had arrived, so far lines were intermittently missed
        // (a pull would silently drop a different handful each run). SKIPPED in -nomove (we don't fly).
        if (!NO_MOVE && sweep != null && sweepIdx < sweep.size()) {
            if (sweepSettle > 0) {                       // arrived; counting down so chunks can load before the scan
                if (--sweepSettle == 0) { rescan(); sweepIdx++; }
                return;
            }
            Vec3d wp = sweep.get(sweepIdx);
            step = new GoTo(wp, () -> { this.sweepSettle = SWEEP_SETTLE_TICKS; this.step = null; });
            step.init();
            return;
        }
        // A sweep pass just finished. Chunk-loading on a DF server is flaky enough that ONE pass intermittently
        // misses a different handful of far lines each run (proven by diffing two pulls of the same plot). So
        // repeat the whole fly-through until a complete pass turns up NOTHING new - then we've seen everything
        // loadable - capped so flakiness can't loop forever. -nomove skips this (you sweep manually).
        if (!NO_MOVE && sweep != null) {
            int found = blocks.size();
            if (found > blocksAtPassStart && sweepPass < MAX_SWEEP_PASSES) {
                sweepPass++;
                Log.step("sweep pass " + sweepPass + " found " + (found - blocksAtPassStart)
                        + " new line(s) (" + found + " total); re-sweeping for stragglers");
                com.dfvibe.Chat.info("re-checking for missed lines (pass " + (sweepPass + 1) + ", " + found + " found)");
                blocksAtPassStart = found;
                sweepIdx = 0;                            // re-fly the whole length
                return;
            }
            Log.step("discovery converged after " + (sweepPass + 1) + " sweep(s): " + found + " line(s)");
            sweep = null;                                // converged (or hit the cap) - stop sweeping, start collecting
        }
        // Phase 2 - pick up every discovered line.
        next();
    }


    private class PickUpBlock extends Action {
        private final BlockPos pos;
        private Integer ticks = 0;
        private int retries = 0;
        private static final int MAX_RETRIES = 6; // ~3s of retries before skipping an un-readable line

        private boolean interacted = false;

        public PickUpBlock(BlockPos pos, Callback callback) {
            super(callback);
            this.pos = pos;
        }

        @Override
        public void init() {
            doInteract();
        }

        private void doInteract() {
            var net = CodeClient.MC.getNetworkHandler();
            Utility.makeHolding(ItemStack.EMPTY);
            var player = CodeClient.MC.player;
            var inter = CodeClient.MC.interactionManager;
            boolean sneaky = !player.isSneaking();
            if (sneaky) net.sendPacket(new PlayerInputC2SPacket(new PlayerInput(false, false, false, false, false, true, false)));
            inter.interactBlock(player, Hand.MAIN_HAND, new BlockHitResult(this.pos.toCenterPos(), Direction.UP, this.pos, false));
            if (sneaky) net.sendPacket(new PlayerInputC2SPacket(CodeClient.MC.player.getLastPlayerInput()));
            interacted = true;
            ticks = 0;
        }

        @Override
        public boolean onReceivePacket(Packet<?> packet) {
            var net = CodeClient.MC.getNetworkHandler();
            if (net != null && packet instanceof ScreenHandlerSlotUpdateS2CPacket slot) {
                var data = Utility.templateDataItem(slot.getStack());
                var template = Template.parse64(data);
                if (template == null) return false;
                if (!templateMatchesSign(template, pos)) return false; // stale/other-line packet - ignore, keep waiting
                recordLine(pos, slot.getStack());
                // Leave the picked template in hand (visual "it's working" feedback) - the next pickup's
                // makeHolding(EMPTY) clears it, and complete() empties the hand when the scan ends.
                this.callback();
                return true;
            }
            return super.onReceivePacket(packet);
        }

        @Override
        public void tick() {
            ticks += 1;
            if (ticks == 10) {
                ticks = 0;
                retries += 1;
                if (retries > MAX_RETRIES) {
                    // No template came back after several tries - DF refused it ("Unable to create code
                    // template! Exceeded the code data size limit"). Queue it for physical-block salvage
                    // (SalvageLine) instead of dropping it; if that also fails it lands in skipped.
                    Log.step("scan @ " + pos.toShortString() + " - no template (too big); queued for block salvage");
                    toSalvage.add(pos);
                    this.callback();
                    return;
                }
                doInteract();
            }
        }
    }
}
