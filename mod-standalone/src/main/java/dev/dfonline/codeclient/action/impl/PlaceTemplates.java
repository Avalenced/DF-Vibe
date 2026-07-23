package dev.dfonline.codeclient.action.impl;

import com.dfvibe.Log;
import com.google.gson.JsonParser;
import dev.dfonline.codeclient.Callback;
import dev.dfonline.codeclient.ChatType;
import dev.dfonline.codeclient.CodeClient;
import dev.dfonline.codeclient.Utility;
import dev.dfonline.codeclient.action.Action;
import dev.dfonline.codeclient.data.DFItem;
import dev.dfonline.codeclient.hypercube.template.Template;
import dev.dfonline.codeclient.hypercube.template.TemplateBlock;
import dev.dfonline.codeclient.location.Dev;
import dev.dfonline.codeclient.mixin.entity.player.ClientPlayerInteractionManagerAccessor;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.SignText;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInputC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket;
import net.minecraft.network.packet.s2c.play.BlockUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.ChunkDeltaUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.OpenScreenS2CPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.PlayerInput;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import org.apache.commons.lang3.ObjectUtils;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

public class PlaceTemplates extends Action {
    /**
     * Ticks to wait for a placement's block-update confirm before assuming it failed (~2s @20tps).
     * DiamondFire's confirm often lands ~1-1.5s after the place, so a 1s window caused needless
     * retries and the occasional false "gave up after 4 attempts" - 2s lets the real confirm arrive.
     */
    private static final int CONFIRM_TIMEOUT_TICKS = 40;
    /** How many times to (re)send a placement before giving up and skipping it. */
    private static final int MAX_PLACE_ATTEMPTS = 4;
    /**
     * Max ticks (~20s) to spend trying to fly to + place ONE line before skipping it. Without this,
     * a single line the player can't reach (rubber-banded deep in the codespace) stalls the whole
     * batch until the websocket times out (and the placer keeps going after the mod gave up). Skip
     * it instead and let /df fill place it later.
     */
    private static final int MAX_WORK_TICKS = 400;
    /**
     * Horizontal stride between adjacent code columns, in blocks. DiamondFire lays code lines out
     * with TWO empty columns between them (one code block + two air), i.e. a stride of 3 - this
     * matches CodeClient's native grid ({@code 2 + row*3}) and yields 6 columns across a width-20
     * plot. Placing at a tighter stride (2 = a single air column) makes DF reject the lines as
     * "Invalid template placement" and reads as events crammed onto one layer, so this MUST stay 3.
     */
    private static final int COLUMN_STRIDE = 3;
    /**
     * Offhand-reach for placing. Like the scanner/salvager, the placer can equip an offhand item that
     * grants +{@link #REACH_BONUS} BLOCK_INTERACTION_RANGE so a whole CLUSTER of lines places from one
     * spot instead of flying onto each. {@link #PLACE_REACH_RADIUS} (squared into {@code placeRangeSq})
     * is how far we'll place from - kept safely inside the server-enforced limit (base ~4.5 + 60). Less
     * flying means far fewer anti-cheat rubber-bands / desync skips, which is the main reason a big deploy
     * used to leave a handful of lines behind. Toggle with /df config placeReach.
     */
    private static final double PLACE_REACH_RADIUS = 48.0;
    private static final double REACH_BONUS = 60.0;
    private static final int OFFHAND_SLOT = 45;
    /**
     * How {@link #createSwapper} lays out leftover (new) lines:
     *
     *  ORGANIZED (default): events go ONE PER COLUMN in type order (player, then game, then entity),
     *    then functions and processes are bin-packed - each category kept in its OWN run of columns
     *    (a function column never shares with a process). Because each category packs separately by
     *    line length, the columns it occupies come out proportional to that category's total
     *    code-block length (75% of the func+proc blocks -> ~3x the columns). Aims to fill the
     *    codespace with the fewest columns/layers while staying readable.
     *  MINIMAL (-compact): pack EVERY line end-to-end down columns regardless of type - the smallest
     *    possible footprint, no organization. For big games where space is tight.
     *
     * Matched lines (swaps) always go to their existing spot regardless of mode. Set by the mod from
     * the /df flags before placing; read in {@link #createSwapper}.
     */
    public enum LayoutMode { ORGANIZED, MINIMAL }

    private static volatile LayoutMode layoutMode = LayoutMode.ORGANIZED;

    public static void setLayout(LayoutMode mode) {
        layoutMode = (mode == null) ? LayoutMode.ORGANIZED : mode;
    }

    /**
     * ORGANIZED only: empty Y-layers to leave between the event / function / process bands.
     * 0 = bands touch (each type still starts on its own fresh layer); N = N blank layers between
     * bands; -1 = don't separate bands at all (one continuous fill, types may share a layer).
     */
    private static volatile int swapGap = 0;

    public static void setGap(int gap) {
        swapGap = gap;
    }

    /**
     * Cross-batch placement progress for the action-bar HUD. The mod resets this at the start of a
     * place op and reads {@link #progressPlaced()} on a timer; the placer ticks it up by one as each
     * line is seated (or given up), so the bar advances smoothly even within one big batch and keeps
     * counting across batches.
     */
    private static final AtomicInteger PLACED_PROGRESS = new AtomicInteger(0);

    public static void resetProgress() { PLACED_PROGRESS.set(0); }

