package com.jokerdayn.swworldgencore.worldgen.decor;

import com.jokerdayn.swworldgencore.diagnostics.Counter;
import com.jokerdayn.swworldgencore.diagnostics.GeneratorDiagnostics;
import com.jokerdayn.swworldgencore.diagnostics.Token;
import com.jokerdayn.swworldgencore.worldgen.AcaciaGenerator;
import com.jokerdayn.swworldgencore.worldgen.PalmGenerator;
import com.jokerdayn.swworldgencore.worldgen.chunk.ChunkColumnCache;
import com.jokerdayn.swworldgencore.worldgen.chunk.ColumnFlags;
import com.jokerdayn.swworldgencore.worldgen.terrain.GridIslandSample;
import com.jokerdayn.swworldgencore.worldgen.terrain.TerrainContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The living part of a volcano: the green belt around its foot.
 *
 * <p>Grass, ferns and the occasional flower and tree on the vegetated hem, plus dead bushes
 * on the trampled-earth patches where the green fades into the ash fields. Nothing is placed
 * on the black lava flows, so the contrast between living island and fresh basalt stays
 * sharp.</p>
 */
public final class VolcanicBiomeDecorator {

    /** Rarity roll below which a column is considered for a tree. */
    private static final double TREE_PICK_CHANCE = 0.0014;

    private final TerrainContext terrain;
    private final GeneratorDiagnostics diagnostics;

    public VolcanicBiomeDecorator(TerrainContext terrain, GeneratorDiagnostics diagnostics) {
        this.terrain = terrain;
        this.diagnostics = diagnostics;
    }

    public void decorate(
        WorldGenLevel level,
        int chunkX,
        int chunkZ,
        ChunkColumnCache columns,
        Token benchmark
    ) {
        int minX = chunkX * 16;
        int minZ = chunkZ * 16;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        GridIslandSample sample = new GridIslandSample();

        int palmAttempts = 0;
        int palmsPlaced = 0;
        int acaciaAttempts = 0;
        int acaciasPlaced = 0;
        int featureWrites = 0;

        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int x = minX + lx;
                int z = minZ + lz;
                int columnIndex = ChunkColumnCache.index(lx, lz);
                if (columns != null
                    && !ColumnFlags.has(columns.flags[columnIndex], ColumnFlags.VOLCANO)) {
                    continue;
                }
                terrain.gridIslands.sample(x, z, sample);
                // Crater columns belong to the active-feature pass, not to this one.
                if (!sample.volcano || sample.crater) continue;

                double t = sample.normalizedDistance;
                int floor = columns != null
                    ? columns.floor[columnIndex]
                    : terrain.columns.floorAt(x, z);
                if (floor < terrain.seaLevel) continue;

                cursor.set(x, floor, z);
                BlockState ground = level.getBlockState(cursor);
                double pick = terrain.noise.hsh(x * 197 + floor, z * 199 - floor);

                if (pick < TREE_PICK_CHANCE) {
                    // The grove fields are only consulted for tree candidates, so they stay
                    // out of the per-column cost of the other 99.9% of the belt.
                    double grove = terrain.noise.fbm(
                        x * 0.010 + 307.0, z * 0.010 - 613.0, 3, 2.0, 0.52
                    );
                    double groveEdge = terrain.noise.fbm(
                        x * 0.027 - 173.0, z * 0.027 + 419.0, 2, 2.0, 0.5
                    );
                    boolean palmCandidate = t > 0.87 && t < 0.94 && grove > 0.61
                        && (ground.is(Blocks.SAND) || ground.is(Blocks.GRASS_BLOCK));
                    boolean acaciaCandidate = t > 0.73 && t < 0.86 && grove > 0.68
                        && groveEdge > 0.46 && ground.is(Blocks.GRASS_BLOCK);

                    if ((palmCandidate || acaciaCandidate) && isStableGround(x, z, floor)) {
                        if (palmCandidate) {
                            palmAttempts++;
                            PalmGenerator.PlacementResult result =
                                PalmGenerator.tryPlacePalmDetailed(
                                    level, x, floor + 1, z,
                                    terrain.noise.hsh(x * 211, z * 223)
                                );
                            TreePlacements.recordPalm(diagnostics, benchmark, result);
                            if (result.placed()) {
                                palmsPlaced++;
                                continue;
                            }
                        }
                        if (acaciaCandidate) {
                            acaciaAttempts++;
                            AcaciaGenerator.PlacementResult result =
                                AcaciaGenerator.tryPlaceDetailed(
                                    level, x, floor + 1, z,
                                    terrain.noise.hsh(x * 227, z * 229),
                                    false
                                );
                            TreePlacements.recordAcacia(diagnostics, benchmark, result);
                            if (result.placed()) {
                                acaciasPlaced++;
                                continue;
                            }
                        }
                    }
                }

                cursor.set(x, floor + 1, z);
                if (!level.getBlockState(cursor).isAir()) continue;

                if (t > 0.64 && t < 0.94 && ground.is(Blocks.GRASS_BLOCK) && pick < 0.16) {
                    level.setBlock(cursor, groundPlant(x, z), 2);
                    featureWrites++;
                    continue;
                }

                // Dry bushes on the trampled-earth patches: the transition from green to
                // ash fields reads as naturally scorched.
                if (t > 0.55 && ground.is(Blocks.COARSE_DIRT) && pick < 0.06) {
                    level.setBlock(cursor, Blocks.DEAD_BUSH.defaultBlockState(), 2);
                    featureWrites++;
                }
            }
        }

        diagnostics.add(benchmark, Counter.PALM_ATTEMPTS, palmAttempts);
        diagnostics.add(benchmark, Counter.PALMS_PLACED, palmsPlaced);
        diagnostics.add(benchmark, Counter.ACACIA_ATTEMPTS, acaciaAttempts);
        diagnostics.add(benchmark, Counter.ACACIAS_PLACED, acaciasPlaced);
        diagnostics.add(benchmark, Counter.VOLCANIC_FEATURE_WRITES, featureWrites);
    }

    /** Rejects tree sites on steps and cliff edges. */
    private boolean isStableGround(int x, int z, int floor) {
        return Math.abs(terrain.columns.floorAt(x + 3, z) - floor) <= 2
            && Math.abs(terrain.columns.floorAt(x - 3, z) - floor) <= 2
            && Math.abs(terrain.columns.floorAt(x, z + 3) - floor) <= 2
            && Math.abs(terrain.columns.floorAt(x, z - 3) - floor) <= 2;
    }

    private BlockState groundPlant(int x, int z) {
        double roll = terrain.noise.hsh(x * 269, z * 271);
        if (roll < 0.62) return Blocks.SHORT_GRASS.defaultBlockState();
        if (roll < 0.90) return Blocks.FERN.defaultBlockState();
        if (roll < 0.96) return Blocks.POPPY.defaultBlockState();
        return Blocks.OXEYE_DAISY.defaultBlockState();
    }
}
