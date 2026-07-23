package com.dfvibe;

import net.minecraft.util.math.Vec3d;

/**
 * Movement-aware time estimate for scans / sweeps - far more accurate than the old "elapsed / lines grabbed"
 * ETA, which only started counting AFTER the first line was grabbed and ignored the (for eventless, dominant)
 * flight time. This models the actual cost of a scan:
 *
 *   TRAVEL to the codespace  +  SWEEPING its length  +  GRABBING each line/chest
 *
 * It's seeded INSTANTLY from geometry (the codespace box) and the mode's flight speed - so you get a real
 * "fly out ~12m, then ~20m to scan" number the moment you start, before a single chunk loads (the "when do I
 * come back" estimate) - and then refines live from the OBSERVED flight speed (eventless's real ~0.062 b/tick,
 * including holds/re-paths) and the observed per-line grab rate.
 *
 * All times are wall-clock. {@link #remainingMs()} is read by {@link Progress} for the action-bar "~X left".
 */
public final class Eta {
    private Eta() {}

    private static volatile boolean active = false;
    private static int[] box;                 // codespace box [x1,y1,z1,x2,y2,z2]
    private static boolean chests;            // -chests: each line also reads chests (extra per-line flights)
    private static double seedSpeed;          // blocks/sec seed (by movement mode), until we've observed real speed

    // observed flight speed
    private static Vec3d lastPos;
    private static int lastLinesDone;     // once we've grabbed any line we're scanning, not flying out
    private static long lastMs;
    private static double movedBlocks;
    private static double movedSeconds;

    // observed grab rate
    private static long startMs;

    // live result, read off-thread by Progress
    private static volatile long remainingMs = -1;

    /** Begin an estimate. {@code box} = the codespace, {@code seedSpeedBlocksPerSec} = the mode's flight speed
     *  (eventless ~1.1, hop much faster), {@code playerStart} = where the player is now (for the travel leg). */
    public static synchronized void begin(int[] box, boolean chests, double seedSpeedBlocksPerSec, Vec3d playerStart) {
        Eta.box = box;
        Eta.chests = chests;
        Eta.seedSpeed = Math.max(0.1, seedSpeedBlocksPerSec);
        lastPos = playerStart;
        lastMs = System.currentTimeMillis();
        startMs = lastMs;
        movedBlocks = 0;
        movedSeconds = 0;
        remainingMs = -1;
        active = true;
    }

    public static void end() { active = false; remainingMs = -1; }

    public static boolean isActive() { return active; }

    /** Observed flight speed (blocks/sec) once we have a couple seconds of movement, else the mode seed. */
    private static double speed() {
        if (movedSeconds > 2.0 && movedBlocks > 1.0) return Math.max(0.1, movedBlocks / movedSeconds);
        return seedSpeed;
    }

    /** Straight-line distance from {@code p} to the codespace box (0 if already inside it). */
    private static double distToBox(Vec3d p) {
        if (box == null) return 0;
        double x1 = Math.min(box[0], box[3]), x2 = Math.max(box[0], box[3]);
        double y1 = Math.min(box[1], box[4]), y2 = Math.max(box[1], box[4]);
        double z1 = Math.min(box[2], box[5]), z2 = Math.max(box[2], box[5]);
        double dx = p.x < x1 ? x1 - p.x : (p.x > x2 ? p.x - x2 : 0);
        double dy = p.y < y1 ? y1 - p.y : (p.y > y2 ? p.y - y2 : 0);
        double dz = p.z < z1 ? z1 - p.z : (p.z > z2 ? p.z - z2 : 0);
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** Path length to sweep the codespace once: down its length + over its layers, ×1.6 for -chests (the extra
     *  down-column flights to bring each line's chests into reach). One mid-height pass covers all layers
     *  (COVER_RADIUS), so this isn't length×layers - it's length + a vertical term. */
    private static double sweepBlocks() {
        if (box == null) return 0;
        double lenZ = Math.abs(box[5] - box[2]);
        double hY = Math.abs(box[4] - box[1]);
        return (lenZ + hY) * (chests ? 1.6 : 1.0);
    }

    /** Up-front, human-readable estimate to print the instant a scan starts (before any chunk loads). */
    public static String instantSummary(Vec3d p) {
        double sp = speed();
        long travelMs = (long) (distToBox(p) / sp * 1000);
        long sweepMs = (long) (sweepBlocks() / sp * 1000);
        StringBuilder s = new StringBuilder("ETA: ");
        if (travelMs > 3000) s.append("~").append(fmt(travelMs)).append(" to fly to the codespace, then ");
        s.append("~").append(fmt(sweepMs)).append(" to scan it");
        if (travelMs > 3000) s.append("  (~").append(fmt(travelMs + sweepMs)).append(" total)");
        s.append(" - refines as it runs.");
        return s.toString();
    }

    /** Feed the latest player position + line counts each scan tick (game thread). Updates the observed speed
     *  and recomputes the remaining estimate: travel-to-box (if still outside) + remaining sweep + grab time. */
    public static void sample(Vec3d p, int linesDone, int linesTotal) {
        if (!active || p == null) return;
        long now = System.currentTimeMillis();
        double dt = (now - lastMs) / 1000.0;
        if (lastPos != null && dt > 0.0) {
            double d = p.distanceTo(lastPos);
            if (d < 48) { movedBlocks += d; movedSeconds += dt; } // ignore teleport hops (HOP/relocate)
        }
        lastPos = p;
        lastMs = now;
        lastLinesDone = linesDone;

        double sp = speed();
        double travelRem = distToBox(p) / sp;                                  // still flying out to it
        double frac = linesTotal > 0 ? Math.min(1.0, (double) linesDone / linesTotal) : 0.0;
        double sweepRem = sweepBlocks() * (1.0 - frac) / sp;                   // distance left to fly over it
        double grabRem = 0;
        if (linesDone > 0) {                                                   // observed per-line grab cost
            double perLine = (now - startMs) / 1000.0 / linesDone;
            grabRem = perLine * Math.max(0, linesTotal - linesDone);
        }
        // Sweeping and grabbing happen together, so take the larger of the two (not their sum); travel is a
        // distinct leg that finishes before either, so it adds on top.
        remainingMs = (long) ((travelRem + Math.max(sweepRem, grabRem)) * 1000);
    }

    /** Remaining wall-clock ms, or -1 if not modelling yet. Read by Progress for the action-bar tail. */
    public static long remainingMs() { return active ? remainingMs : -1; }

    /** Which leg we're in, for the progress bar: "flying out" while still en route to the codespace, else
     *  "scanning". Empty if not modelling. */
    public static String phase() {
        if (!active || lastPos == null) return "";
        // Once any line has been grabbed we're scanning. Otherwise "flying out" only while genuinely far (you
        // hover ABOVE the lines while scanning, so a small distance to the box isn't "flying out").
        if (lastLinesDone > 0) return "scanning";
        return distToBox(lastPos) > 24 ? "flying out" : "scanning";
    }

    private static String fmt(long ms) {
        long s = Math.max(0, ms / 1000);
        if (s < 60) return s + "s";
        long m = s / 60, rs = s % 60;
        if (m < 60) return m + "m" + (rs > 0 ? rs + "s" : "");
        long h = m / 60, rm = m % 60;
        return h + "h" + (rm > 0 ? rm + "m" : "");
    }
}