    public static int progressPlaced() { return PLACED_PROGRESS.get(); }

    /**
     * The lines being placed. CONCURRENT: {@link #tick()} (game thread) structurally modifies this
     * via {@code removeIf}, while {@link #onReceivePacket} (Netty IO thread) iterates it to confirm
     * placements - a plain ArrayList there threw ConcurrentModificationException on big single-batch
     * deploys (hundreds of ops churning for a long time). A CopyOnWriteArrayList makes every
     * iteration a CME-immune snapshot and keeps removeIf atomic; the list is small (<= one batch) and
     * only structurally changes when ops complete, so the copy cost is negligible.
     */
    private final List<Operation> operations;
    /** Every template op this placer was created with (operations empties as ops complete) - kept for
     *  the end-of-batch verify pass that catches DiamondFire's SILENT template rejections. */
    private final List<TemplateToPlace> allOps = new ArrayList<>();
    /** Lines that gave up (couldn't place) - handed back to the player's inventory at the end so they
     *  can be placed manually, instead of being silently dropped. */
    private final ArrayList<ItemStack> failed = new ArrayList<>();
    /** Readable names of every line in {@link #failed}, in add order (for the WS "done" reply, so the
     *  mod can mark those files failed in last-sync instead of trusting the batch-level "done"). */
    private final ArrayList<String> failedLineNames = new ArrayList<>();
    private boolean verified = false;
    /** The failed-line names of the most recently FINISHED placer (one placer runs at a time). The WS
     *  layer appends these to its "place done"/"placepos done" reply via {@link #doneMessage}. */
    public static volatile List<String> LAST_FAILED = List.of();

    /** Build the WS completion reply: {@code base} alone on full success, else
     *  {@code base + " failed <name;name;...>"} listing every line that did NOT end up placed. */
    public static String doneMessage(String base) {
        List<String> f = LAST_FAILED;
        return f.isEmpty() ? base : base + " failed " + String.join(";", f);
    }
    private int cooldown = 0;
    private ItemStack recoverMainHand;
    private GoTo goTo = null;
    private boolean shouldBeSwapping = false;
    /** Offhand-reach placing: on = place from up to {@link #PLACE_REACH_RADIUS} away (see config placeReach). */
    private boolean useReach = false;
    private boolean reachEquipped = false;
    private double placeRangeSq = 0;

    /** Readable "type name" of a line from its header block (e.g. "func MyFunction", "event Join"). */
    private static String lineName(ItemStack item) {
        try {
            Template t = Template.parse64(Utility.templateDataItem(item));
            if (t != null && t.blocks != null && !t.blocks.isEmpty()) {
                TemplateBlock h = t.blocks.get(0);
                String name = ObjectUtils.firstNonNull(h.data, h.action);
                return (h.block != null ? h.block : "?") + (name != null ? " " + name : "");
            }
        } catch (Exception ignored) {
        }
        return "(unknown line)";
    }

    /** Record a line that did NOT end up placed: mark the op, queue the template for inventory
     *  hand-back, and remember its name for the WS "done failed ..." reply. */
    private void fail(TemplateToPlace op) {
        op.gaveUp = true;
        failed.add(op.template);
        failedLineNames.add(lineName(op.template));
    }

    /**
     * End-of-batch verification: every op that confirmed earlier must STILL have a non-air block at its
     * anchor. DiamondFire can accept the place packet (block appears -> we confirm) and then silently
     * reject the template's data, removing the line moments later - which used to be reported as
     * "placed" while the line was simply gone from the plot. Anchors in unloaded chunks are skipped
     * (can't be judged from here); air anchors are failed loudly and handed back like any other failure.
     */
    private void verifyPlacements() {
        var world = CodeClient.MC.world;
        if (world == null) return;
        int lateRejects = 0;
        for (TemplateToPlace op : allOps) {
            if (op.gaveUp) continue;
            BlockPos p = op.pos;
            if (!world.getChunkManager().isChunkLoaded(p.getX() >> 4, p.getZ() >> 4)) continue;
            if (!world.getBlockState(p).isAir()) continue;
            fail(op);
            lateRejects++;
            Log.step("place VERIFY: anchor @ " + p.toShortString() + " is AIR after placement - "
                    + lineName(op.template) + " was silently rejected by DF");
        }
        if (lateRejects > 0)
            Utility.sendMessage(lateRejects + " line(s) vanished after placing - DiamondFire silently rejected "
                    + "the template(s). Saved to your inventory; place one manually to see DF's own error.", ChatType.FAIL);
    }

    /** Give every line that gave up back to the player's inventory (creative), each NAMED with its
     *  function/event name so you can tell them apart, into a free slot - never slot 0, which the
     *  placer uses to hold its current template. Called once when placement finishes. */
    private void handBackFailed() {
        var player = CodeClient.MC.player;
        if (failed.isEmpty() || player == null) return;
        var inv = player.getInventory();
        int n = 0;
        for (ItemStack it : failed) {
            int slot = -1;
            for (int i = 1; i <= 35; i++) if (inv.getStack(i).isEmpty()) { slot = i; break; } // skip slot 0
            if (slot < 0) break; // inventory full
            ItemStack named = it.copy();
            try { DFItem.of(named).setName(Text.literal(lineName(it))); } catch (Exception ignored) {}
            inv.setStack(slot, named);
            n++;
        }
        failed.clear();
        Utility.sendInventory();
        if (n > 0) Utility.sendMessage(n + " line(s) couldn't be placed - saved to your inventory (named) to place manually.", ChatType.FAIL);
    }

