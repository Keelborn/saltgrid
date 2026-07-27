package com.jokerdayn.swworldgencore.worldgen.terrain;

import static com.jokerdayn.swworldgencore.worldgen.terrain.IslandSettings.SPAWN_ISLAND_MAX_T;

import com.jokerdayn.swworldgencore.worldgen.noise.TerrainNoise;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;

/**
 * One compact freshwater lake on every non-volcanic island.
 *
 * <p>The lake layout is a pure function of the world seed and owning island, so basins and
 * shores remain seamless across chunk borders. Each water footprint has an irregular oval
 * outline, a shallow three-block bowl, sparse gravel arcs and one connected 48-block clay
 * bed. Vanilla clay blocks drop four clay balls, making the guaranteed yield exactly
 * {@code 48 * 4 = 192} items, or three stacks.</p>
 */
public final class IslandLakeField {

    private static final double TWO_PI = Math.PI * 2.0;
    private static final double SHORE_LIMIT = 1.30;
    private static final int CLAY_BLOCKS_PER_LAKE = 48;

    private final TerrainNoise noise;
    private final SpawnIslandField spawnIsland;
    private final int seaLevel;
    private final int spawnLakeCenterX;
    private final int spawnLakeCenterZ;
    private final double spawnLakeRadiusX;
    private final double spawnLakeRadiusZ;
    private final int spawnLakeQuarterTurn;
    private final double spawnLakePhase;
    private final int spawnLakeWaterLevel;

    public IslandLakeField(
        TerrainNoise noise,
        SpawnIslandField spawnIsland,
        int seaLevel
    ) {
        this.noise = noise;
        this.spawnIsland = spawnIsland;
        this.seaLevel = seaLevel;
        this.spawnLakeRadiusX = 10.5 + noise.hsh(1213, -1217) * 2.0;
        this.spawnLakeRadiusZ = 8.5 + noise.hsh(1223, -1229) * 1.5;
        this.spawnLakeQuarterTurn = (int) (noise.hsh(1231, -1237) * 4.0) & 3;
        this.spawnLakePhase = noise.hsh(1249, -1259) * TWO_PI;
        int[] spawnCenter = findSpawnLakeCenter();
        this.spawnLakeCenterX = spawnCenter[0];
        this.spawnLakeCenterZ = spawnCenter[1];
        this.spawnLakeWaterLevel =
            deriveSpawnWaterLevel(spawnLakeCenterX, spawnLakeCenterZ);
    }

    /**
     * Applies the freshwater basin belonging to the current island, if any.
     *
     * @param spawnDistance already-sampled spawn-island distance for this column
     * @param grid          already-sampled grid-island state for this column
     * @param baseFloor     solid floor before freshwater shaping
     */
    public void shape(
        int x,
        int z,
        double spawnDistance,
        GridIslandSample grid,
        int baseFloor,
        IslandLakeSample out
    ) {
        out.clear(baseFloor);

        // The spawn island is independent of the grid and always receives one calm lake.
        if (spawnDistance < SPAWN_ISLAND_MAX_T) {
            if (insideBoundingSquare(x, z, spawnLakeCenterX, spawnLakeCenterZ, 20)) {
                if (shapeLake(
                    x, z, spawnLakeCenterX, spawnLakeCenterZ,
                    spawnLakeRadiusX, spawnLakeRadiusZ,
                    spawnLakeQuarterTurn, spawnLakePhase, spawnLakeWaterLevel,
                    baseFloor, out
                )) {
                    return;
                }
            }
        }

        // Active volcanoes already own a lava lake; every other grid island gets freshwater.
        if (!grid.hasIsland() || grid.volcano) return;
        long ownerHash = ownerHash(grid);
        int centerX = gridLakeCenterX(grid, ownerHash);
        int centerZ = gridLakeCenterZ(grid, ownerHash);
        if (!insideBoundingSquare(x, z, centerX, centerZ, 20)) return;
        shapeLake(
            x, z, centerX, centerZ,
            9.5 + fraction(ownerHash >> 24) * 2.5,
            8.0 + fraction(ownerHash >> 32) * 2.0,
            (int) ((ownerHash >>> 40) & 3L),
            fraction(ownerHash >> 44) * TWO_PI,
            grid.freshwaterLevel,
            baseFloor,
            out
        );
    }

