package com.dfvibe;

import java.util.ArrayList;
import java.util.List;

/**
 * Computes the ENTIRE codespace layout for a deploy up front (all lines at once), so placement can be
 * chunked for anti-flood pacing WITHOUT the chunks overlapping each other. It's pure geometry and
 * assumes a CLEARED plot (deploy/restore clear first), so it never reads the world - every line gets
 * an absolute position decided here, and the placer just seats blocks at those positions.
 *
 * Geometry: code lines sit in COLUMNS going west (x = originX-2, -5, -8, ... stride 3 = two empty
 * columns between lines, DiamondFire's spacing), 6 columns across a width-20 layer; a line extends
 * SOUTH (z+) for its length; layers stack every 5 Y. floorY = 50 normally, 5 underground; top ~250.
 *
 * ORGANIZED (default): events go ONE PER COLUMN at the bottom (player -> game -> entity), then
 *   functions, then processes, each type on its own layers (with `gap` blank layers between bands). If
 *   one-per-column fits the codespace it stays fully spread (the most uncompact, readable layout). If
 *   it does NOT fit, functions/processes (NEVER events) pack multiple-per-column: each type gets a run
 *   of columns sized PROPORTIONAL to its total line length, and within that run lines are balanced so
 *   the columns are evenly dense ("roughly the same amount of uncompacted"). MINIMAL (-compact): pack
 *   every line tight, column by column, ignoring type - the smallest footprint.
 */
public final class Layout {
    /** One line placed at an absolute block position. token = the base64 template string. */
    public record Placement(int x, int y, int z, String token) {}

    private static final int STRIDE = 3;     // columns: a code block + 2 air
    private static final int LAYER = 5;       // Y between layers
    private static final int MAX_Y = 250;     // top placeable layer (world cap is y<255)

    private final int originX, originZ, floorY, codeLength;
    private final int colsPerLayer, maxLayers, maxCols;

    public Layout(int originX, int originZ, int floorY, int codeWidth, int codeLength) {
        this.originX = originX;
        this.originZ = originZ;
        this.floorY = floorY;
        this.codeLength = codeLength;
        this.colsPerLayer = Math.max(1, (codeWidth - 4) / STRIDE + 1); // 6 for width 20
        this.maxLayers = Math.max(1, (MAX_Y - floorY) / LAYER + 1);
        this.maxCols = maxLayers * colsPerLayer;
    }

    public int colsPerLayer() { return colsPerLayer; }
    public int maxLayers() { return maxLayers; }

    private int colX(int col) { return originX - 2 - (col % colsPerLayer) * STRIDE; }
    private int colY(int col) { return floorY + (col / colsPerLayer) * LAYER; }
    private static int ceilDiv(int a, int b) { return (a + b - 1) / b; }
    private int roundUpToLayer(int col) { return ceilDiv(col, colsPerLayer) * colsPerLayer; }

    /** band: 0 events, 1 func, 2 proc, 3 unknown (from {@link Templates#lineRank}). */
    private static int bandOf(int rank) { return rank <= 2 ? 0 : Math.min(rank - 2, 3); }

    private record Line(String token, int len, int rank) {}