    public PlaceTemplates(List<ItemStack> templates, Callback callback) {
        super(callback);
        if (CodeClient.location instanceof Dev plot) {
            int i = 0;
            ArrayList<Operation> templatesToPlace = new ArrayList<>();
            var rowSize = plot.assumeSize().codeWidth / 2 + 1;
            for (ItemStack template : templates) {
                int row = i % rowSize;
                int level = i / rowSize;
                i++;
                Vec3d pos = new Vec3d(plot.getX(), plot.getFloorY(), plot.getZ()).add((2 + (row * 3)) * -1, level * 5, 0);
                templatesToPlace.add(new TemplateToPlace(pos, template));
            }
            this.operations = new CopyOnWriteArrayList<>(templatesToPlace);
            for (Operation op : this.operations) if (op instanceof TemplateToPlace t) allOps.add(t);
        } else {
            throw new IllegalStateException("Player must be in dev mode.");
        }
    }

    public PlaceTemplates(HashMap<BlockPos, ItemStack> templates, Callback callback) {
        this(templates, null, callback);
    }

    /**
     * @param freshPositions positions that are FRESH placements into empty space (must land in air,
     *        never break/replace a block). Positions NOT in this set are intended swaps (replace the
     *        existing line there). null = treat every position as fresh (air-required).
     */
    public PlaceTemplates(HashMap<BlockPos, ItemStack> templates, Set<BlockPos> freshPositions, Callback callback) {
        super(callback);
        ArrayList<Operation> templatesToPlace = new ArrayList<>();
        for (var template : templates.entrySet()) {
            boolean fresh = (freshPositions == null) || freshPositions.contains(template.getKey());
            String token = null;
            try { token = Utility.templateDataItem(template.getValue()); } catch (Exception ignored) {}
            if (token != null && token.length() > com.dfvibe.Templates.MAX_TOKEN) {
                // Over-64KB template: sending it in ONE item would disconnect the client (NBT string cap),
                // so SEGMENT-PLACE it - same blocks split across several templates, placed contiguously
                // along one physical line = the original line rebuilt 1-1, no code modification. The FIRST
                // segment keeps the op's swap/fresh semantics (a swap's starter-break removes the whole old
                // line); the rest land in the air that break/clear left, straight south down the line.
                List<String> segs = com.dfvibe.Templates.split(token);
                if (segs == null) {
                    Log.step("place: over-64KB template couldn't be split - skipped: " + lineName(template.getValue()));
                    Utility.sendMessage("Couldn't split over-64KB line " + lineName(template.getValue())
                            + " into segments - SKIPPED.", ChatType.FAIL);
                    continue;
                }
                int zOff = 0;
                TemplateToPlace head = null;
                for (String seg : segs) {
                    TemplateToPlace op = new TemplateToPlace(template.getKey().south(zOff), Utility.makeTemplate(seg));
                    op.requireAir = head != null || fresh;
                    op.after = head; // a swap's continuation must WAIT for the head's break/place to clear the old line
                    if (head == null) head = op;
                    templatesToPlace.add(op);
                    int len = com.dfvibe.Templates.length(seg);
                    zOff += Math.max(0, len);
                }
                Log.step("place: segment-placing " + lineName(template.getValue()) + " as " + segs.size()
                        + " parts @ " + template.getKey().toShortString());
                continue;
            }
            TemplateToPlace op = new TemplateToPlace(template.getKey(), template.getValue());
            op.requireAir = fresh;
            templatesToPlace.add(op);
        }
        this.operations = new CopyOnWriteArrayList<>(templatesToPlace);
        for (Operation op : this.operations) if (op instanceof TemplateToPlace t) allOps.add(t);
    }