    private boolean shapeLake(
        int x,
        int z,
        int centerX,
        int centerZ,
        double radiusX,
        double radiusZ,
        int quarterTurn,
        double phase,
        int waterLevel,
        int baseFloor,
        IslandLakeSample out
    ) {
        int dx = x - centerX;
        int dz = z - centerZ;
        int localX = rotateX(dx, dz, quarterTurn);
        int localZ = rotateZ(dx, dz, quarterTurn);

        double nx = localX / radiusX;
        double nz = localZ / radiusZ;
        double angle = Math.atan2(nz, nx);
        double outline =
            1.0 +
            Math.sin(angle * 3.0 + phase) * 0.075 +
            Math.sin(angle * 5.0 - phase * 0.7) * 0.040;
        double t = Math.sqrt(nx * nx + nz * nz) / outline;
        if (t > SHORE_LIMIT) return false;

        out.present = true;
        if (t <= 1.0) {
            out.water = true;
            out.waterLevel = waterLevel;

            // One block deep at the edge and three in the calm centre.
            double centre = TerrainNoise.smoothstepClamped((1.0 - t) / 0.72);
            int depth = 1 + (int) Math.round(centre * 2.0);
            out.floor = waterLevel - depth;

            // The compact central mask is wholly inside even the smallest possible lake.
            out.clay = isClayMask(localX, localZ);
            out.gravel = !out.clay && t > 0.62 && gravelArc(angle, phase, x, z);
            return true;
        }

        // A sealed, mostly grassy bank keeps the water readable even on a low island.
        int bankFloor = t <= 1.22 ? waterLevel : waterLevel - 1;
        out.floor = Math.max(baseFloor, bankFloor);
        out.gravel = t <= 1.20 && gravelArc(angle, phase, x, z);
        return true;
    }

    /**
     * Connected 48-column clay bed. Quarter-turning the whole lake varies its orientation
     * without changing the exact resource yield.
     */
    private static boolean isClayMask(int x, int z) {
        return switch (z) {
            case -3, 3 -> x >= -2 && x <= 2;       // 5 + 5
            case -2, 2 -> x >= -3 && x <= 3;       // 7 + 7
            case -1 -> x >= -4 && x <= 3;           // 8
            case 0 -> x >= -3 && x <= 4;            // 8
            case 1 -> x >= -4 && x <= 3;            // 8
            default -> false;
        };
    }

    private boolean gravelArc(double angle, double phase, int x, int z) {
        double arc = Math.sin(angle * 4.0 + phase * 1.7);
        double breakup = noise.fbm(
            x * 0.11 + 631.0 + noise.seedOff(231, 2.0),
            z * 0.11 - 419.0 + noise.seedOff(232, 2.0),
            2, 2.0, 0.5
        );
        return arc > 0.52 && breakup > 0.47;
    }

    /** Material override for a lake column, or {@code null} for its ordinary grassy bank. */
    public static BlockState surfaceOverride(IslandLakeSample lake) {
        return surfaceOverride(lake.water, lake.clay, lake.gravel);
    }

    /** Allocation-free material override for packed chunk-column lake flags. */
    public static BlockState surfaceOverride(boolean water, boolean clay, boolean gravel) {
        if (clay) return TerrainBlocks.CLAY;
        if (gravel) return TerrainBlocks.GRAVEL;
        if (water) return TerrainBlocks.SAND;
        return null;
    }

    // -------------------------------------------------------------------------
    // Stable per-island layouts
    // -------------------------------------------------------------------------

    int spawnLakeCenterX() {
        return spawnLakeCenterX;
    }

    int spawnLakeCenterZ() {
        return spawnLakeCenterZ;
    }

