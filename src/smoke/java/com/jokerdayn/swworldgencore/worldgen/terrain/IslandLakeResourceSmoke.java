package com.jokerdayn.swworldgencore.worldgen.terrain;

import com.jokerdayn.swworldgencore.diagnostics.GeneratorDiagnostics;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.fml.loading.LoadingModList;

/** Resource and shape invariants for freshwater lakes and broad ocean clay deposits. */
public final class IslandLakeResourceSmoke {

    private static final long[] SEEDS = {
        1L,
        -7_493_821_045L,
        5_916_308_533_714_060_029L,
        88_172_311L,
    };
    private static final int SEA_LEVEL = 63;
    private static final int CLAY_ITEMS_PER_BLOCK = 4;
    private static final int STACK_SIZE = 64;

    private IslandLakeResourceSmoke() {}

    public static void main(String[] args) {
        LoadingModList.of(List.of(), List.of(), List.of(), List.of(), Map.of());
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();

        int verifiedLakes = 0;
        for (long seed : SEEDS) {
            TerrainContext terrain =
                new TerrainContext(seed, SEA_LEVEL, new GeneratorDiagnostics());

            int spawnLakeX = terrain.lakes.spawnLakeCenterX();
            int spawnLakeZ = terrain.lakes.spawnLakeCenterZ();
            require(
                terrain.spawnIsland.distanceTo(spawnLakeX, spawnLakeZ) <= 1.0,
                "spawn seed=" + seed + " lake centre fell outside the island shoreline"
            );
            verifyLake(terrain, spawnLakeX, spawnLakeZ, "spawn seed=" + seed);
            verifiedLakes++;

            GridIslandSample owner = findOrdinaryGridIsland(terrain);
            verifyLake(
                terrain,
                terrain.lakes.gridLakeCenterX(owner),
                terrain.lakes.gridLakeCenterZ(owner),
                "grid seed=" + seed
            );
            verifiedLakes++;

            verifyOceanClayDeposit(terrain, seed);
        }

        System.out.println(
            "Island lake resource smoke test passed: " + verifiedLakes
                + " lakes, exactly " + IslandLakeField.clayBlocksPerLake()
                + " clay blocks / " + (IslandLakeField.clayBlocksPerLake()
                    * CLAY_ITEMS_PER_BLOCK / STACK_SIZE)
                + " stacks each; broad ocean deposits are connected"
        );
    }

    private static GridIslandSample findOrdinaryGridIsland(TerrainContext terrain) {
        GridIslandSample sample = new GridIslandSample();
        for (int ring = 0; ring <= 6; ring++) {
            for (int cellX = -ring; cellX <= ring; cellX++) {
                for (int cellZ = -ring; cellZ <= ring; cellZ++) {
                    if (ring > 0 && Math.abs(cellX) != ring && Math.abs(cellZ) != ring) continue;
                    if (!terrain.gridIslands.hasIsland(cellX, cellZ)) continue;
                    int centerX = terrain.gridIslands.islandCenterX(cellX, cellZ);
                    int centerZ = terrain.gridIslands.islandCenterZ(cellX, cellZ);
                    if (terrain.gridIslands.isVolcano(cellX, cellZ, centerX, centerZ)) continue;
                    terrain.gridIslands.sample(centerX, centerZ, sample);
                    if (sample.hasIsland() && !sample.volcano) return sample;
                }
            }
        }
        throw new AssertionError("No ordinary grid island found within six cells");
    }

