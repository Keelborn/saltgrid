package com.jokerdayn.swworldgencore.worldgen.terrain;

import static com.jokerdayn.swworldgencore.worldgen.terrain.IslandSettings.VOLCANO_FLOW_COUNT;
import static com.jokerdayn.swworldgencore.worldgen.terrain.IslandSettings.VOLCANO_LAVA_ABOVE_SEA;
import static com.jokerdayn.swworldgencore.worldgen.terrain.IslandSettings.VOLCANO_LAVA_RADIUS;
import static com.jokerdayn.swworldgencore.worldgen.terrain.IslandSettings.VOLCANO_RIM_CLEARANCE;
import static com.jokerdayn.swworldgencore.worldgen.terrain.IslandSettings.VOLCANO_RIM_OUTER_RADIUS;

import com.jokerdayn.swworldgencore.worldgen.noise.Hashing;
import com.jokerdayn.swworldgencore.worldgen.noise.TerrainNoise;
import net.minecraft.util.Mth;

/**
 * Pure geometry of an active volcano, shared by terrain shaping, the surface
 * palette and the decoration passes.
 *
 * <p>Every function here depends only on world coordinates plus the volcano's own
 * centre and hash, never on chunk state — that is what keeps the caldera seal, the
 * lava tongues and the frozen flow scars seamless across chunk borders.</p>
 */
public final class VolcanoGeometry {

    private VolcanoGeometry() {}

    /** Stable hash of a volcano, derived from its centre only. */
    public static long volcanoHash(TerrainNoise noise, double centerX, double centerZ) {
        return noise.rawHash((int) centerX * 17, (int) centerZ * 19);
    }

    /**
     * Circular (un-warped) normalised distance from the volcano centre. The crater,
     * rim and caldera seal are all defined on this field rather than on the organic
     * outline, so the lava lake stays a readable bowl.
     */
    public static double craterT(int x, int z, GridIslandSample sample) {
        double dx = x - sample.centerX;
        double dz = z - sample.centerZ;
        return Math.sqrt(dx * dx + dz * dz) / sample.islandRadius;
    }

    /**
     * The solid belt between the lava lake and the outer flank. Inside it, noise may
     * only raise the terrain — never lower it below the safe height.
     */
    public static boolean isCalderaBarrier(double craterT) {
        return craterT >= VOLCANO_LAVA_RADIUS && craterT <= VOLCANO_RIM_OUTER_RADIUS;
    }

    /**
     * Clamps a column inside the barrier up to a guaranteed height.
     *
     * <p>Unlike a decorative peak this belt exists in every column of the full circle,
     * so not even the noise minimum can open a leak for the lava. The inner edge sits
     * only {@link IslandSettings#VOLCANO_RIM_CLEARANCE} blocks above the lava and the
     * outer edge blends into the natural slope, which keeps the tall centre of the
     * cone from degenerating into a cylinder.</p>
     */
    public static double sealedCalderaHeight(double craterT, double currentHeight) {
        if (!isCalderaBarrier(craterT)) return currentHeight;

        double middle = (VOLCANO_LAVA_RADIUS + VOLCANO_RIM_OUTER_RADIUS) * 0.5;
        double halfWidth = (VOLCANO_RIM_OUTER_RADIUS - VOLCANO_LAVA_RADIUS) * 0.5;
        double crown = Mth.clamp(
            1.0 - Math.abs(craterT - middle) / Math.max(0.001, halfWidth),
            0.0,
            1.0
        );
        double guaranteedHeight = VOLCANO_LAVA_ABOVE_SEA + VOLCANO_RIM_CLEARANCE + crown * 5.0;
        double outward = TerrainNoise.smoothstepClamped(
            (craterT - VOLCANO_LAVA_RADIUS) / (VOLCANO_RIM_OUTER_RADIUS - VOLCANO_LAVA_RADIUS)
        );
        return Math.max(guaranteedHeight, Mth.lerp(outward, guaranteedHeight, currentHeight));
    }

    /**
     * Angular distance to the nearest of the volcano's radial lava tongues.
     *
     * <p>The field depends only on world coordinates and the volcano centre, so it is
     * seamless between chunks.</p>
     *
     * @param nearestIndexOut optional single-element sink for the winning tongue index
     * @return angular distance in radians, at most {@code PI}
     */
    public static double flowDistance(
        int x,
        int z,
        double islandT,
        double centerX,
        double centerZ,
        long volcanoHash,
        int[] nearestIndexOut
    ) {
        double angle = Math.atan2(z - centerZ, x - centerX);
        double nearest = Math.PI;
        int nearestIndex = -1;
        for (int flow = 0; flow < VOLCANO_FLOW_COUNT; flow++) {
            // NOTE: arithmetic shifts above 40 leave sign-extension bits inside the
            // 24-bit window Hashing.frac reads. flow == 4 shifts by 47 and 43, so its
            // base angle and phase are degenerate. Kept verbatim because changing it
            // moves every volcano's flow scars for existing seeds.
            double baseAngle = Hashing.frac(volcanoHash >> (flow * 11 + 3)) * Math.PI * 2.0 - Math.PI;
            double phase = Hashing.frac(volcanoHash >> (flow * 9 + 7)) * Math.PI * 2.0;
            double meander =
                Math.sin(islandT * 15.0 + phase) * 0.040 +
                Math.sin(islandT * 31.0 - phase * 0.7) * 0.016;
            double delta = angularDistance(angle, baseAngle + meander);
            if (delta < nearest) {
                nearest = delta;
                nearestIndex = flow;
            }
        }
        if (nearestIndexOut != null) nearestIndexOut[0] = nearestIndex;
        return nearest;
    }

    /** Shortest absolute angle between two directions, wrapped into {@code [0,PI]}. */
    public static double angularDistance(double angle, double target) {
        double delta = angle - target;
        return Math.abs(Math.atan2(Math.sin(delta), Math.cos(delta)));
    }
}