    /**
     * Chooses a low, comfortably inland site once per seed. The eight rim probes keep the
     * whole lake away from the ocean even when the spawn-island domain warp is extreme.
     */
    private int[] findSpawnLakeCenter() {
        double initialAngle = noise.hsh(1187, -1193) * TWO_PI;
        int bestX = (int) Math.round(Math.cos(initialAngle) * 35.0);
        int bestZ = (int) Math.round(Math.sin(initialAngle) * 35.0);
        double bestScore = Double.MAX_VALUE;

        for (int candidate = 0; candidate < 32; candidate++) {
            double angle = initialAngle + candidate * 2.399963229728653;
            double distance =
                102.0 + noise.hsh(1277 + candidate * 17, -1283 - candidate * 19) * 52.0;
            int x = (int) Math.round(Math.cos(angle) * distance);
            int z = (int) Math.round(Math.sin(angle) * distance);
            double centerT = spawnIsland.distanceTo(x, z);
            double maxT = centerT;
            for (int direction = 0; direction < 8; direction++) {
                double rimAngle = direction * Math.PI / 4.0;
                int rimX = x + (int) Math.round(Math.cos(rimAngle) * 22.0);
                int rimZ = z + (int) Math.round(Math.sin(rimAngle) * 22.0);
                maxT = Math.max(maxT, spawnIsland.distanceTo(rimX, rimZ));
            }
            if (maxT > 0.92) continue;

            double height = spawnIsland.heightAt(x, z, centerT);
            double score = Math.abs(centerT - 0.55) * 4.0 + height * 0.025;
            if (score >= bestScore) continue;
            bestScore = score;
            bestX = x;
            bestZ = z;
        }
        return new int[] { bestX, bestZ };
    }

    private int deriveSpawnWaterLevel(int centerX, int centerZ) {
        double t = spawnIsland.distanceTo(centerX, centerZ);
        double height = spawnIsland.heightAt(centerX, centerZ, t);
        return seaLevel + Mth.clamp((int) Math.round(height) - 2, 4, 14);
    }

    private long ownerHash(GridIslandSample grid) {
        return noise.rawHash(
            (int) Math.round(grid.centerX) * 37 + 127,
            (int) Math.round(grid.centerZ) * 41 - 131
        );
    }

    private int gridLakeCenterX(GridIslandSample grid, long hash) {
        double angle = fraction(hash >> 8) * TWO_PI;
        double distance = grid.islandRadius * (0.34 + fraction(hash >> 16) * 0.09);
        return (int) Math.round(grid.centerX + Math.cos(angle) * distance);
    }

    int gridLakeCenterX(GridIslandSample grid) {
        return gridLakeCenterX(grid, ownerHash(grid));
    }

    private int gridLakeCenterZ(GridIslandSample grid, long hash) {
        double angle = fraction(hash >> 8) * TWO_PI;
        double distance = grid.islandRadius * (0.34 + fraction(hash >> 16) * 0.09);
        return (int) Math.round(grid.centerZ + Math.sin(angle) * distance);
    }

    int gridLakeCenterZ(GridIslandSample grid) {
        return gridLakeCenterZ(grid, ownerHash(grid));
    }

    private static int rotateX(int x, int z, int quarterTurn) {
        return switch (quarterTurn & 3) {
            case 1 -> z;
            case 2 -> -x;
            case 3 -> -z;
            default -> x;
        };
    }

    private static int rotateZ(int x, int z, int quarterTurn) {
        return switch (quarterTurn & 3) {
            case 1 -> -x;
            case 2 -> -z;
            case 3 -> x;
            default -> z;
        };
    }

    private static double fraction(long value) {
        return (value & 0xFFFFL) / (double) 0x10000L;
    }

    private static boolean insideBoundingSquare(
        int x,
        int z,
        int centerX,
        int centerZ,
        int reach
    ) {
        return Math.abs(x - centerX) <= reach && Math.abs(z - centerZ) <= reach;
    }

    public static int clayBlocksPerLake() {
        return CLAY_BLOCKS_PER_LAKE;
    }
}
