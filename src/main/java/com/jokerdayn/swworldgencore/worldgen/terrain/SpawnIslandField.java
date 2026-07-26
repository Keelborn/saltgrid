package com.jokerdayn.swworldgencore.worldgen.terrain;

import static com.jokerdayn.swworldgencore.worldgen.terrain.IslandSettings.SPAWN_ISLAND_MAX_HEIGHT;
import static com.jokerdayn.swworldgencore.worldgen.terrain.IslandSettings.SPAWN_ISLAND_MAX_T;
import static com.jokerdayn.swworldgencore.worldgen.terrain.IslandSettings.SPAWN_ISLAND_MOUNTAIN_HEIGHT;
import static com.jokerdayn.swworldgencore.worldgen.terrain.IslandSettings.SPAWN_ISLAND_RADIUS;
import static com.jokerdayn.swworldgencore.worldgen.terrain.IslandSettings.SPAWN_ISLAND_SKIP_DISTANCE;

import com.jokerdayn.swworldgencore.worldgen.noise.TerrainNoise;
import net.minecraft.util.Mth;

/**
 * The single hand-tuned landmass at the world origin.
 *
 * <p>Independent of the {@link GridIslandField} lattice: it has its own silhouette
 * (three octaves of domain warp applied to a circle), its own hill field and its own
 * ridged mountain spine.</p>
 */
public final class SpawnIslandField {

    private final TerrainNoise noise;

    public SpawnIslandField(TerrainNoise noise) {
        this.noise = noise;
    }

    /**
     * Normalised distance from the island: {@code 0} at the centre, {@code 1} at the
     * shoreline, {@link IslandSettings#SPAWN_ISLAND_MAX_T} once the feather ring ends.
     */
    public double distanceTo(int x, int z) {
        // The warp is bounded, so far-away columns provably saturate the clamp below.
        // Skipping them here removes 18 noise octaves per column for effectively the
        // whole world without changing a single generated block.
        long squared = (long) x * x + (long) z * z;
        if (squared >= (long) SPAWN_ISLAND_SKIP_DISTANCE * SPAWN_ISLAND_SKIP_DISTANCE) {
            return SPAWN_ISLAND_MAX_T;
        }

        double dist = Math.sqrt(squared);
        double wx1 = x + noise.fbm(
            x * 0.004 + 7.3 + noise.seedOff(41, 1.0),
            z * 0.004 + 2.1 + noise.seedOff(42, 0.7),
            4, 2.0, 0.55
        ) * 70;
        double wz1 = z + noise.fbm(
            x * 0.004 + 91.7 + noise.seedOff(43, 1.3),
            z * 0.004 + 53.4 + noise.seedOff(44, 0.5),
            4, 2.0, 0.55
        ) * 70;
        double wx2 = wx1 + noise.fbm(
            wx1 * 0.012 + 17.9 + noise.seedOff(45, 2.1),
            wz1 * 0.012 + 83.1 + noise.seedOff(46, 0.9),
            3, 2.0, 0.45
        ) * 30;
        double wz2 = wz1 + noise.fbm(
            wx1 * 0.012 + 44.2 + noise.seedOff(47, 0.6),
            wz1 * 0.012 + 11.6 + noise.seedOff(48, 1.7),
            3, 2.0, 0.45
        ) * 30;
        double wx3 = wx2 + noise.fbm(
            wx2 * 0.04 + 33.5 + noise.seedOff(49, 3.0),
            wz2 * 0.04 + 67.8 + noise.seedOff(50, 1.1),
            2, 2.0, 0.4
        ) * 10;
        double wz3 = wz2 + noise.fbm(
            wx2 * 0.04 + 5.5 + noise.seedOff(51, 0.8),
            wz2 * 0.04 + 99.2 + noise.seedOff(52, 2.5),
            2, 2.0, 0.4
        ) * 10;

        // Project the warp back onto the radial direction, so the silhouette wobbles
        // without the interior distance field folding over itself.
        double warpX = wx3 - x;
        double warpZ = wz3 - z;
        double warpShift = (warpX * x + warpZ * z) / (dist + 0.001);
        double warped = dist - warpShift - 65;
        return Mth.clamp(warped / SPAWN_ISLAND_RADIUS, 0.0, SPAWN_ISLAND_MAX_T);
    }

    /**
     * Land height above sea level for a column, given its {@link #distanceTo} value.
     * Returns {@code 0} outside the feather ring.
     */
    public double heightAt(int x, int z, double t) {
        if (t >= SPAWN_ISLAND_MAX_T) return 0.0;
        double edge = Mth.clamp(1.0 - t / SPAWN_ISLAND_MAX_T, 0.0, 1.0);
        double falloff = TerrainNoise.smoothstep(edge);
        if (falloff < 0.001) return 0.0;

        double rawHill = noise.fbm(
            x * 0.006 + 13.0 + noise.seedOff(61, 1.5),
            z * 0.006 + 77.0 + noise.seedOff(62, 0.8),
            3, 2.0, 0.55
        );
        double hillLarge = TerrainNoise.smoothstepClamped((rawHill - 0.2) / 0.7);
        double hillMid = noise.fbm(
            x * 0.018 + 55.0 + noise.seedOff(63, 2.0),
            z * 0.018 + 31.0 + noise.seedOff(64, 1.2),
            2, 2.0, 0.45
        ) * 0.3;
        double raw = (hillLarge + hillMid) * falloff * SPAWN_ISLAND_MAX_HEIGHT;

        double interior = TerrainNoise.smoothstepClamped(1.0 - t * 2.5);
        if (interior <= 0.01) return raw;

        // The mountain spine lives in a separately warped domain so its ridges do not
        // line up with the hill field underneath it.
        double wx = x + noise.fbm(
            x * 0.015 + 101.3 + noise.seedOff(65, 3.0),
            z * 0.015 + 57.9 + noise.seedOff(66, 1.5),
            3, 2.0, 0.5
        ) * 40;
        double wz = z + noise.fbm(
            x * 0.015 + 33.7 + noise.seedOff(67, 1.0),
            z * 0.015 + 88.2 + noise.seedOff(68, 2.2),
            3, 2.0, 0.5
        ) * 30;
        double ridge = Math.pow(
            noise.ridgeNoise(
                wx * 0.018 + 200.0 + noise.seedOff(69, 5.0),
                wz * 0.022 + 150.0 + noise.seedOff(70, 3.0),
                5, 2.0, 0.55
            ),
            0.6
        );
        double mountainDetail = noise.fbm(
            wx * 0.06 + 300.0 + noise.seedOff(71, 4.0),
            wz * 0.06 + 250.0 + noise.seedOff(72, 2.0),
            3, 2.0, 0.45
        ) * 0.3;
        return raw +
            SPAWN_ISLAND_MOUNTAIN_HEIGHT *
            (ridge * 0.75 + mountainDetail * 0.25) *
            interior *
            falloff;
    }

    /** True when the column is inside the spawn island's shoreline. */
    public boolean isInsideShoreline(int x, int z) {
        return distanceTo(x, z) <= 1.0;
    }
}