    /**
     * Doesn't add .swap(), that needs to be added yourself.
     * Will return <b>null</b> if there is no space.
     */
    public static @Nullable PlaceTemplates createSwapper(List<ItemStack> templates, Callback callback) {
        if (CodeClient.location instanceof Dev dev) {
            HashMap<BlockPos, ItemStack> map = new HashMap<>();
            Set<BlockPos> freshPositions = new HashSet<>(); // leftovers placed into empty space (air-required)
            var scan = dev.scanForSigns(Pattern.compile(".*"));
            ArrayList<ItemStack> leftOvers = new ArrayList<>(templates);
            for (ItemStack item : templates) {
                DFItem dfItem = new DFItem(item);
                Optional<String> codeTemplateData = dfItem.getHypercubeStringValue("codetemplatedata");
                if (codeTemplateData.isEmpty()) continue;
                try {
                    Template template = Template.parse64(JsonParser.parseString(codeTemplateData.get()).getAsJsonObject().get("code").getAsString());
                    if (template == null || template.blocks.isEmpty()) continue;
                    TemplateBlock block = template.blocks.get(0);
                    if (block.block == null) continue;
                    TemplateBlock.Block blockName = TemplateBlock.Block.valueOf(block.block.toUpperCase());
                    String name = ObjectUtils.firstNonNull(block.action, block.data);
                    for (Map.Entry<BlockPos, SignText> sign : scan.entrySet()) { // Loop through scanned signs
                        SignText text = sign.getValue();                        // ↓ If the blockName and name match
                        if (text.getMessage(0, false).getString().equals(blockName.name) && text.getMessage(1, false).getString().equals(name)) {
                            map.put(sign.getKey().east(), item);                // Put it into map
                            leftOvers.remove(item);                             // Remove the template, so we can see if there's anything left over
                            break;                                              // break out :D
                        }
                    }
                } catch (Exception e) {
                    CodeClient.LOGGER.warn(e.getMessage());
                }
            }
            if (!leftOvers.isEmpty()) {
                // Lay out leftover (new) lines. Matched lines already swapped into their existing
                // spots above; these have no spot yet, so we choose their geometry by layout mode.
                //
                // ORGANIZED (default): each TYPE gets its own vertical BAND of Y-layers - events at
                //   the bottom (player, then game, then entity), then functions, then processes - and
                //   a type never shares a layer with another type. Within a band, lines go ONE PER
                //   COLUMN (the "spread" look: ~codeWidth/2 lines across a layer, then up to the next
                //   layer) - as uncompact as possible. `-gap N` leaves N empty layers between bands
                //   (gap -1 = don't separate bands). If one-per-column would overflow the codespace,
                //   the bands fall back to PACKING (south-stacking lines down each column) just enough
                //   that all the code still fits.
                // MINIMAL (-compact): ignore all of that - pack every line end-to-end down columns,
                //   smallest possible footprint.
                // Lines arrive already sorted by the mod (type, then the -sort key); the stable sort
                // by type here only firms up the grouping without disturbing that order.
                // ORGANIZED groups by type (events/funcs/procs into their bands). MINIMAL must NOT
                // regroup by type - it packs in whatever order the caller sent (size-descending, for
                // tightest bin-packing), so -compact doesn't pointlessly lead with events.
                if (layoutMode != LayoutMode.MINIMAL)
                    leftOvers.sort(Comparator.comparingInt(PlaceTemplates::templateRank));
                BlockPos first = dev.findFreePlacePos();
                if (first == null) return null;
                final int firstX = first.getX(), originZ = first.getZ(), startY = first.getY();
                final int originX = dev.getX();
                final int codeWidth = dev.assumeSize().codeWidth;
                final int maxY = 250;                                       // top placeable layer (y < 255)
                final int colsPerLayer = Math.max(1, (codeWidth - 4) / COLUMN_STRIDE + 1);
                final int availLayers = Math.max(1, (maxY - startY) / 5 + 1);

                if (layoutMode == LayoutMode.MINIMAL) {
                    // Smallest footprint: south-stack every line down columns, ignore type/bands.
                    BlockPos colTop = first, nextPos = first;
                    boolean firstLine = true;
                    for (var item : leftOvers) {
                        Template parsed = Template.parse64(Utility.templateDataItem(item));
                        if (parsed == null) return null;
                        int size = parsed.getLength();
                        BlockPos placePos;
                        if (firstLine) placePos = colTop;
                        else if (dev.isInDev(nextPos.south(size))) placePos = nextPos;       // fits this column
                        else { colTop = dev.findFreePlacePos(colTop.west(2)); if (colTop == null) return null; placePos = colTop; }
                        nextPos = placePos.south(size + 2);
                        map.put(placePos, item);
                        freshPositions.add(placePos);
                        firstLine = false;
                    }
                } else {
                    // ORGANIZED: the most UNCOMPACT layout. Every line gets its OWN column (one line
                    // per line), 6 columns per layer, filling a layer left-to-right then stepping UP to
                    // the next - so it uses as many codespace layers as the code needs. Lines are NEVER
                    // stacked down a column here; that's what -compact is for. Each TYPE starts on its
                    // own fresh layer (events at the bottom: player -> game -> entity, then functions,
                    // then processes) so a type never shares a layer with another, with `gap` empty
                    // layers between bands (-gap -1 = don't separate bands, one continuous spread). If
                    // one-per-line runs off the top of the codespace, the overflow is reported (use
                    // -compact to pack, -underground for ~9x the layers, or a bigger plot).
                    int gap = swapGap;
                    boolean bands = (gap != -1);
                    if (gap < 0) gap = 0;
                    Log.step("organized layout: spread 1/column, " + colsPerLayer + " cols/layer, "
                            + availLayers + " layers avail, gap=" + (bands ? gap : -1));

                    int curX = firstX, curY = startY;
                    int prevBand = -1;
                    boolean usedLayer = false;
                    int overflowed = 0;
                    for (var item : leftOvers) {
                        Template parsed = Template.parse64(Utility.templateDataItem(item));
                        if (parsed == null) return null;
                        int band = bandOf(lineTypeRank(parsed));

                        // New band -> jump to a fresh layer (+ gap empty layers between bands).
                        if (bands && prevBand != -1 && band != prevBand) {
                            if (usedLayer) curY += 5;
                            curY += 5 * gap;
                            curX = firstX; usedLayer = false;
                        }
                        prevBand = band;

                        // One line per column; wrap up to the next layer when this layer's row is full.
                        if (usedLayer) {
                            curX -= COLUMN_STRIDE;                              // 2 empty columns between lines (DF spacing)
                            if (originX - curX > codeWidth - 2) { curX = firstX; curY += 5; } // layer full -> up
                        }

                        if (curY > maxY) { overflowed++; continue; }            // off the top of the codespace

                        BlockPos placePos = new BlockPos(curX, curY, originZ);  // every line's anchor at the top (z=originZ)
                        map.put(placePos, item);
                        freshPositions.add(placePos);
                        usedLayer = true;
                    }
                    if (overflowed > 0) {
                        Log.step("layout OVERFLOW: " + overflowed + " line(s) ran past the top of the codespace");
                        Utility.sendMessage(overflowed + " line(s) didn't fit one-per-line here - use -compact (pack tight), "
                                + "-underground (~9x the layers), or a bigger plot.", ChatType.FAIL);
                    }
                }
            }
            return new PlaceTemplates(map, freshPositions, callback);
        }
        return null;
    }

