package dev.dfonline.codeclient.dev;

import dev.dfonline.codeclient.CodeClient;
import dev.dfonline.codeclient.Feature;
import dev.dfonline.codeclient.action.impl.ScanPlot;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.util.math.BlockPos;

import java.util.List;

/**
 * /df debug missingchest: marks every chest/line a running scan still hasn't read with a bright particle, so you
 * can see at a glance what's left (e.g. when a -chests sweep can't reach something). Particles rather than block
 * outlines because the 1.21.11 world-line render API is gutted.
 * Data + toggle live in {@link ScanPlot}; this just draws what {@link ScanPlot#missingTargets()} returns.
 */
public class MissingChestRenderer extends Feature {
    private int counter = 0;

    @Override
    public void tick() {
        if (!ScanPlot.SHOW_MISSING || CodeClient.MC.world == null) return;
        if (counter++ % 6 != 0) return; // re-emit a few times a second (the dots are short-lived)
        List<BlockPos> targets = ScanPlot.missingTargets();
        for (BlockPos b : targets) {
            double x = b.getX() + 0.5, y = b.getY() + 0.5, z = b.getZ() + 0.5;
            // a short bright marker (green "todo" + a white cap) - stands out against the code-block clutter
            CodeClient.MC.world.addParticleClient(ParticleTypes.HAPPY_VILLAGER, x, y, z, 0, 0, 0);
            CodeClient.MC.world.addParticleClient(ParticleTypes.END_ROD, x, y + 1.0, z, 0, 0, 0);
        }
    }
}
