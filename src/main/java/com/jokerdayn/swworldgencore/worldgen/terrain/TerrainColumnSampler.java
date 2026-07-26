package com.jokerdayn.swworldgencore.worldgen.terrain;

import static com.jokerdayn.swworldgencore.worldgen.GenSettings.BASE_FLOOR;
import static com.jokerdayn.swworldgencore.worldgen.GenSettings.MAX_FLOOR;
import static com.jokerdayn.swworldgencore.worldgen.GenSettings.MIN_FLOOR;
import static com.jokerdayn.swworldgencore.worldgen.terrain.IslandSettings.SPAWN_ISLAND_MAX_T;

import com.jokerdayn.swworldgencore.diagnostics.Counter;
import com.jokerdayn.swworldgencore.diagnostics.GeneratorDiagnostics;
import com.jokerdayn.swworldgencore.worldgen.noise.Hashing;
import com.jokerdayn.swworldgencore.worldgen.noise.TerrainNoise;
import net.minecraft.util.Mth;

/**
 * Resolves the single most-queried quantity in the generator: how high is the solid floor
 * of a given column, and is that column a beach.
 *
 * <p>Both answers are pure functions of {@code (x, z, seed)} and both are memoised,
 * because shoreline detection, slope tests, boulder siting and the spawn search all probe
 * dozens of neighbouring columns each.</p>
 */
public final class TerrainColumnSampler {

    /** The eight neighbour directions, axis-aligned first. */
    public static final int[][] NEIGHBOUR_DIRS = {
        { 1, 0 },
        { -1, 0 },
        { 0, 1 },
        { 0, -1 },
        { 1, 1 },
        { 1, -1 },
        { -1, 1 },
        { -1, -1 },
    };

    /** Depth below sea level past which a column can no longer be a beach. */
    private static final int BEACH_MAX_HEIGHT_ABOVE_SEA = 5;

    private final TerrainNoise noise;
    private final SpawnIslandField spawnIsland;
    private final GridIslandField gridIslands;
    private final ColumnCaches caches;
    private final GeneratorDiagnostics diagnostics;
    private final int seaLevel;

    /**
     * Scratch owned solely by {@link #floorAt}. Never handed to a caller, so no other
     * code can observe it being overwritten by a nested sample.
     */
    private final ThreadLocal<GridIslandSample> floorScratch =
        ThreadLocal.withInitial(GridIslandSample::new);

    public TerrainColumnSampler(
        TerrainNoise noise,
        SpawnIslandField spawnIsland,
        GridIslandField gridIslands,
        ColumnCaches caches,
        GeneratorDiagnostics diagnostics,
        int seaLevel
    ) {
        this.noise = noise;
        this.spawnIsland = spawnIsland;
        this.gridIslands = gridIslands;
        this.caches = caches;
        this.diagnostics = diagnostics;
        this.seaLevel = seaLevel;
    }

    public int seaLevel() {
        return seaLevel;
    }

    // -------------------------------------------------------------------------
    // Floor height
    // -------------------------------------------------------------------------

    /**
     * Blends the open-ocean floor with whichever island covers the column.
     *
     * @param spawnDistance {@link SpawnIslandField#distanceTo} for this column
     * @param spawnHeight   {@link SpawnIslandField#heightAt} for this column
     * @param gridHeight    {@link GridIslandSample#height} for this column
     */
    public int computeFloor(
        int x,
        int z,
        double spawnDistance,
        double spawnHeight,
        double gridHeight
    ) {
        // Warping the sea-floor domain keeps the dune fields from looking like a grid.
        double wx = x + noise.fbm(
            x * 0.005 + noise.seedOff(81, 1.0),
            z * 0.005 + noise.seedOff(82, 0.5),
            2, 2.0, 0.5
        ) * 50;
        double wz = z + noise.fbm(
            x * 0.005 + 31.7 + noise.seedOff(83, 0.8),
            z * 0.005 + 47.3 + noise.seedOff(84, 1.2),
            2, 2.0, 0.5
        ) * 50;
        double h = BASE_FLOOR +
            noise.fbm(
                wx * 0.004 + noise.seedOff(85, 2.0),
                wz * 0.004 + noise.seedOff(86, 1.5),
                4, 2.0, 0.55
            ) * 30 +
            noise.fbm(
                wx * 0.016 + noise.seedOff(87, 0.7),
                wz * 0.016 + noise.seedOff(88, 0.9),
                2, 2.0, 0.45
            ) * 4 +
            noise.fbm(
                wx * 0.05 + noise.seedOff(89, 3.0),
                wz * 0.05 + noise.seedOff(90, 2.0),
                2, 2.0, 0.4
            ) * 1.5;

        // The bare sea floor always stays submerged; only islands lift land above water.
        int oceanFloor = Math.max(MIN_FLOOR, Math.min((int) h, seaLevel - 4));

        if (spawnDistance < SPAWN_ISLAND_MAX_T) {
            int islandFloor = Math.max(oceanFloor, seaLevel + (int) Math.round(spawnHeight));
            if (spawnDistance <= 1.0) return clampFloor(islandFloor);
            // Inside the feather ring: fade the island back down into the sea floor.
            double norm = (spawnDistance - 1.0) / (SPAWN_ISLAND_MAX_T - 1.0);
            double blend = TerrainNoise.smoothstep(norm);
            return clampFloor(
                (int) Math.round(islandFloor + (oceanFloor - islandFloor) * blend)
            );
        }

        if (gridHeight > 0.01) {
            int gridFloor = Math.max(oceanFloor, seaLevel + (int) Math.round(gridHeight));
            // Rises out of the water gradually over the first three blocks of land height,
            // which is what produces a shallow shelf instead of a wall at the shoreline.
            double blend = TerrainNoise.smoothstepClamped(gridHeight / 3.0);
            return clampFloor((int) Math.round(oceanFloor + (gridFloor - oceanFloor) * blend));
        }

        return clampFloor(oceanFloor);
    }