    /**
     * A regular placer will always start from where code starts.
     * This will use any free spaces instead.
     * Will return <b>null</b> if there is no space.
     */
    public static PlaceTemplates createPlacer(List<ItemStack> templates, Callback callback) {
        return createPlacer(templates, callback, false);
    }

    /**
     * A regular placer will always start from where code starts.
     * This will use any free spaces instead.
     * Will return <b>null</b> if there is no space or some other error occurs.
     */
    public static PlaceTemplates createPlacer(List<ItemStack> templates, Callback callback, boolean compacter) {
        if (CodeClient.location instanceof Dev dev) {
            var map = new HashMap<BlockPos, ItemStack>();
            if (!compacter) {
                BlockPos lastPos = dev.findFreePlacePos();
                for (var template : templates) {
                    if(lastPos == null) return null;
                    map.put(lastPos, template);
                    lastPos = dev.findFreePlacePos(lastPos.west(2));
                }
            } else {
                BlockPos nextPos = dev.findFreePlacePos();
                for (var template : templates) {
                    Template parsed = Template.parse64(Utility.templateDataItem(template));
                    if(parsed == null) return null;
                    int size = parsed.getLength();
                    var placePos = nextPos;
                    if(placePos == null) return null;
                    var templateEndPos = placePos.south(size);
                    if (dev.isInDev(templateEndPos)) {
                        nextPos = templateEndPos;
                    } else {
                        placePos = dev.findFreePlacePos(placePos.west(2));
                        if(placePos == null) return null;
                        nextPos = placePos.south(size);
                    }
                    map.put(placePos, template);
                }
            }
            return new PlaceTemplates(map, callback);
        }
        return null;
    }

    /**
     * Layout order for a code line, keyed off its starter block: player events first (so they
     * land in the lowest layer), then game events, then entity events, then functions, then
     * processes. Unknown starters sort last. Used by {@link #createSwapper} to organize the
     * codespace.
     */
    private static int lineTypeRank(Template template) {
        if (template == null || template.blocks == null || template.blocks.isEmpty()) return 9;
        String block = template.blocks.get(0).block;
        if (block == null) return 9;
        return switch (block.toLowerCase()) {
            case "event" -> 0;          // PLAYER EVENT
            case "game_event" -> 1;     // GAME EVENT
            case "entity_event" -> 2;   // ENTITY EVENT
            case "func" -> 3;           // FUNCTION
            case "process" -> 4;        // PROCESS
            default -> 9;
        };
    }

    /** {@link #lineTypeRank} for a not-yet-parsed template item (used as a sort key). */
    private static int templateRank(ItemStack item) {
        return lineTypeRank(Template.parse64(Utility.templateDataItem(item)));
    }

    /** The layer BAND a line belongs to: events (0), functions (1), processes (2), unknown (3).
     *  Player/game/entity events share band 0 (so they sit together, just sorted within). */
    private static int bandOf(int rank) {
        return rank <= 2 ? 0 : Math.min(rank - 2, 3);
    }


    /**
     * A fresh placement spot is "clear" when the anchor block AND the block the line extends into
     * (one south) are both air - "a block of air with a block of air next to it". Guards against
     * placing onto / overlapping existing code when the player's position is desynced.
     */
    private static boolean placeSpotClear(BlockPos pos) {
        var world = CodeClient.MC.world;
        if (world == null) return false;
        return world.getBlockState(pos).isAir() && world.getBlockState(pos.south()).isAir();
    }

    /**
     * If there is a template in the way, replace it.
     */
    public PlaceTemplates swap() {
        this.shouldBeSwapping = true;
        return this;
    }

    /** Engage NoClip's wall-sanitized movement while placing (see {@link dev.dfonline.codeclient.dev.NoClip}). */
    @Override
    public boolean wantsNoClip() {
        return true;
    }

    @Override
    public void init() {
        cooldown = 4;
        LAST_FAILED = List.of(); // this batch's failures only - don't leak the previous batch's
        assert CodeClient.MC.player != null;
        recoverMainHand = CodeClient.MC.player.getMainHandStack();
        // Offhand-reach placing (default on): one stand spot covers a cluster of lines, so we fly far less.
        useReach = com.dfvibe.DfVibeClient.config == null || com.dfvibe.DfVibeClient.config.placeReach;
        placeRangeSq = useReach ? (PLACE_REACH_RADIUS * PLACE_REACH_RADIUS) : 0;
        if (useReach) equipReach();
    }