    private static void verifyLake(
        TerrainContext terrain,
        int centerX,
        int centerZ,
        String label
    ) {
        GridIslandSample grid = new GridIslandSample();
        IslandLakeSample lake = new IslandLakeSample();
        int waterColumns = 0;
        int clayColumns = 0;
        int gravelColumns = 0;
        int shoreSandColumns = 0;
        int deepest = 0;
        int surfaceLevel = 0;
        boolean[][] water = new boolean[41][41];
        int[][] floors = new int[41][41];
        int[][] waterLevels = new int[41][41];

        for (int x = centerX - 20; x <= centerX + 20; x++) {
            for (int z = centerZ - 20; z <= centerZ + 20; z++) {
                int localX = x - centerX + 20;
                int localZ = z - centerZ + 20;
                double spawnDistance = terrain.spawnIsland.distanceTo(x, z);
                double spawnHeight = terrain.spawnIsland.heightAt(x, z, spawnDistance);
                terrain.gridIslands.sample(x, z, grid);
                int floor = terrain.columns.computeFloor(
                    x, z, spawnDistance, spawnHeight, grid, lake
                );
                floors[localX][localZ] = floor;
                water[localX][localZ] = lake.water;
                waterLevels[localX][localZ] = lake.waterLevel;

                if (!lake.water) {
                    if (lake.present && lake.sand) shoreSandColumns++;
                    continue;
                }
                waterColumns++;
                require(floor < lake.waterLevel, label + " has a dry water column");
                deepest = Math.max(deepest, lake.waterLevel - floor);
                surfaceLevel = lake.waterLevel;
                if (lake.clay) {
                    clayColumns++;
                    require(
                        IslandLakeField.surfaceOverride(lake).is(Blocks.CLAY),
                        label + " clay flag did not produce a clay surface"
                    );
                }
                if (lake.gravel) gravelColumns++;
            }
        }

        require(
            clayColumns == IslandLakeField.clayBlocksPerLake(),
            label + " has " + clayColumns + " clay blocks instead of "
                + IslandLakeField.clayBlocksPerLake()
        );
        require(
            clayColumns * CLAY_ITEMS_PER_BLOCK == 3 * STACK_SIZE,
            label + " does not yield exactly three stacks of clay items"
        );
        require(
            waterColumns >= 220 && waterColumns <= 460,
            label + " water footprint is not compact: " + waterColumns + " columns"
        );
        require(gravelColumns > 0, label + " has no gravel accents");
        require(shoreSandColumns >= 20, label + " has no sandy shore: " + shoreSandColumns);

        // A low island cannot afford the full bowl without cutting under the sea floor, so
        // the expected depth follows the same budget the shaper uses.
        int expectedDepth = Math.min(3, Math.max(2, surfaceLevel - SEA_LEVEL - 1));
        require(
            deepest >= expectedDepth,
            label + " bed never gets deeper than " + deepest
        );

        // The rim must be both sealed and *flush*: a solid ring one or two blocks above the
        // water is what used to make every lake look sunk into its own island.
        int[][] directions = { { 1, 0 }, { -1, 0 }, { 0, 1 }, { 0, -1 } };
        for (int x = 1; x < 40; x++) {
            for (int z = 1; z < 40; z++) {
                if (!water[x][z]) continue;
                for (int[] direction : directions) {
                    int nextX = x + direction[0];
                    int nextZ = z + direction[1];
                    if (water[nextX][nextZ]) continue;
                    require(
                        floors[nextX][nextZ] == waterLevels[x][z],
                        label + " shore is not flush with the water: floor "
                            + floors[nextX][nextZ] + " beside water level "
                            + waterLevels[x][z]
                    );
                }
            }
        }
    }

    private static void verifyOceanClayDeposit(TerrainContext terrain, long seed) {
        final int cellSize = 80;
        for (int cellX = -5; cellX <= 5; cellX++) {
            for (int cellZ = -5; cellZ <= 5; cellZ++) {
                int minX = cellX * cellSize;
                int minZ = cellZ * cellSize;
                boolean[][] clay = new boolean[cellSize][cellSize];
                int count = 0;
                int startX = -1;
                int startZ = -1;

                for (int localX = 0; localX < cellSize; localX++) {
                    for (int localZ = 0; localZ < cellSize; localZ++) {
                        if (!terrain.surface.isOceanClayDeposit(
                            minX + localX, minZ + localZ
                        )) {
                            continue;
                        }
                        clay[localX][localZ] = true;
                        count++;
                        startX = localX;
                        startZ = localZ;
                    }
                }
                if (count == 0) continue;

                require(
                    count >= 90,
                    "seed=" + seed + " ocean clay deposit is too small: " + count
                );
                int connected = connectedCount(clay, startX, startZ);
                require(
                    connected == count,
                    "seed=" + seed + " ocean clay deposit split into fragments: "
                        + connected + "/" + count
                );
                return;
            }
        }
        throw new AssertionError("seed=" + seed + " has no ocean clay deposit near origin");
    }

    private static int connectedCount(boolean[][] cells, int startX, int startZ) {
        boolean[][] visited = new boolean[cells.length][cells[0].length];
        ArrayDeque<int[]> queue = new ArrayDeque<>();
        queue.add(new int[] { startX, startZ });
        visited[startX][startZ] = true;
        int count = 0;
        int[][] directions = { { 1, 0 }, { -1, 0 }, { 0, 1 }, { 0, -1 } };

        while (!queue.isEmpty()) {
            int[] current = queue.removeFirst();
            count++;
            for (int[] direction : directions) {
                int x = current[0] + direction[0];
                int z = current[1] + direction[1];
                if (x < 0 || z < 0 || x >= cells.length || z >= cells[0].length) continue;
                if (!cells[x][z] || visited[x][z]) continue;
                visited[x][z] = true;
                queue.addLast(new int[] { x, z });
            }
        }
        return count;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