    /**
     * @param tokens   lines, ALREADY ordered by the caller (type then -sort key; size-desc for compact).
     * @param compact  MINIMAL (pack tight, ignore type) vs ORGANIZED.
     * @param gap      ORGANIZED: blank layers between type bands; -1 = don't separate bands (continuous).
     * @param overflow filled with any tokens that didn't fit the codespace (null to ignore).
     */
    public List<Placement> compute(List<String> tokens, boolean compact, int gap, List<String> overflow) {
        List<Line> lines = new ArrayList<>(tokens.size());
        for (String t : tokens) lines.add(new Line(t, Math.max(1, Templates.length(t)), Templates.lineRank(t)));
        List<Placement> out = new ArrayList<>(lines.size());

        if (compact) {
            packTight(lines, out, overflow); // smallest footprint, type-agnostic
            return out;
        }

        boolean bands = (gap >= 0);
        if (gap < 0) gap = 0;
        // Split into type bands, preserving the caller's order within each.
        List<Line> ev = new ArrayList<>(), fn = new ArrayList<>(), pr = new ArrayList<>(), uk = new ArrayList<>();
        for (Line l : lines) switch (bandOf(l.rank())) {
            case 0 -> ev.add(l);
            case 1 -> fn.add(l);
            case 2 -> pr.add(l);
            default -> uk.add(l);
        }

        // Does everything fit ONE PER COLUMN (fully spread)?
        int present = (ev.isEmpty() ? 0 : 1) + (fn.isEmpty() ? 0 : 1) + (pr.isEmpty() ? 0 : 1) + (uk.isEmpty() ? 0 : 1);
        int gapLayers = bands ? Math.max(0, present - 1) * gap : 0;
        int spreadLayers = ceilDiv(ev.size(), colsPerLayer) + ceilDiv(fn.size(), colsPerLayer)
                + ceilDiv(pr.size(), colsPerLayer) + ceilDiv(uk.size(), colsPerLayer) + gapLayers;
        boolean spread = spreadLayers <= maxLayers;

        if (spread) {
            int col = 0;
            col = spreadBand(ev, col, out, overflow);
            col = nextBandStart(col, bands, gap, !fn.isEmpty() || !pr.isEmpty() || !uk.isEmpty());
            col = spreadBand(fn, col, out, overflow);
            col = nextBandStart(col, bands, gap, !pr.isEmpty() || !uk.isEmpty());
            col = spreadBand(pr, col, out, overflow);
            col = nextBandStart(col, bands, gap, !uk.isEmpty());
            spreadBand(uk, col, out, overflow);
        } else {
            // Events ALWAYS one-per-column at the bottom; functions/processes pack above them.
            int col = spreadBand(ev, 0, out, overflow);
            int startCol = (bands && !ev.isEmpty()) ? roundUpToLayer(col) + gap * colsPerLayer : col;
            startCol = Math.min(startCol, maxCols);
            int avail = Math.max(0, maxCols - startCol);
            // Give each remaining type a run of columns proportional to its total line length.
            long lf = totalLen(fn), lp = totalLen(pr), lu = totalLen(uk), lt = lf + lp + lu;
            int cf = alloc(avail, lf, lt, !fn.isEmpty());
            int cp = alloc(avail, lp, lt, !pr.isEmpty());
            int cu = alloc(avail, lu, lt, !uk.isEmpty());
            int rem = avail - (cf + cp + cu);                 // hand rounding remainder to the biggest type
            if (rem > 0) { if (lf >= lp && lf >= lu) cf += rem; else if (lp >= lu) cp += rem; else cu += rem; }
            int c = startCol;
            packBalanced(fn, c, c + cf, out, overflow); c += cf;
            packBalanced(pr, c, c + cp, out, overflow); c += cp;
            packBalanced(uk, c, c + cu, out, overflow);
        }
        return out;
    }

    private long totalLen(List<Line> g) { long s = 0; for (Line l : g) s += l.len() + 2; return s; }

    private int alloc(int avail, long part, long total, boolean present) {
        if (!present || total <= 0 || avail <= 0) return present ? Math.min(1, avail) : 0;
        return Math.max(1, (int) Math.round((double) avail * part / total));
    }

    /** One line per column from startCol; returns the next free column. Overflow past the codespace top. */
    private int spreadBand(List<Line> g, int startCol, List<Placement> out, List<String> overflow) {
        int col = startCol;
        for (Line l : g) {
            if (col < maxCols) out.add(new Placement(colX(col), colY(col), originZ, l.token()));
            else if (overflow != null) overflow.add(l.token());
            col++;
        }
        return col;
    }

    private int nextBandStart(int col, boolean bands, int gap, boolean moreFollow) {
        if (!bands || !moreFollow) return col;             // -gap -1 = continuous flow
        return roundUpToLayer(col) + gap * colsPerLayer;   // fresh layer + gap blank layers
    }

    /**
     * Pack a type's lines into columns [startCol, endCol) BALANCING total length per column: each line,
     * longest first, goes to the currently-shortest column that can still hold it. With more columns
     * than lines this lands one-per-column (spread); when tight it stacks evenly. Lines stack south
     * within a column. Lines that don't fit anywhere go to overflow.
     */
    private void packBalanced(List<Line> g, int startCol, int endCol, List<Placement> out, List<String> overflow) {
        if (g.isEmpty()) return;
        int k = Math.max(1, Math.min(endCol, maxCols) - startCol);
        List<Line> sorted = new ArrayList<>(g);
        sorted.sort((a, b) -> Integer.compare(b.len(), a.len())); // longest first
        int[] used = new int[k]; // z consumed in each column (relative to originZ)
        for (Line l : sorted) {
            int best = -1;
            for (int j = 0; j < k; j++) {
                if (used[j] + l.len() <= codeLength && (best == -1 || used[j] < used[best])) best = j;
            }
            if (best == -1) { if (overflow != null) overflow.add(l.token()); continue; }
            int col = startCol + best;
            out.add(new Placement(colX(col), colY(col), originZ + used[best], l.token()));
            used[best] += l.len() + 2; // +2 leaves a gap below before the next line
        }
    }

    /**
     * -compact: fill each column to the plot's code length, then move to the next column - the smallest
     * possible footprint. Lines arrive longest-first (the caller's compact order) for tight bin-packing.
     */
    private void packTight(List<Line> g, List<Placement> out, List<String> overflow) {
        int col = 0, z = 0;
        for (Line l : g) {
            if (l.len() > codeLength) { if (overflow != null) overflow.add(l.token()); continue; } // too long for any column
            if (z > 0 && z + l.len() > codeLength) { col++; z = 0; }   // current column full -> next column
            if (col >= maxCols) { if (overflow != null) overflow.add(l.token()); continue; }
            out.add(new Placement(colX(col), colY(col), originZ + z, l.token()));
            z += l.len() + 2;
        }
    }
}