    /** Put a big +block-interaction-range item in the offhand so lines can be placed from far away. */
    private void equipReach() {
        var nh = CodeClient.MC.getNetworkHandler();
        if (nh == null) return;
        ItemStack item = new ItemStack(net.minecraft.item.Items.STICK);
        var mods = net.minecraft.component.type.AttributeModifiersComponent.builder()
                .add(net.minecraft.entity.attribute.EntityAttributes.BLOCK_INTERACTION_RANGE,
                        new net.minecraft.entity.attribute.EntityAttributeModifier(
                                net.minecraft.util.Identifier.of("dfvibe", "place_reach"), REACH_BONUS,
                                net.minecraft.entity.attribute.EntityAttributeModifier.Operation.ADD_VALUE),
                        net.minecraft.component.type.AttributeModifierSlot.OFFHAND)
                .build();
        item.set(net.minecraft.component.DataComponentTypes.ATTRIBUTE_MODIFIERS, mods);
        nh.sendPacket(new net.minecraft.network.packet.c2s.play.CreativeInventoryActionC2SPacket(OFFHAND_SLOT, item));
        reachEquipped = true;
    }

    private void clearReach() {
        if (!reachEquipped) return;
        var nh = CodeClient.MC.getNetworkHandler();
        if (nh != null) nh.sendPacket(new net.minecraft.network.packet.c2s.play.CreativeInventoryActionC2SPacket(OFFHAND_SLOT, ItemStack.EMPTY));
        reachEquipped = false;
    }

    @Override
    public boolean onReceivePacket(Packet<?> packet) {
        if (packet instanceof OpenScreenS2CPacket) {
            return true;
        }
        if (packet instanceof ChunkDeltaUpdateS2CPacket updates) {
            for (Operation operation : operations) {
                var block = new Object() {
                    boolean isTemplate = false;
                    BlockState state = null;
                };
                updates.visitUpdates((blockPos, blockState) -> {
                    if (operation.pos.equals(blockPos)) {
                        block.isTemplate = true;
                        block.state = blockState;
                    }
                });
                if (operation instanceof TemplateToPlace) {
                    if (!block.isTemplate) continue;
                    // Confirm ONLY on a real (non-air) block at the anchor. A swap BREAKS the old line first,
                    // and that break's own block->AIR update used to count as the confirmation - so when
                    // DiamondFire then silently rejected the template (bad data, desync), the op still read
                    // "placed" while the line was actually GONE from the plot. Air = not placed yet.
                    if (block.state == null || block.state.isAir()) continue;
                    boolean recovered = operation.wasTimedOut() && !operation.isComplete();
                    operation.setComplete();
                    if (recovered) {
                        Log.step("place CONFIRMED (recovered) @ " + operation.posLabel());
                        Utility.sendMessage("Returned to " + operation.posLabel()
                                + " - placement went through.", ChatType.SUCCESS);
                    }
                }
            }
        }
        // A placement whose block change arrives as a SINGLE-block update (not a multi-block chunk
        // delta) was previously missed here, so the line falsely "timed out" and was retried/given up
        // even though it placed (this is what kept making normal-length lines like @test fail). Treat a
        // single-block update at an operation's anchor as the same confirmation.
        if (packet instanceof BlockUpdateS2CPacket bu) {
            for (Operation operation : operations) {
                if (operation instanceof TemplateToPlace && operation.pos.equals(bu.getPos())) {
                    if (bu.getState() == null || bu.getState().isAir()) continue; // our own swap-break echo, not a placement
                    boolean recovered = operation.wasTimedOut() && !operation.isComplete();
                    operation.setComplete();
                    if (recovered) {
                        Log.step("place CONFIRMED (recovered, single-block) @ " + operation.posLabel());
                        Utility.sendMessage("Returned to " + operation.posLabel()
                                + " - placement went through.", ChatType.SUCCESS);
                    }
                }
            }
        }
        return super.onReceivePacket(packet);
    }