    public static int clampFloor(int floor) {
        return Mth.clamp(floor, MIN_FLOOR, MAX_FLOOR);
    }

    /** Memoised {@link #computeFloor} — the generator's hottest query. */
    public int floorAt(int x, int z) {
        long key = Hashing.columnKey(x, z);
        long seed = noise.seed();
        int cached = caches.lookupFloor(key, seed);
        if (cached != ColumnCaches.FLOOR_MISS) return cached;

        double spawnDistance = spawnIsland.distanceTo(x, z);
        double spawnHeight = spawnIsland.heightAt(x, z, spawnDistance);
        GridIslandSample sample = floorScratch.get();
        gridIslands.sample(x, z, sample);
        int floor = computeFloor(x, z, spawnDistance, spawnHeight, sample.height);

        caches.storeFloor(key, floor, seed);
        return floor;
    }

    // -------------------------------------------------------------------------
    // Beaches
    // -------------------------------------------------------------------------

    /** Beach width in blocks, drifting smoothly along the coast between 12 and 40. */
    public double beachWidthAt(int x, int z) {
        return 12.0 + noise.fbm(
            x * 0.015 + noise.seedOff(120, 2.0),
            z * 0.015 + noise.seedOff(121, 1.5),
            3, 2.0, 0.5
        ) * 28.0;
    }

    /**
     * A beach is simply a low column close to real water.
     *
     * <p>Eight rays are scanned; if any finds a column below sea level within the local
     * beach width, this is beach. A column right at the waterline always finds water at
     * distance 1, so the sand is guaranteed to reach the ocean without gaps. Height is
     * only used to strip sand off coastal cliffs.</p>
     */
    public boolean isBeach(int x, int z, int floor) {
        if (floor < seaLevel || floor > seaLevel + BEACH_MAX_HEIGHT_ABOVE_SEA) return false;

        long key = Hashing.columnKey(x, z);
        long seed = noise.seed();
        int cached = caches.lookupBeach(key, seed);
        if (cached != ColumnCaches.BEACH_UNKNOWN) return cached == 1;

        int width = (int) beachWidthAt(x, z);
        int farthest = 1;
        for (int d = 1; d <= width; d += d < 4 ? 1 : 3) farthest = d;

        boolean beach = false;
        int floorSamples = 0;
        search:
        for (int[] dir : NEIGHBOUR_DIRS) {
            // Most eligible low columns are close to the coast, so probing the far edge
            // first proves "water within width" with a single lookup in the common case.
            // The dense near scan stays as a fallback for coves and narrow channels.
            floorSamples++;
            if (floorAt(x + dir[0] * farthest, z + dir[1] * farthest) < seaLevel) {
                beach = true;
                break;
            }
            for (int d = 1; d <= width; d += d < 4 ? 1 : 3) {
                if (d == farthest) continue;
                floorSamples++;
                if (floorAt(x + dir[0] * d, z + dir[1] * d) < seaLevel) {
                    beach = true;
                    break search;
                }
            }
        }

        diagnostics.add(Counter.BEACH_SEARCHES, 1L);
        diagnostics.add(Counter.BEACH_FLOOR_SAMPLES, floorSamples);
        caches.storeBeach(key, beach, seed);
        return beach;
    }

    /**
     * Chebyshev distance to the nearest submerged column, or {@code -1} if none is within
     * {@code maxDistance}.
     */
    public int nearestOceanDistance(int x, int z, int maxDistance) {
        for (int distance = 1; distance <= maxDistance; distance++) {
            for (int dx = -distance; dx <= distance; dx++) {
                for (int dz = -distance; dz <= distance; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != distance) continue;
                    if (floorAt(x + dx, z + dz) < seaLevel) return distance;
                }
            }
        }
        return -1;
    }
}
