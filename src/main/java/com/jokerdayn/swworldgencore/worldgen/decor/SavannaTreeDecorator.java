package com.jokerdayn.swworldgencore.worldgen.decor;

import static com.jokerdayn.swworldgencore.worldgen.decor.DecorSettings.SAVANNA_GROVE_THRESHOLD;
import static com.jokerdayn.swworldgencore.worldgen.decor.DecorSettings.SAVANNA_MAX_SLOPE;
import static com.jokerdayn.swworldgencore.worldgen.decor.DecorSettings.SAVANNA_SLOPE_SAMPLES;
import static com.jokerdayn.swworldgencore.worldgen.decor.DecorSettings.SAVANNA_TREE_CELL;

import com.jokerdayn.swworldgencore.diagnostics.Counter;
import com.jokerdayn.swworldgencore.diagnostics.GeneratorDiagnostics;
import com.jokerdayn.swworldgencore.diagnostics.Token;
import com.jokerdayn.swworldgencore.worldgen.AcaciaGenerator;
import com.jokerdayn.swworldgencore.worldgen.noise.Hashing;
import com.jokerdayn.swworldgencore.worldgen.terrain.BiomeCategory;
import com.jokerdayn.swworldgencore.worldgen.terrain.TerrainContext;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;

/**
 * Acacia groves on the savanna.
 *
 * <p>Candidates sit on a fixed {@link DecorSettings#SAVANNA_TREE_CELL} lattice so a tree is
 * always derived from its own cell rather than from the chunk being decorated. A
 * low-frequency noise field then gathers them into expressive clumps with open clearings
 * between, which a per-column probability could not produce.</p>
 */
public final class SavannaTreeDecorator {

    private final TerrainContext terrain;
    private final GeneratorDiagnostics diagnostics;

    public SavannaTreeDecorator(TerrainContext terrain, GeneratorDiagnostics diagnostics) {
        this.terrain = terrain;
        this.diagnostics = diagnostics;
    }

    public void decorate(WorldGenLevel level, int chunkX, int chunkZ, Token benchmark) {
        int minX = chunkX * 16;
        int minZ = chunkZ * 16;
        int maxX = minX + 15;
        int maxZ = minZ + 15;
        int minCellX = Math.floorDiv(minX, SAVANNA_TREE_CELL);
        int maxCellX = Math.floorDiv(maxX, SAVANNA_TREE_CELL);
        int minCellZ = Math.floorDiv(minZ, SAVANNA_TREE_CELL);
        int maxCellZ = Math.floorDiv(maxZ, SAVANNA_TREE_CELL);

        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        int attempts = 0;
        int placed = 0;

        for (int cellX = minCellX; cellX <= maxCellX; cellX++) {
            for (int cellZ = minCellZ; cellZ <= maxCellZ; cellZ++) {
                long candidateHash = terrain.noise.rawHash(cellX * 92821, cellZ * 68917);
                int wx = cellX * SAVANNA_TREE_CELL
                    + (int) (Hashing.frac(candidateHash) * SAVANNA_TREE_CELL);
                int wz = cellZ * SAVANNA_TREE_CELL
                    + (int) (Hashing.frac(candidateHash >> 20) * SAVANNA_TREE_CELL);
                if (wx < minX || wx > maxX || wz < minZ || wz > maxZ) continue;
                if (terrain.biomes.classify(wx, wz) != BiomeCategory.SAVANNA) continue;

                int floor = terrain.columns.floorAt(wx, wz);
                if (floor <= terrain.seaLevel) continue;
                if (terrain.columns.isBeach(wx, wz, floor)) continue;
                cursor.set(wx, floor, wz);
                if (!level.getBlockState(cursor).is(Blocks.GRASS_BLOCK)) continue;

                int slope = localSlope(wx, wz, floor);
                if (slope > SAVANNA_MAX_SLOPE) continue;

                double grove = terrain.noise.fbm(
                    wx * 0.012 + 311.0, wz * 0.012 - 173.0, 3, 2.0, 0.5
                );
                // Higher ground gets a small bonus so hillsides are not bare.
                double mountainBonus =
                    Mth.clamp((floor - terrain.seaLevel - 8) / 28.0, 0.0, 0.16);
                if (grove + mountainBonus < SAVANNA_GROVE_THRESHOLD) continue;

                // Natural gaps remain inside a grove.
                double density = Mth.clamp(
                    (grove - SAVANNA_GROVE_THRESHOLD) * 2.2 + 0.58 + mountainBonus,
                    0.0,
                    0.92
                );
                if (Hashing.frac(candidateHash >> 40) > density) continue;

                boolean preferSmall = slope >= 2 || floor > terrain.seaLevel + 28;
                attempts++;
                AcaciaGenerator.PlacementResult result = AcaciaGenerator.tryPlaceDetailed(
                    level, wx, floor + 1, wz,
                    Hashing.frac(candidateHash ^ terrain.seed()),
                    preferSmall
                );
                TreePlacements.recordAcacia(diagnostics, benchmark, result);
                if (result.placed()) placed++;
            }
        }

        diagnostics.add(benchmark, Counter.ACACIA_ATTEMPTS, attempts);
        diagnostics.add(benchmark, Counter.ACACIAS_PLACED, placed);
    }

    private int localSlope(int x, int z, int floor) {
        int min = floor;
        int max = floor;
        for (int[] offset : SAVANNA_SLOPE_SAMPLES) {
            int sample = terrain.columns.floorAt(x + offset[0], z + offset[1]);
            min = Math.min(min, sample);
            max = Math.max(max, sample);
        }
        return max - min;
    }
}