    @Override
    public void tick() {
        var net = CodeClient.MC.getNetworkHandler();
        if (CodeClient.MC.interactionManager == null || CodeClient.MC.player == null || net == null) return;
        // /df end pause: freeze placement (it resumes on /df end continue). Note: a very long pause can still
        // time out the websocket place call - pause is most reliable for scans (which heartbeat).
        if (com.dfvibe.SyncService.PAUSED) return;
        if (CodeClient.location instanceof Dev) {
            if (operations.isEmpty()) {
                if (!verified) { verified = true; verifyPlacements(); } // catch DF's silent late rejections
                clearReach();
                LAST_FAILED = List.copyOf(failedLineNames);
                handBackFailed();
                callback();
                return;
            }
            if (goTo != null) {
                goTo.tick();
            }
            if (cooldown > 0) cooldown--;
            // Drop finished ops, advancing the action-bar progress bar by however many were removed
            // (size-delta, so the count stays exact regardless of how the list evaluates the predicate).
            int beforeRemove = operations.size();
            operations.removeIf(Operation::isComplete);
            int removed = beforeRemove - operations.size();
            if (removed > 0) PLACED_PROGRESS.addAndGet(removed);
            // Recover from placements the server silently rejected (desync / anti-cheat rubber-band):
            // a "placed" block that never gets confirmed would otherwise stay the nearest target
            // forever, freezing the placer on it. If it isn't confirmed within CONFIRM_TIMEOUT_TICKS,
            // retry it; after MAX_PLACE_ATTEMPTS, skip it so the deploy keeps moving (a re-deploy fills
            // skips - it's idempotent). Each timeout/skip is reported in chat so it isn't a silent stall.
            for (Operation operation : operations) {
                if (operation.awaitingConfirm() && operation.tickAwaiting() > CONFIRM_TIMEOUT_TICKS) {
                    if (operation.attempts() >= MAX_PLACE_ATTEMPTS) {
                        operation.setComplete();
                        String nm = "(line)";
                        if (operation instanceof TemplateToPlace t) { nm = lineName(t.template); fail(t); }
                        Log.step("place GAVE UP: " + nm + " @ " + operation.posLabel() + " after " + operation.attempts() + " attempts");
                        Utility.sendMessage("Couldn't place " + nm + " @ " + operation.posLabel()
                                + " after " + operation.attempts() + " tries - saved to your inventory to place manually.", ChatType.FAIL);
                    } else {
                        operation.markTimedOut();
                        Log.step("place TIMED OUT @ " + operation.posLabel() + " - retrying (attempt "
                                + (operation.attempts() + 1) + "/" + MAX_PLACE_ATTEMPTS + ")");
                        Utility.sendMessage("Placement at " + operation.posLabel() + " timed out - retrying (attempt "
                                + (operation.attempts() + 1) + "/" + MAX_PLACE_ATTEMPTS + ").", ChatType.INFO);
                    }
                }
            }
            // Pick the next line: strongly prefer the player's CURRENT layer (drain a whole layer
            // before changing height), then nearest horizontally. This keeps the player gliding at one
            // above-chest height instead of bobbing up and down between layers.
            Vec3d feet = CodeClient.MC.player.getEntityPos();
            int playerLayer = (int) Math.floor(feet.y / 5.0);
            Operation target = null;
            double bestMetric = Double.MAX_VALUE;
            for (Operation operation : operations) {
                if (!operation.isOpen() || operation.isComplete()) continue;
                if (operation instanceof TemplateToPlace tt && tt.after != null
                        && tt.after.attempts() == 0 && !tt.after.isComplete()) continue; // wait for its head segment
                Vec3d s = operation.pos().add(1, 1.5, 0);
                double dxs = s.x - feet.x, dzs = s.z - feet.z;
                double h = Math.sqrt(dxs * dxs + dzs * dzs);
                int opLayer = (int) Math.floor(s.y / 5.0);
                double metric = h + 100000.0 * Math.abs(playerLayer - opLayer);
                if (metric < bestMetric) {
                    bestMetric = metric;
                    target = operation;
                }
            }
            // Offhand-reach: if any open line is already within reach of where we stand, place THAT one
            // first (no flight) - mops up the whole cluster around the current spot before we move on,
            // exactly like the scanner does. Only the metric pick (above) drives where we fly next.
            if (useReach && goTo == null) {
                Operation inReach = null;
                double bestReachSq = Double.MAX_VALUE;
                for (Operation operation : operations) {
                    if (!operation.isOpen() || operation.isComplete()) continue;
                    if (operation instanceof TemplateToPlace tt && tt.after != null
                            && tt.after.attempts() == 0 && !tt.after.isComplete()) continue; // wait for its head segment
                    double dq = feet.squaredDistanceTo(operation.pos().add(0, 1, 0));
                    if (dq <= placeRangeSq && dq < bestReachSq) { bestReachSq = dq; inReach = operation; }
                }
                if (inReach != null) target = inReach;
            }
            // Nothing open to place right now (everything placed is awaiting its block-update confirm):
            // wait - the timeout loop above reopens anything that doesn't come back, so this can't stall.
            if (target == null) return;
            // Give up on a line we can't reach/place in time so it can't stall the batch (/df fill later).
            if (target.tickWork() > MAX_WORK_TICKS) {
                String nm = "(line)";
                if (target instanceof TemplateToPlace t) { nm = lineName(t.template); fail(t); }
                Log.step("place GAVE UP (couldn't reach/place in time): " + nm + " @ " + target.posLabel());
                Utility.sendMessage("Couldn't place " + nm + " @ " + target.posLabel()
                        + " in time - saved to your inventory to place manually.", ChatType.FAIL);
                target.setComplete();
                return;
            }
            // This line's stand spot: 1 block EAST, 2 up - on top of the chest, in clear air.
            Vec3d stand = target.pos().add(1, 1.5, 0);
            double sdx = stand.x - feet.x, sdz = stand.z - feet.z;
            double horiz = Math.sqrt(sdx * sdx + sdz * sdz);
            double dy = Math.abs(stand.y - feet.y);
            // Place ONLY once we can actually reach this line's spot. WITHOUT reach: we must be standing
            // right on it - on its layer (dy small) AND right above it (horiz small); placing from 5+
            // blocks away used to fire across layers and get rubber-banded / rejected. WITH reach: the
            // offhand item makes that range legitimate, so "arrived" just means the anchor is within the
            // (server-honored) reach radius - one stand spot then seats a whole cluster.
            boolean arrived = useReach
                    ? (goTo == null && feet.squaredDistanceTo(target.pos().add(0, 1, 0)) <= placeRangeSq)
                    : (goTo == null && horiz < 2.0 && dy < 2.5);
            if (arrived && cooldown == 0 && target instanceof TemplateToPlace template) {
                // SAFETY: a fresh placement must land in clear air. If the anchor or the block the line
                // extends into (south) is occupied, SKIP rather than overlap/break existing code.
                if (template.requireAir && !placeSpotClear(template.pos)) {
                    String nm = lineName(template.template);
                    fail(template); // surface it (inventory + count + WS reply), don't silently drop
                    Log.step("place SKIP: " + nm + " @ " + template.pos.toShortString() + " - spot not clear (would overlap existing code)");
                    Utility.sendMessage("Couldn't place " + nm + " @ " + template.pos.toShortString()
                            + " - spot wasn't clear - saved to your inventory.", ChatType.FAIL);
                    template.setComplete();
                    return;
                }
                // Only break/replace a block for an intended SWAP (a matched existing line).
                // NEVER destroy on a fresh placement - that randomly broke blocks when the player desynced.
                if (shouldBeSwapping && !template.requireAir) {
                    var player = CodeClient.MC.player;
                    boolean sneaky = !player.isSneaking();
                    // TODO I have never actually tested if these input packets actually replicate sneaking.
                    if (sneaky) net.sendPacket(new PlayerInputC2SPacket(new PlayerInput(false, false, false, false, false, true, false)));
                    net.sendPacket(new PlayerActionC2SPacket(PlayerActionC2SPacket.Action.START_DESTROY_BLOCK, template.pos, Direction.UP));
                    net.sendPacket(new PlayerActionC2SPacket(PlayerActionC2SPacket.Action.ABORT_DESTROY_BLOCK, template.pos, Direction.UP));
                    if (sneaky) net.sendPacket(new PlayerInputC2SPacket(CodeClient.MC.player.getLastPlayerInput()));
                    Log.step("swap-replace @ " + template.pos.toShortString());
                }
                Utility.makeHolding(template.template);
                BlockHitResult blockHitResult = new BlockHitResult(template.pos().add(0, 1, 0), Direction.UP, template.pos, false);
                ((ClientPlayerInteractionManagerAccessor) (CodeClient.MC.interactionManager)).invokeSequencedPacket(CodeClient.MC.world, sequence -> new PlayerInteractBlockC2SPacket(Hand.MAIN_HAND, blockHitResult, sequence));
                Log.step("place @ " + template.pos.toShortString() + " (attempt " + (template.attempts() + 1) + (template.requireAir ? ", fresh" : ", swap") + ")");
                template.markPlaced();
                return;
            }
            // Not there yet - fly to the above-chest spot (GoTo travels straight cardinal segments).
            if (!arrived && goTo == null) {
                final int settle = Math.min(5, 2 + (int) (horiz / 40));
                goTo = new GoTo(stand, () -> { this.goTo = null; this.cooldown = settle; });
                goTo.init();
                cooldown = 2;
            }
        }
    }

