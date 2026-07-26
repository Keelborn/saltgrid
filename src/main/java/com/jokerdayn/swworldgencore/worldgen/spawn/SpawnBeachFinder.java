package com.jokerdayn.swworldgencore.worldgen.spawn;

import static com.jokerdayn.swworldgencore.worldgen.terrain.IslandSettings.SPAWN_ISLAND_MAX_T;

import com.jokerdayn.swworldgencore.worldgen.noise.Hashing;
import com.jokerdayn.swworldgencore.worldgen.terrain.TerrainColumnSampler;
import com.jokerdayn.swworldgencore.worldgen.terrain.TerrainContext;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;

/**
 * Picks a broad, flat beach on the spawn island for a new player.
 *
 * <p>Works purely on the deterministic terrain field, so it can answer without loading a
 * single chunk. The caller is still responsible for loading the chunk and confirming that
 * decoration or player changes have not blocked the two blocks above the sand.</p>
 *
 * <p>The scan starts out in the open ocean and walks inward, which is what stops an inland
 * pond from being mistaken for the outer coast.</p>
 */
public final class SpawnBeachFinder {

    /** Radial directions tried, in a salt-dependent order. */
    private static final int SEARCH_DIRECTIONS = 128;

    /** The ray starts here, comfortably outside the island's feather ring. */
    private static final int SEARCH_OUTER_RADIUS = 520;

    /** ...and gives up here, well before the island interior. */
    private static final int SEARCH_INNER_RADIUS = 64;

    /** Coarse step, refined to single blocks once the coast is bracketed. */
    private static final int SEARCH_STEP = 4;

    /** Required run of beach columns walking inland from the waterline. */
    private static final int MIN_INLAND_DEPTH = 8;

    /** Required half-width of beach along the shore, measured tangentially. */
    private static final int TANGENT_HALF_WIDTH = 6;

    /** Accepted range for how far the player stands from the water. */
    private static final int MIN_OCEAN_DISTANCE = 1;
    private static final int MAX_OCEAN_DISTANCE = 3;

    private final TerrainContext terrain;

    public SpawnBeachFinder(TerrainContext terrain) {
        this.terrain = terrain;
    }

    /**
     * A generated, decoration-independent spawn target on the spawn island.
     *
     * @param feet          the player's feet position
     * @param oceanDistance how many blocks of sand separate it from open water
     */
    public record SpawnBeachPosition(BlockPos feet, int oceanDistance) {}

    /**
     * @param preferredOceanDistance clamped into {@code 1..3}
     * @param searchSalt             varies the direction order per player
     * @return a predicted beach spawn, or {@code null} if this terrain seed has no suitable
     *         coast at the requested distance
     */
    public SpawnBeachPosition find(int preferredOceanDistance, long searchSalt) {
        int oceanDistance =
            Mth.clamp(preferredOceanDistance, MIN_OCEAN_DISTANCE, MAX_OCEAN_DISTANCE);
        long mixedSalt = Hashing.mix64(searchSalt ^ terrain.seed());
        int firstDirection = Math.floorMod(
            (int) (mixedSalt ^ (mixedSalt >>> 32)),
            SEARCH_DIRECTIONS
        );
        int directionStep = (mixedSalt & 1L) == 0L ? 1 : -1;

        for (int attempt = 0; attempt < SEARCH_DIRECTIONS; attempt++) {
            int directionIndex = Math.floorMod(
                firstDirection + attempt * directionStep,
                SEARCH_DIRECTIONS
            );
            double angle = directionIndex * (Math.PI * 2.0 / SEARCH_DIRECTIONS);
            SpawnBeachPosition found = scanRay(
                Math.cos(angle), Math.sin(angle), oceanDistance
            );
            if (found != null) return found;
        }
        return null;
    }

