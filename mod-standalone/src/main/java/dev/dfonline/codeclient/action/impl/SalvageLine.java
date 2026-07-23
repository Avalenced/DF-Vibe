package dev.dfonline.codeclient.action.impl;

import com.dfvibe.DfVibeClient;
import com.dfvibe.Log;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.dfonline.codeclient.Callback;
import dev.dfonline.codeclient.CodeClient;
import dev.dfonline.codeclient.Utility;
import dev.dfonline.codeclient.action.Action;
import dev.dfonline.codeclient.data.DFItem;
import dev.dfonline.codeclient.location.Plot;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.SignBlockEntity;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.AttributeModifierSlot;
import net.minecraft.component.type.AttributeModifiersComponent;
import net.minecraft.component.type.ContainerComponent;
import net.minecraft.entity.attribute.EntityAttributeModifier;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.c2s.play.CreativeInventoryActionC2SPacket;
import net.minecraft.network.packet.c2s.play.PickItemFromBlockC2SPacket;
import net.minecraft.network.packet.s2c.play.ScreenHandlerSlotUpdateS2CPacket;
import net.minecraft.registry.Registries;
import net.minecraft.state.property.Properties;
import net.minecraft.util.Identifier;
import net.minecraft.util.collection.DefaultedList;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Reconstruct ONE code line directly from its physical blocks, for lines DiamondFire refuses to hand
 * over as a template ("Unable to create code template! Exceeded the code data size limit"). The normal
 * scan picks up each line-starter and DF generates the template item; when a line is too long DF errors
 * and {@link ScanPlot} would otherwise just skip it (the line silently vanishes from a /df pull). This
 * rebuilds it instead.
 *
 * <p>Codespace geometry (verified against a live plot): a line is a fixed 2-wide column running south
 * (+Z) from its starter sign. For a code block at material column {@code mx}, floor {@code y}, depth
 * {@code z}: the <b>material</b> is at {@code (mx,y,z)}, its <b>wall sign</b> at {@code (mx-1,y,z)}
 * (line 0 = block type, line 1 = action/name), its <b>chest</b> (the args) at {@code (mx,y+1,z)}, and
 * <b>brackets</b> are pistons at {@code (mx,y,z)} (facing +Z = open, -Z = close; sticky = repeat).
 * Filler {@code stone}/{@code air} cells are ignored, so a malformed line missing its stone separators
 * still reconstructs (we order by what's actually there, not by assumed spacing).
 *
 * <p>Args come from picking each chest with {@link PickItemFromBlockC2SPacket} (server-authoritative, so
 * contents return with full NBT even though the client never opened it). Each item's
 * {@code hypercube:varitem} tag is the EXACT {@code {"id","data"}} DiamondFire stored for that arg, so we
 * copy it VERBATIM - lossless (this is what keeps {@code loc(..., isBlock=True)}, var scopes, etc.).
 * A plain item with no varitem tag is a raw Minecraft item: emit {@code {"id":"item","data":{"item":SNBT}}}.
 *
 * <p>With {@code salvageReach} on (default), an offhand +reach item is equipped so chests pick from far
 * away - the player only has to fly when the next chest is out of (extended) reach, instead of landing on
 * every one. The reconstructed line is a standard base64+gzip template the codec decompiles like any other.
 */
public class SalvageLine extends Action {
    /** DiamondFire's code-block material -> template block category. The authoritative, text-free key. */
    private static final Map<String, String> MATERIAL = Map.ofEntries(
            Map.entry("diamond_block", "event"),
            Map.entry("gold_block", "entity_event"),
            Map.entry("netherite_block", "game_event"),
            Map.entry("cobblestone", "player_action"),
            Map.entry("mossy_cobblestone", "entity_action"),
            Map.entry("netherrack", "game_action"),
            Map.entry("iron_block", "set_var"),
            Map.entry("purpur_block", "select_obj"),
            Map.entry("coal_block", "control"),
            Map.entry("lapis_block", "func"),
            Map.entry("emerald_block", "process"),
            Map.entry("lapis_ore", "call_func"),
            Map.entry("emerald_ore", "start_process"),
            Map.entry("oak_planks", "if_player"),
            Map.entry("bricks", "if_entity"),
            Map.entry("red_nether_bricks", "if_game"),
            Map.entry("obsidian", "if_var"),
            Map.entry("prismarine", "repeat"),
            Map.entry("end_stone", "else"));

    /** Blocks whose sign name is the function/process NAME ("data"), not an "action". */
    private static final java.util.Set<String> DATA_BLOCKS = java.util.Set.of("func", "process", "call_func", "start_process");

    /** SELECT OBJECT conditional selectors: for these (and REPEAT While) sign line 2 is the embedded
     *  condition, which DF stores as the top-level "subAction" - NOT a selection "target". */
    private static final java.util.Set<String> COND_SELECTORS = java.util.Set.of("PlayersCond", "EntitiesCond", "MobsCond", "FilterCondition");

    /** Categories that ONLY ever begin a line - hitting one mid-walk means the next (stacked) line started. */
    private static final java.util.Set<String> STARTER_CATS = java.util.Set.of("event", "entity_event", "game_event", "func", "process");

    private static final int OFFHAND_SLOT = 45;          // player-inventory slot index of the offhand
    private static final double REACH_BONUS = 60.0;      // +reach the offhand item grants (base ~4.5 -> ~64)
    private static final int MAX_PICK_RETRIES = 12;      // ~6s of retries per chest before giving up (block-entity sync can be slow to respond)

    private final BlockPos starter; // the line-starter SIGN position (what ScanPlot picked up)
    private int mx, floorY, startZ;
    private boolean reach;          // offhand-reach speedup enabled for this run
    private boolean noMove;         // -nomove: never fly; give up a line whose chests are out of reach
    private boolean scanPlotOwnsReach; // ScanPlot equipped the offhand reach for the whole scan (-nomove)
    private double pickRangeSq;     // how close (squared) the player must be to pick a chest

    /** One reconstructed element: either a code block (may carry a chest) or a bracket. */
    private static final class Element {
        boolean bracket;
        // block:
        String category, name;
        String target;         // sign line 2 (selection: Selection/Victim/Killer/...), null if none
        String attribute;      // sign line 3 (NOT for inverted conditions, LS-CANCEL, ...), null if none
        boolean isData;
        BlockPos signPos;      // the block's wall-sign position (for a fresh re-read at finish, see below)
        BlockPos chestPos;     // null = no chest (no args)
        JsonArray args;        // filled once the chest is read (or set empty if none)
        // bracket:
        boolean open, repeat;
        int pickFails;         // give-up visits for this chest (human -nomove defers once before zeroing)
    }

    private final List<Element> elements = new ArrayList<>();
    private int idx = 0;                 // element currently being chest-picked
    private int reWalks = 0;             // re-walks done after loading chunks by flying (capped)
    private static final int MAX_REWALKS = 5;
    private GoTo goTo = null;
    private boolean picking = false;     // a pick packet was sent; awaiting the slot update
    private boolean mustFly = false;     // a ranged pick kept failing - fly onto this chest and pick point-blank
    private int ticks = 0, retries = 0;
    private boolean walked = false;      // the column has been read into elements (deferred until the chunk loads)
    private int walkTries = 0;           // ticks spent waiting for the starter's chunk before giving up
    private int signSettle = 0;          // ticks waited (at finish) for blank-action signs' block-entities to load
    private static final int SIGN_SETTLE_MAX = 60; // ~3s: a sign block-entity always arrives shortly after its chunk
    private int blankFlights = 0;        // fly-back legs used to reload blank signs' chunks before trusting the blank
    private static final int MAX_BLANK_FLIGHTS = 4;

    /** The reconstructed base64+gzip template code, or null if reconstruction failed. Read by the caller. */
    public String resultCode = null;
    /**
     * -nomove resumability. {@code done} = this line is fully resolved (resultCode set, or permanently
     * failed) and the caller should record it. {@code done == false} after a callback means DEFERRED:
     * every chest still in reach was read, but more remain out of (no-fly) reach - keep this instance and
     * tick it again once the player has flown closer; already-read args are preserved, so it resumes where
     * it left off instead of dropping the whole line (the old behaviour, which silently lost lines whose
     * far end was briefly out of reach as you flew in). Flying salvage always runs straight through to
     * {@code done == true}.
     */
    public boolean done = false;
    private Element pendingElement = null; // the chest currently being picked (so out-of-order picks land right)

    public SalvageLine(BlockPos starterSign, Callback callback) {
        super(callback);
        this.starter = starterSign;
    }

    /** Salvage flies the player chest-to-chest like the scan/placer, with the NoClip hop mover. */
    @Override
    public boolean wantsNoClip() {
        return true;
    }

    @Override
    public void init() {
        mx = starter.getX() + 1;     // material/chest column is one EAST of the sign
        floorY = starter.getY();
        startZ = starter.getZ();
        // -nomove is "stationary salvage": read every chest in reach from where we stand and DEFER the rest,
        // never flying down the column to finish one line (the player drives the flight manually). Normal
        // salvage walks the line.
        noMove = ScanPlot.NO_MOVE;
        // ScanPlot equips ONE offhand reach item for the whole -nomove scan, so a salvage reads chests up to
        // ~54 blocks from where it stands. Only equip/clear the offhand ourselves when ScanPlot didn't
        // (a plain pull with salvageReach on).
        scanPlotOwnsReach = noMove;
        reach = scanPlotOwnsReach || (DfVibeClient.config != null && DfVibeClient.config.salvageReach);
        double range = reach ? (REACH_BONUS - 6) : 2.0; // stay safely inside the server-enforced limit
        pickRangeSq = range * range;
        if (reach && !scanPlotOwnsReach) equipReachItem();
        // The column walk is DEFERRED to tick() (ensureWalked): we must not read the line's blocks until its
        // chunk is actually loaded. After the discovery sweep the player is elsewhere and this line's chunk has
        // usually unloaded -> every block reads as air and the line would be silently dropped/truncated. tick()
        // flies onto the starter first (loading the chunk) and settles, then walks.
    }

    /**
     * Read the line's physical column into {@code elements}. Returns false (no code found) if the column is
     * empty - usually because the chunk still isn't loaded, so the caller retries/flies closer first.
     *
     * <p>Lines can be SOUTH-STACKED in one column (the placer packs them), so the walk must stop at this
     * line's end, not run into the next. Two independent stops: (1) hitting another line-STARTER block
     * (event/func/process - those only ever begin a line), and (2) a 2-cell AIR gap (the spacing the placer
     * leaves between stacked lines). Within a line, cells are blocks/pistons/stone connectors with at most
     * ONE air cell (the lead-in to a close bracket), so neither stop fires mid-line.
     */
    private boolean walkColumn() {
        var world = CodeClient.MC.world;
        if (world == null) return false;
        // How far south the column can possibly run. An off-plot region scan has NO Plot location, so it used
        // to fall back to a flat startZ+300 - and any line longer than that had its tail silently truncated
        // (its far blocks never became elements, so their chests were never read and never even reported
        // missing). The scan's region box IS the codespace when one is set, so use its south edge. Clamped so
        // an oversized region can't turn one walk into a 100k-block loop; the walk stops at the next line's
        // starter or a run of air long before this bound anyway.
        int endZ = startZ + 300;     // default cap; tightened/widened below when we know the codespace
        int[] region = ScanPlot.REGION;
        if (region != null) endZ = Math.max(region[2], region[5]);
        else if (CodeClient.location instanceof Plot plot && plot.getZ() != null)
            endZ = plot.getZ() + plot.assumeSize().codeLength;
        endZ = Math.max(startZ, Math.min(endZ, startZ + 2000));
        elements.clear();
        int consecutiveAir = 0;
        for (int z = startZ; z <= endZ; z++) {
            BlockPos pos = new BlockPos(mx, floorY, z);
            String id = Registries.BLOCK.getId(world.getBlockState(pos).getBlock()).getPath();
            if (id.equals("air")) {
                // Tolerate air gaps WITHIN a line (close brackets leave a 1-cell gap, and a codespace can have
                // a few blank cells between blocks and still compile). The reliable line boundary is the next
                // STARTER block (handled below); this large air run is only a fallback for the very last line /
                // a genuinely huge gap. (Kept under the ~8 that used to merge stacked lines.)
                if (!elements.isEmpty() && ++consecutiveAir >= 6) break;
                continue;
            }
            consecutiveAir = 0; // any non-air cell (stone connector / block / piston) keeps us in the line
            if (id.equals("stone")) continue;
            if (id.equals("piston") || id.equals("sticky_piston")) {
                Element e = new Element();
                e.bracket = true;
                e.repeat = id.equals("sticky_piston");
                Direction f = world.getBlockState(pos).get(Properties.FACING);
                e.open = f.getOffsetZ() > 0; // faces +Z (south, line direction) = opening bracket
                elements.add(e);
            } else if (MATERIAL.containsKey(id)) {
                String cat = MATERIAL.get(id);
                if (z > startZ && STARTER_CATS.contains(cat)) break; // the next stacked line's starter
                Element e = new Element();
                e.category = cat;
                e.signPos = new BlockPos(mx - 1, floorY, z);
                String[] sign = signLines(e.signPos);
                e.name = sign[1];
                if (!sign[2].isBlank()) e.target = sign[2].trim();      // line 2 = selection target
                if (!sign[3].isBlank()) e.attribute = sign[3].trim();   // line 3 = NOT / LS-CANCEL / ...
                e.isData = DATA_BLOCKS.contains(e.category);
                BlockPos chest = new BlockPos(mx, floorY + 1, z);
                if (Registries.BLOCK.getId(world.getBlockState(chest).getBlock()).getPath().equals("chest"))
                    e.chestPos = chest;
                else
                    e.args = new JsonArray(); // no chest -> no args
                elements.add(e);
            }
            // anything else (unexpected block) -> ignore
        }
        if (elements.isEmpty()) return false;
        Log.step("salvage @ " + starter.toShortString() + " - reconstructing " + elements.size()
                + " element(s) from blocks" + (reach ? " (offhand reach)" : ""));
        return true;
    }

    /** The block id (path) at a position, or "air" if the chunk isn't loaded there. */
    private String idAt(int x, int y, int z) {
        return Registries.BLOCK.getId(CodeClient.MC.world.getBlockState(new BlockPos(x, y, z)).getBlock()).getPath();
    }

    /**
     * Make sure the line's chunk is loaded and walk its column. Returns true once {@code elements} is built;
     * false while still flying to / waiting for the chunk (or after giving up). Flight mode flies onto the
     * starter to force its chunk to load; -nomove never flies, so it just waits briefly (the line it's
     * salvaging is in reach, so its chunk is already loaded) and gives up if it somehow isn't.
     */
    private boolean ensureWalked() {
        var world = CodeClient.MC.world;
        if (world == null) { finishFail(); return false; }
        boolean loaded = MATERIAL.containsKey(idAt(mx, floorY, startZ)); // starter material present = chunk ready
        if (!loaded) {
            if (!noMove) {
                Vec3d feet = CodeClient.MC.player.getEntityPos();
                Vec3d at = new Vec3d(mx + 0.5, floorY + 1.5, startZ + 0.5);
                if (feet.squaredDistanceTo(at) > 9.0) { goTo = new GoTo(at, () -> this.goTo = null); goTo.init(); return false; }
            }
            if (++walkTries > 60) { // ~3s near the starter and still air -> give up rather than loop forever
                Log.step("salvage @ " + starter.toShortString() + " - starter chunk never loaded; skipping line");
                finishFail();
                return false;
            }
            return false; // settle: let the chunk's blocks/signs arrive, retry next tick
        }
        if (!walkColumn()) {
            if (++walkTries > 60) {
                Log.step("salvage @ " + starter.toShortString() + " - no code blocks found in the column; giving up");
                finishFail();
                return false;
            }
            return false; // loaded a moment ago but blocks not all there yet - retry
        }
        walked = true;
        return true;
    }

    /** All four front-sign lines as plain strings: [0]=category, [1]=action/name, [2]=target, [3]=attribute. */
    private String[] signLines(BlockPos signPos) {
        String[] out = {"", "", "", ""};
        BlockEntity be = CodeClient.MC.world.getBlockEntity(signPos);
        if (be instanceof SignBlockEntity sign) {
            for (int i = 0; i < 4; i++) out[i] = sign.getFrontText().getMessage(i, false).getString();
        }
        return out;
    }

    @Override
    public void tick() {
        if (done || resultCode != null) return; // already finished
        if (goTo != null) { goTo.tick(); return; }
        if (!walked && !ensureWalked()) return; // fly onto the line + load its chunk before reading any blocks

        if (picking) { awaitPick(); return; }

        if (noMove) {
            // -nomove: never fly. Read every chest currently in reach (nearest first), then PAUSE - the
            // remaining chests come into reach as the player keeps flying, and this instance resumes from
            // here with the read args intact. The whole line is only dropped if it genuinely can't be read.
            Element e = nearestUnreadChestInReach();
            if (e != null) {
                sendPick(e.chestPos); pendingElement = e; picking = true; ticks = 0; return;
            }
            if (allChestsRead()) { if (maybeReWalk()) return; tryFinish(); return; }
            defer(); // chests remain but none in reach right now - resume after the player moves closer
            return;
        }

        // Flying salvage: take chests in column order, flying to any that's out of (extended) reach.
        Element e = nextUnread();
        if (e == null) { if (maybeReWalk()) return; tryFinish(); return; }
        Vec3d feet = CodeClient.MC.player.getEntityPos();
        Vec3d chest = e.chestPos.toCenterPos();
        // Fly TOWARD the chest when it's out of (extended) reach. When ranged picks kept failing
        // (reach not honored) mustFly lands point-blank, which guarantees we're in range.
        if (mustFly || feet.squaredDistanceTo(chest) > pickRangeSq) {
            goTo = new GoTo(chest.add(0, 1.5, 0), () -> this.goTo = null);
            goTo.init();
            mustFly = false;
            return;
        }
        sendPick(e.chestPos);
        pendingElement = e;
        picking = true;
        ticks = 0;
    }

    /** Awaiting the pick response (filled in onReceivePacket); retry, then (flying only) fly closer, then skip. */
    private void awaitPick() {
        if (++ticks < 10) return;
        ticks = 0;
        retries++;
        Element e = pendingElement;
        if (retries > MAX_PICK_RETRIES) {
            if (e != null) {
                e.pickFails++;
                int dist = CodeClient.MC.player != null ? (int) CodeClient.MC.player.getEntityPos().distanceTo(e.chestPos.toCenterPos()) : -1;
                // Human -nomove can't fly onto the chest to fix a failing ranged pick - but writing EMPTY
                // args here is exactly the "no data in the chests" corruption. DEFER instead (args stay
                // null): the line stays pending, the player is guided back, and the pick retries from a
                // fresh position. Only a chest that fails a SECOND visit gets zeroed (with the warning),
                // so a genuinely unreadable chest can't livelock the scan.
                if (noMove && e.pickFails < 2) {
                    com.dfvibe.Chat.warn("salvage: chest @ " + e.chestPos.toShortString() + " didn't respond ("
                            + dist + "b away) - will retry when you pass it again (line stays pending).");
                    picking = false;
                    retries = 0;
                    mustFly = false;
                    pendingElement = null;
                    defer();
                    return;
                }
                com.dfvibe.Chat.warn("salvage: chest @ " + e.chestPos.toShortString() + " never responded (" + dist + "b away, reach=" + reach + ") - empty args");
                e.args = new JsonArray(); // give up on this chest; keep the line
            }
            picking = false;
            retries = 0;
            mustFly = false;
            pendingElement = null;
        } else if (!noMove && retries == 3) {
            // Ranged pick isn't landing. Flying salvage escalates by flying point-blank onto the chest
            // and retrying there.
            picking = false;
            mustFly = true;
        } else if (e != null) {
            sendPick(e.chestPos);     // resend at range
        }
    }

    /** True once every code block that has a chest has had its args read (brackets / no-chest blocks
     *  already carry args, so they don't count as unread). */
    private boolean allChestsRead() {
        for (Element e : elements) if (!e.bracket && e.args == null) return false;
        return true;
    }

    /**
     * Before finishing, RE-WALK the column. Flying chest-to-chest to read this line's args also flew THROUGH
     * its length, loading chunks that were unloaded during the first walk - so a re-walk now sees blocks that
     * first read as air (the missing-middle-blocks truncation). If it turns up more, we keep the args we
     * already read (matched by chest position), reset, and pick the rest; on the next pass it converges. If
     * nothing new appears, we restore the read line and finish. Capped so it can't loop forever.
     */
    private boolean maybeReWalk() {
        if (reWalks >= MAX_REWALKS) return false;
        // A re-walk restarts at the starter. If the starter's chunk isn't loaded (we finished this line's
        // chests from far down its column - normal for a long line) every cell up there reads AIR, and the
        // walk would silently rebuild the line without its head. Only re-walk from a position where the
        // column's beginning is actually readable; otherwise keep the elements we already have.
        if (!MATERIAL.containsKey(idAt(mx, floorY, startZ))) return false;
        List<Element> old = new ArrayList<>(elements);
        if (!walkColumn() || elements.size() <= old.size()) {  // no growth -> keep the line we already read
            elements.clear();
            elements.addAll(old);
            return false;
        }
        reWalks++;
        java.util.HashMap<Long, JsonArray> readArgs = new java.util.HashMap<>();
        for (Element e : old) if (e.chestPos != null && e.args != null) readArgs.put(e.chestPos.asLong(), e.args);
        for (Element e : elements)
            if (e.chestPos != null) {
                JsonArray a = readArgs.get(e.chestPos.asLong());
                if (a != null) e.args = a;
            }
        idx = 0;
        signSettle = 0;
        Log.step("salvage @ " + starter.toShortString() + " - re-walk after loading chunks found "
                + (elements.size() - old.size()) + " more block(s); reading them");
        return true;
    }

    /**
     * Finish, but FIRST let any blank-action block's wall sign actually load. A code block whose action is
     * genuinely unset reads blank - and so does one whose sign block-entity simply hasn't been delivered yet
     * (block-states load a tick or more before their block-entities). A line with no chests reaches finish()
     * the instant its column walks - before those signs arrive - so we'd capture a blank action where a real
     * one exists (this is why the all-blank "stress-test" line read wrong). We wait (keeping this op active, no
     * flight) until every blank-named block's sign is loaded - then a still-blank action is provably REAL - or
     * until a short timeout, after which finish() keeps the line and emits any still-blank block as a blank
     * action (a later pull re-grabs the real action if the sign was merely slow).
     */
    private void tryFinish() {
        if (!blanksConfirmed()) {
            if (++signSettle <= SIGN_SETTLE_MAX) return;
            // Waiting in place didn't load the blank signs - the player is too far (fell / got moved away
            // mid-salvage), so their chunk will NEVER stream in from here. FLY BACK onto the nearest
            // unconfirmed blank sign to force its chunk to load, then settle and re-check. This is what
            // used to write "signs with no action" into pulled files: the old code trusted a blank read
            // from an unloaded chunk after 3s of hoping. Bounded so a genuinely unreachable sign can't
            // loop the salvage forever - after the flights are spent, finish() still warns loudly.
            if (!noMove && blankFlights < MAX_BLANK_FLIGHTS) {
                BlockPos p = nearestUnconfirmedBlankSign();
                if (p != null) {
                    blankFlights++;
                    signSettle = 0;
                    Log.step("salvage @ " + starter.toShortString() + " - blank sign(s) unconfirmed; flying back to "
                            + p.toShortString() + " to re-read (" + blankFlights + "/" + MAX_BLANK_FLIGHTS + ")");
                    goTo = new GoTo(p.toCenterPos().add(0, 1.5, 0), () -> this.goTo = null);
                    goTo.init();
                    return;
                }
            }
        }
        finish();
    }

    /** Every blank-named block has its sign block-entity loaded (so its blank action is trustworthy, not just
     *  un-delivered). Blocks that already read a name are trusted as-is and never block finishing. */
    private boolean blanksConfirmed() {
        for (Element e : elements) if (signUnconfirmed(e)) return false;
        return true;
    }

    /** A blank-named block whose sign we cannot yet TRUST to be blank: its chunk is unloaded (every block
     *  there reads air - the blank is meaningless) or the sign block is present but its block-entity (the
     *  text) hasn't been delivered. A blank read through an unloaded chunk is exactly how corrupt
     *  "no-action" blocks used to get written into pulled files. */
    private boolean signUnconfirmed(Element e) {
        if (e.bracket || "else".equals(e.category) || e.signPos == null) return false;
        if (e.name != null && !e.name.isBlank()) return false;             // already named -> trusted
        var world = CodeClient.MC.world;
        if (world == null) return true;
        // Unloaded chunk: block states read AIR there, so "no sign block" proves nothing - unconfirmed.
        if (!world.getChunkManager().isChunkLoaded(e.signPos.getX() >> 4, e.signPos.getZ() >> 4)) return true;
        // The block STATE loads with the chunk, before its block-entity. If there isn't even a sign
        // BLOCK here (in a LOADED chunk), this block genuinely has no sign - its action is a real blank.
        if (!idAt(e.signPos.getX(), e.signPos.getY(), e.signPos.getZ()).contains("sign")) return false;
        return !(world.getBlockEntity(e.signPos) instanceof SignBlockEntity); // sign present, entity still loading
    }

    /** The nearest blank-named block whose sign is still unconfirmed (see {@link #signUnconfirmed}), or null. */
    private BlockPos nearestUnconfirmedBlankSign() {
        var player = CodeClient.MC.player;
        Vec3d feet = player != null ? player.getEntityPos() : null;
        BlockPos best = null;
        double bestSq = Double.MAX_VALUE;
        for (Element e : elements) {
            if (!signUnconfirmed(e)) continue;
            double d = feet == null ? 0 : feet.squaredDistanceTo(e.signPos.toCenterPos());
            if (d < bestSq) { bestSq = d; best = e.signPos; }
        }
        return best;
    }

    /** The unread-chest element nearest the player that is within (no-fly) reach right now, or null. */
    private Element nearestUnreadChestInReach() {
        Vec3d feet = CodeClient.MC.player.getEntityPos();
        Element best = null;
        double bestSq = Double.MAX_VALUE;
        for (Element e : elements) {
            if (e.bracket || e.args != null || e.chestPos == null) continue;
            double d = feet.squaredDistanceTo(e.chestPos.toCenterPos());
            if (d <= pickRangeSq && d < bestSq) { bestSq = d; best = e; }
        }
        return best;
    }

    /** -nomove: has this (walked) line at least one unread chest within reach of {@code feet} right now?
     *  Lets the caller skip resuming a line it can make no progress on from the current spot. */
    public boolean hasUnreadChestInReach(Vec3d feet) {
        for (Element e : elements) {
            if (e.bracket || e.args != null || e.chestPos == null) continue;
            if (feet.squaredDistanceTo(e.chestPos.toCenterPos()) <= pickRangeSq) return true;
        }
        return false;
    }

    /** Has the line's column been read into {@code elements} yet (chunk loaded + walked)? */
    public boolean isWalked() { return walked; }

    /** The position of this line's nearest still-unread chest to {@code from} (any distance), or null if every
     *  chest has been read. Auto-sweep flies toward this to bring a line's far (down-column) chests into reach,
     *  instead of sitting on the starter where the far chests never load. */
    public BlockPos nearestUnreadChest(Vec3d from) {
        BlockPos best = null;
        double bestSq = Double.MAX_VALUE;
        for (Element e : elements) {
            if (e.bracket || e.args != null || e.chestPos == null) continue;
            double d = from.squaredDistanceTo(e.chestPos.toCenterPos());
            if (d < bestSq) { bestSq = d; best = e.chestPos; }
        }
        return best;
    }

    /** Every still-unread chest in this line (for the /df showmissingchest highlighter). */
    public java.util.List<BlockPos> unreadChests() {
        java.util.ArrayList<BlockPos> out = new java.util.ArrayList<>();
        for (Element e : elements) if (!e.bracket && e.args == null && e.chestPos != null) out.add(e.chestPos);
        return out;
    }

    /** True while this salvage is actively flying its own GoTo to a chest. */
    public boolean isFlying() { return goTo != null; }

    /** -nomove pause: read everything reachable from here; keep this instance to resume once the player
     *  has flown closer to the chests still out of reach. */
    private void defer() {
        done = false;
        try { Utility.makeHolding(ItemStack.EMPTY); } catch (Throwable ignored) {}
        callback();
    }

    private Element nextUnread() {
        while (idx < elements.size()) {
            Element e = elements.get(idx);
            if (e.bracket || e.args != null) { idx++; continue; }
            return e;
        }
        return null;
    }

    private void sendPick(BlockPos chest) {
        var net = CodeClient.MC.getNetworkHandler();
        if (net == null) return;
        Utility.makeHolding(ItemStack.EMPTY);         // pick lands in the held slot; keep it clean
        net.sendPacket(new PickItemFromBlockC2SPacket(chest, true)); // includeData=true -> full contents NBT
    }

    @Override
    public boolean onReceivePacket(Packet<?> packet) {
        if (goTo != null) return goTo.onReceivePacket(packet);
        if (!picking) return false;
        if (!(packet instanceof ScreenHandlerSlotUpdateS2CPacket slot)) return false;
        ItemStack stack = slot.getStack();
        if (stack == null || stack.getItem() != Items.CHEST) return false; // not our pick response

        // Assign to the chest we actually picked (pendingElement) - the player may have read chests out of
        // column order in -nomove, so nextUnread() is no longer necessarily the one this response is for.
        Element e = pendingElement != null ? pendingElement : nextUnread();
        if (e != null) {
            JsonArray args = new JsonArray();
            ContainerComponent container = DFItem.of(stack).getContainer();
            if (container != null) {
                DefaultedList<ItemStack> slots = DefaultedList.ofSize(27, ItemStack.EMPTY);
                container.copyTo(slots); // preserves slot indices (an arg's chest slot IS its template slot)
                for (int i = 0; i < slots.size(); i++) {
                    ItemStack arg = slots.get(i);
                    if (!arg.isEmpty()) args.add(argFor(arg, i));
                }
            }
            e.args = args;
        }
        // Show the picked chest in hand (so you can SEE salvage is working) instead of clearing it; the next
        // pick's makeHolding(EMPTY) clears it, and finish() empties the hand at the end.
        Utility.makeHolding(stack);
        picking = false;
        retries = 0;
        mustFly = false;
        pendingElement = null;
        return true;
    }

    /**
     * One chest item -> one template arg {@code {"item":{"id","data"},"slot":N}}. Reads the item's
     * {@code hypercube:varitem} tag VERBATIM (DiamondFire's exact arg JSON - lossless for every typed arg:
     * num/var/txt/loc/snd/pot/g_val/bl_tag/hint/...); a plain item with no such tag is a raw Minecraft item.
     */
    private JsonObject argFor(ItemStack stack, int slot) {
        JsonObject arg = new JsonObject();
        JsonObject itemObj = null;
        try {
            Optional<String> varitem = DFItem.of(stack).getHypercubeStringValue("varitem");
            if (varitem.isPresent()) itemObj = JsonParser.parseString(varitem.get()).getAsJsonObject();
        } catch (Exception ignored) {
        }
        if (itemObj == null) {
            // Raw Minecraft item: {"id":"item","data":{"item":"<SNBT>"}}
            itemObj = new JsonObject();
            itemObj.addProperty("id", "item");
            JsonObject data = new JsonObject();
            data.addProperty("item", rawItemSnbt(stack));
            itemObj.add("data", data);
        }
        arg.add("item", itemObj);
        arg.addProperty("slot", slot);
        return arg;
    }

    /** Serialize a raw item to DiamondFire's SNBT form: {@code {DF_NBT:<ver>,components:{...},count:N,id:"..."}}. */
    private String rawItemSnbt(ItemStack stack) {
        try {
            NbtElement enc = ItemStack.CODEC.encodeStart(
                    CodeClient.MC.player.getRegistryManager().getOps(NbtOps.INSTANCE), stack).getOrThrow();
            // NOTE: DF adds a DF_NBT (data-version) field when IT serializes; we omit it (the API doesn't
            // expose the accessor cleanly here). Harmless - the codec stores the SNBT verbatim and DF
            // re-adds DF_NBT when the line is placed, so a re-deploy is still correct.
            return enc.toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    /** All chests read - assemble the block list, encode it, and hand the code string back. */
    private void finish() {
        try {
            // A block whose action/name reads BLANK is one of two things: (a) a code block whose action is
            // GENUINELY unset - a real, valid DF block a normal pull decompiles as PlayerAction(''); or (b) a
            // sign whose block-entity hadn't arrived when we walked (block-states load a tick before their
            // block-entities, and chunk edges can lag). We RE-READ the sign now (the column has been walked +
            // chest-picked, so its chunk is loaded) to recover any action that simply loaded late. If it's
            // STILL blank we keep the line and emit THIS block as a blank action of its type, rather than
            // dropping the whole line - the old behaviour silently lost any line containing such a block from a
            // /df pull. (Emitting a blank action is safe: it's a valid no-op block and can't orphan a bracket;
            // if the sign was merely slow, a later pull re-grabs the real action since pull overwrites.)
            int unloadedBlanks = 0;
            for (Element e : elements) {
                if (e.bracket || "else".equals(e.category)) continue;
                if (e.name != null && !e.name.isBlank()) continue;
                String[] sign = e.signPos != null ? signLines(e.signPos) : new String[]{"", "", "", ""};
                e.name = sign[1]; // refresh from the now-loaded sign
                if (e.target == null && !sign[2].isBlank()) e.target = sign[2].trim();
                if (e.attribute == null && !sign[3].isBlank()) e.attribute = sign[3].trim();
                if (e.name == null || e.name.isBlank()) {
                    boolean signLoaded = e.signPos != null && CodeClient.MC.world != null
                            && CodeClient.MC.world.getBlockEntity(e.signPos) instanceof SignBlockEntity;
                    e.name = ""; // a blank action of this block type, never a JSON-null
                    if (!signLoaded) unloadedBlanks++;
                    Log.step("salvage @ " + starter.toShortString() + " - blank action on a " + e.category
                            + " block (" + (signLoaded ? "action genuinely unset" : "no sign loaded; using blank")
                            + "); keeping the line");
                }
            }
            // Blanks whose sign NEVER loaded are almost certainly real actions/names we failed to read (the
            // player fell/tp'd away mid-read) - deployed, they are silent no-ops that break the plot. Loudly
            // flag the line in chat so a corrupt salvage can't masquerade as a clean pull.
            if (unloadedBlanks > 0)
                com.dfvibe.Chat.warn("Salvaged line @ " + starter.toShortString() + ": " + unloadedBlanks
                        + " block(s) came back BLANK (their sign never loaded) - this line is probably corrupted."
                        + " Re-pull while staying near it, or /df pull -chests, and check the file before deploying.");
            JsonArray blocks = new JsonArray();
            for (Element e : elements) {
                JsonObject o = new JsonObject();
                if (e.bracket) {
                    o.addProperty("id", "bracket");
                    o.addProperty("direct", e.open ? "open" : "close");
                    o.addProperty("type", e.repeat ? "repeat" : "norm");
                } else if ("else".equals(e.category)) {
                    // ELSE is a bare block: no action, no data, NO args field (DF + the codec require this).
                    o.addProperty("id", "block");
                    o.addProperty("block", "else");
                } else {
                    o.addProperty("id", "block");
                    o.addProperty("block", e.category);
                    if (e.isData) o.addProperty("data", e.name);
                    else o.addProperty("action", e.name);
                    if (e.target != null) {
                        // SELECT OBJECT cond-selectors and REPEAT While carry their CONDITION on sign line 2,
                        // which DF stores as top-level "subAction", not a selection "target". Emitting it as
                        // "target" (the old behaviour) corrupted every such line vs a normal pull.
                        if (("select_obj".equals(e.category) && COND_SELECTORS.contains(e.name))
                                || ("repeat".equals(e.category) && "While".equals(e.name)))
                            o.addProperty("subAction", e.target);
                        else
                            o.addProperty("target", e.target);
                    }
                    if (e.attribute != null) o.addProperty("attribute", e.attribute);
                    JsonObject args = new JsonObject();
                    args.add("items", e.args != null ? e.args : new JsonArray());
                    o.add("args", args);
                }
                blocks.add(o);
            }
            resultCode = dev.dfonline.codeclient.hypercube.template.Template.encode(blocks);
            Log.step("salvage @ " + starter.toShortString() + " - rebuilt line ("
                    + elements.size() + " elements, " + resultCode.length() + " b64 chars)");
        } catch (Exception ex) {
            Log.step("salvage @ " + starter.toShortString() + " - encode failed: " + ex.getMessage());
            resultCode = null;
        }
        done = true;
        cleanup();
        callback();
    }

    private void finishFail() {
        resultCode = null;
        done = true;
        cleanup();
        callback();
    }

    /** Remove the offhand reach item and empty the hand so the player's inventory is left as we found it. */
    private void cleanup() {
        var net = CodeClient.MC.getNetworkHandler();
        if (net == null) return;
        try { Utility.makeHolding(ItemStack.EMPTY); } catch (Throwable ignored) {}
        // Only clear the offhand if WE equipped it; when ScanPlot owns it (-nomove or a fly mode) it clears the
        // reach item when the whole scan ends, so leave it equipped for the remaining lines.
        if (reach && !scanPlotOwnsReach) net.sendPacket(new CreativeInventoryActionC2SPacket(OFFHAND_SLOT, ItemStack.EMPTY));
    }

    /** Put a +block-interaction-range item in the offhand so chests can be picked from far away. */
    private void equipReachItem() {
        var net = CodeClient.MC.getNetworkHandler();
        if (net == null) return;
        ItemStack item = new ItemStack(Items.STICK);
        AttributeModifiersComponent mods = AttributeModifiersComponent.builder()
                .add(EntityAttributes.BLOCK_INTERACTION_RANGE,
                        new EntityAttributeModifier(Identifier.of("dfvibe", "salvage_reach"),
                                REACH_BONUS, EntityAttributeModifier.Operation.ADD_VALUE),
                        AttributeModifierSlot.OFFHAND)
                .build();
        item.set(DataComponentTypes.ATTRIBUTE_MODIFIERS, mods);
        net.sendPacket(new CreativeInventoryActionC2SPacket(OFFHAND_SLOT, item));
    }
}