    private static abstract class Operation {
        protected final BlockPos pos;
        /**
         * If this operation can be acted on, and needs to be completed.
         */
        private boolean open = true;
        /**
         * Whether this operation is complete and can be dismissed.
         */
        private boolean complete = false;
        /** Ticks since the place packet was last sent, while awaiting the block-update confirm. */
        private int sincePlaced = 0;
        /** How many times we've sent a place packet for this op (for retry-then-skip). */
        private int attempts = 0;
        /** Whether this op ever timed out waiting for confirmation (for the "returned to" message). */
        private boolean timedOut = false;
        /** Ticks spent flying to / waiting on this op (for the unreachable give-up cap). */
        private int workTicks = 0;

        public Operation(BlockPos pos) {
            this.pos = pos;
        }

        public Operation(Vec3d pos) {
            this.pos = new BlockPos((int) pos.x, (int) pos.y, (int) pos.z);
        }

        public Vec3d pos() {
            return pos.toCenterPos();
        }

        public boolean isOpen() {
            return open;
        }

        public void setOpen(boolean open) {
            this.open = open;
        }

        /**
         * Mark for dismissing. Doesn't need to be used again.
         */
        public void setComplete() {
            this.open = false;
            this.complete = true;
        }

        public boolean isComplete() {
            return complete;
        }

        /** A place packet was just sent for this op; start awaiting its confirmation. */
        public void markPlaced() {
            this.open = false;
            this.sincePlaced = 0;
            this.attempts++;
        }

        /** True while a place was sent but the block hasn't been confirmed (could have failed). */
        public boolean awaitingConfirm() {
            return !open && !complete;
        }

        /** Advance and return the awaiting-confirmation tick count. */
        public int tickAwaiting() {
            return ++sincePlaced;
        }

        public int attempts() {
            return attempts;
        }

        /** Count a tick spent trying to reach/place this op; returns the running total. */
        public int tickWork() {
            return ++workTicks;
        }

        /** Mark a timed-out placement for retry: reopen it and remember it timed out. */
        public void markTimedOut() {
            this.open = true;
            this.timedOut = true;
        }

        public boolean wasTimedOut() {
            return timedOut;
        }

        /** Short "x, y, z" of this op's block, for chat diagnostics. */
        public String posLabel() {
            return pos.toShortString();
        }
    }

    private static class TemplateToPlace extends Operation {
        private final ItemStack template;
        /** true = fresh placement into empty space (must land in air); false = replace existing line. */
        boolean requireAir = true;
        /** This op was recorded as failed (gave up / rejected) - excluded from the verify pass. */
        boolean gaveUp = false;
        /** Segment ordering: don't attempt this op until {@code after} has at least been attempted (its
         *  swap-break is what clears the old line out of our cells). null = no dependency. */
        TemplateToPlace after = null;

        public TemplateToPlace(Vec3d pos, ItemStack template) {
            super(pos);
            this.template = template;
        }

        public TemplateToPlace(BlockPos pos, ItemStack template) {
            super(pos);
            this.template = template;
        }
    }
}