    /** Walks one ray from open water towards the island and validates the first coast. */
    private SpawnBeachPosition scanRay(
        double outwardX,
        double outwardZ,
        int oceanDistance
    ) {
        int seaLevel = terrain.seaLevel;
        TerrainColumnSampler columns = terrain.columns;

        int previousRadius = SEARCH_OUTER_RADIUS;
        int previousX = (int) Math.round(outwardX * previousRadius);
        int previousZ = (int) Math.round(outwardZ * previousRadius);
        // The ray must begin in real water, otherwise this direction is already land.
        if (columns.floorAt(previousX, previousZ) >= seaLevel) return null;

        for (int radius = previousRadius - SEARCH_STEP;
             radius >= SEARCH_INNER_RADIUS;
             radius -= SEARCH_STEP) {
            int x = (int) Math.round(outwardX * radius);
            int z = (int) Math.round(outwardZ * radius);
            if (columns.floorAt(x, z) < seaLevel) {
                previousRadius = radius;
                previousX = x;
                previousZ = z;
                continue;
            }

            // Land found: refine the last coarse step to a single-block waterline.
            int coastX = x;
            int coastZ = z;
            for (int fine = previousRadius - 1; fine >= radius; fine--) {
                int fineX = (int) Math.round(outwardX * fine);
                int fineZ = (int) Math.round(outwardZ * fine);
                if (columns.floorAt(fineX, fineZ) >= seaLevel) {
                    coastX = fineX;
                    coastZ = fineZ;
                    break;
                }
                previousX = fineX;
                previousZ = fineZ;
            }

            int[] oceanDirection = adjacentOceanDirection(
                coastX, coastZ, outwardX, outwardZ, previousX, previousZ
            );
            if (oceanDirection == null) return null;

            int targetX = coastX - oceanDirection[0] * (oceanDistance - 1);
            int targetZ = coastZ - oceanDirection[1] * (oceanDistance - 1);
            if (columns.nearestOceanDistance(targetX, targetZ, MAX_OCEAN_DISTANCE)
                    != oceanDistance
                || !isPredictedSand(targetX, targetZ)
                || !isFlatColumn(targetX, targetZ)) {
                return null;
            }

            if (!isBroadBeach(coastX, coastZ, oceanDirection[0], oceanDirection[1])) {
                return null;
            }
            return new SpawnBeachPosition(
                new BlockPos(targetX, columns.floorAt(targetX, targetZ) + 1, targetZ),
                oceanDistance
            );
        }
        return null;
    }

    /**
     * Picks which neighbouring column is the open sea, preferring the direction the ray came
     * from so the player ends up facing the water they approached over.
     */
    private int[] adjacentOceanDirection(
        int coastX,
        int coastZ,
        double expectedOutwardX,
        double expectedOutwardZ,
        int previousOceanX,
        int previousOceanZ
    ) {
        int[] best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (int[] direction : TerrainColumnSampler.NEIGHBOUR_DIRS) {
            if (terrain.columns.floorAt(coastX + direction[0], coastZ + direction[1])
                    >= terrain.seaLevel) {
                continue;
            }
            double score =
                direction[0] * expectedOutwardX + direction[1] * expectedOutwardZ;
            if (coastX + direction[0] == previousOceanX
                && coastZ + direction[1] == previousOceanZ) {
                score += 0.25;
            }
            if (score > bestScore) {
                bestScore = score;
                best = direction;
            }
        }
        return best;
    }

    /** Whether the terrain field alone predicts sand here, on the spawn island. */
    public boolean isPredictedSand(int x, int z) {
        int floor = terrain.columns.floorAt(x, z);
        if (floor < terrain.seaLevel || floor > terrain.seaLevel + 5) return false;
        return terrain.spawnIsland.distanceTo(x, z) < SPAWN_ISLAND_MAX_T
            && terrain.columns.isBeach(x, z, floor);
    }

    /** Rejects a spawn on a one-block spit or on a step. */
    private boolean isFlatColumn(int x, int z) {
        int centerFloor = terrain.columns.floorAt(x, z);
        int landNeighbors = 0;
        for (int i = 0; i < 4; i++) {
            int[] direction = TerrainColumnSampler.NEIGHBOUR_DIRS[i];
            int neighborFloor =
                terrain.columns.floorAt(x + direction[0], z + direction[1]);
            if (neighborFloor < terrain.seaLevel) continue;
            landNeighbors++;
            if (Math.abs(neighborFloor - centerFloor) > 1) return false;
        }
        return landNeighbors >= 2;
    }

    /**
     * Requires a genuinely broad beach: a run of sand inland and a wide band along the
     * shore, both at a consistent height.
     */
    private boolean isBroadBeach(
        int coastX,
        int coastZ,
        int oceanDirectionX,
        int oceanDirectionZ
    ) {
        int coastFloor = terrain.columns.floorAt(coastX, coastZ);

        int inlandDepth = 0;
        for (int step = 0; step < MIN_INLAND_DEPTH; step++) {
            int x = coastX - oceanDirectionX * step;
            int z = coastZ - oceanDirectionZ * step;
            int floor = terrain.columns.floorAt(x, z);
            if (!isPredictedSand(x, z) || Math.abs(floor - coastFloor) > 2) break;
            inlandDepth++;
        }
        if (inlandDepth < MIN_INLAND_DEPTH) return false;

        int tangentX = -oceanDirectionZ;
        int tangentZ = oceanDirectionX;
        int centerX = coastX - oceanDirectionX * 2;
        int centerZ = coastZ - oceanDirectionZ * 2;
        int validColumns = 0;
        for (int step = -TANGENT_HALF_WIDTH; step <= TANGENT_HALF_WIDTH; step++) {
            int x = centerX + tangentX * step;
            int z = centerZ + tangentZ * step;
            int floor = terrain.columns.floorAt(x, z);
            if (isPredictedSand(x, z) && Math.abs(floor - coastFloor) <= 2) {
                validColumns++;
            }
        }
        return validColumns >= TANGENT_HALF_WIDTH * 2;
    }
}
