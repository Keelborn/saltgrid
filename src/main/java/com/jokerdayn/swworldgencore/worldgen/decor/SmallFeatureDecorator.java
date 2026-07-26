package com.jokerdayn.swworldgencore.worldgen.decor;

import com.jokerdayn.swworldgencore.block.GroundDecorationBlock;
import com.jokerdayn.swworldgencore.block.ShellBlock;
import com.jokerdayn.swworldgencore.diagnostics.Counter;
import com.jokerdayn.swworldgencore.diagnostics.GeneratorDiagnostics;
import com.jokerdayn.swworldgencore.diagnostics.Token;
import com.jokerdayn.swworldgencore.registry.ModBlocks;
import com.jokerdayn.swworldgencore.worldgen.PalmGenerator;
import com.jokerdayn.swworldgencore.worldgen.chunk.ChunkColumnCache;
import com.jokerdayn.swworldgencore.worldgen.chunk.ColumnFlags;
import com.jokerdayn.swworldgencore.worldgen.terrain.BiomeCategory;
import com.jokerdayn.swworldgencore.worldgen.terrain.GridIslandSample;
import com.jokerdayn.swworldgencore.worldgen.terrain.TerrainContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Ground-level decoration on the ordinary (non-volcanic) islands: coastal palms, seashells,
 * pebbles and sticks, grass, flowers and jungle bushes.
 *
 * <p>Runs last, after boulders and trees, so that the small stuff fills whatever space is
 * still free instead of being buried by them.</p>
 */
public final class SmallFeatureDecorator {

    private static final ShellBlock.Variation[] SHELL_VARIATIONS =
        ShellBlock.Variation.values();
    private static final GroundDecorationBlock.Type[] GROUND_DECORATION_TYPES =
        GroundDecorationBlock.Type.values();

    private final TerrainContext terrain;
    private final GeneratorDiagnostics diagnostics;

    public SmallFeatureDecorator(TerrainContext terrain, GeneratorDiagnostics diagnostics) {
        this.terrain = terrain;
        this.diagnostics = diagnostics;
    }

    /** Mutable per-chunk tallies. */
    private static final class Tally {
        int palmAttempts;
        int palmsPlaced;
        int shells;
        int groundDecorations;
        int shortGrass;
        int flowers;
        int bushes;
        int bushLeaves;
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
        Tally tally = new Tally();

        // Resolved once instead of per column: DeferredHolder.get() is a volatile read plus
        // a null check, and this loop runs 256 times a chunk.
        BlockState shellBase = ModBlocks.SHELL.get().defaultBlockState();
        BlockState groundDecorationBase = ModBlocks.GROUND_DECORATION.get().defaultBlockState();
        BlockState bushLeaf = Blocks.JUNGLE_LEAVES.defaultBlockState()
            .setValue(LeavesBlock.PERSISTENT, true);

        GridIslandSample fallback = columns == null ? new GridIslandSample() : null;
        BlockPos.MutableBlockPos surfacePos = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos abovePos = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos bushPos = new BlockPos.MutableBlockPos();

        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int wx = minX + lx;
                int wz = minZ + lz;

                int floor;
                boolean onIsland;
                boolean onVolcano;
                if (columns != null) {
                    int columnIndex = ChunkColumnCache.index(lx, lz);
                    floor = columns.floor[columnIndex];
                    byte flags = columns.flags[columnIndex];
                    onIsland = ColumnFlags.has(flags, ColumnFlags.ISLAND);
                    onVolcano = ColumnFlags.has(flags, ColumnFlags.VOLCANO);
                } else {
                    // Fallback for a chunk whose terrain hand-off is gone; slower but exact.
                    double spawnDistance = terrain.spawnIsland.distanceTo(wx, wz);
                    double spawnHeight = terrain.spawnIsland.heightAt(wx, wz, spawnDistance);
                    terrain.gridIslands.sample(wx, wz, fallback);
                    floor = terrain.columns.computeFloor(
                        wx, wz, spawnDistance, spawnHeight, fallback.height
                    );
                    onIsland = spawnHeight > 0.0 || fallback.height > 0.5;
                    onVolcano = fallback.volcano;
                }

                if (floor < terrain.seaLevel) continue;
                if (!onIsland || onVolcano) continue;

                surfacePos.set(wx, floor, wz);
                abovePos.set(wx, floor + 1, wz);
                // Nothing in this iteration writes to the surface block itself, so a single
                // read serves both the sand and the grass tests.
                BlockState ground = level.getBlockState(surfacePos);
                boolean onSand = ground.is(Blocks.SAND);

                if (onSand && tryPlacePalm(level, wx, wz, floor, abovePos, tally, benchmark)) {
                    continue;
                }

                if (onSand && terrain.noise.hsh(wx * 31, wz * 37) < 0.06) {
                    if (level.getBlockState(abovePos).isAir()) {
                        ShellBlock.Variation variation = SHELL_VARIATIONS[
                            (int) (terrain.noise.hsh(wx * 79, wz * 83) * SHELL_VARIATIONS.length)
                                % SHELL_VARIATIONS.length
                        ];
                        level.setBlock(
                            abovePos,
                            shellBase.setValue(ShellBlock.VARIANT, variation),
                            2
                        );
                        tally.shells++;
                    }
                    continue;
                }

                if (terrain.noise.hsh(wx * 61, wz * 67) < 0.01
                    && level.getBlockState(abovePos).isAir()) {
                    GroundDecorationBlock.Type type = GROUND_DECORATION_TYPES[
                        (int) (terrain.noise.hsh(wx * 89, wz * 91)
                            * GROUND_DECORATION_TYPES.length)
                            % GROUND_DECORATION_TYPES.length
                    ];
                    level.setBlock(
                        abovePos,
                        groundDecorationBase.setValue(GroundDecorationBlock.VARIANT, type),
                        2
                    );
                    tally.groundDecorations++;
                }

                if (!ground.is(Blocks.GRASS_BLOCK)) continue;

                double roll = terrain.noise.hsh(wx * 17, wz * 29);
                if (roll >= 0.52 || !level.getBlockState(abovePos).isAir()) continue;

                if (roll < 0.45) {
                    level.setBlock(abovePos, Blocks.SHORT_GRASS.defaultBlockState(), 2);
                    tally.shortGrass++;
                } else if (roll < 0.49) {
                    level.setBlock(abovePos, pickFlower(wx, wz), 2);
                    tally.flowers++;
                } else {
                    placeBush(level, bushPos, bushLeaf, wx, wz, floor, tally);
                }
            }
        }

        recordCounters(benchmark, tally);
    }

    /**
     * Palms only on flat sand that the classifier actually calls a beach.
     *
     * <p>The pre-refactor condition tested for {@code TROPICS} here, which made the branch
     * unreachable: a sandy shore column is always classified {@code BEACH}.</p>
     */
    private boolean tryPlacePalm(
        WorldGenLevel level,
        int wx,
        int wz,
        int floor,
        BlockPos abovePos,
        Tally tally,
        Token benchmark
    ) {
        if (terrain.noise.hsh(wx * 41, wz * 43) >= 0.0045) return false;
        if (terrain.biomes.classify(wx, wz) != BiomeCategory.BEACH) return false;
        if (!isFlatAround(wx, wz, floor)) return false;
        if (!level.getBlockState(abovePos).isAir()) return false;

        tally.palmAttempts++;
        PalmGenerator.PlacementResult result = PalmGenerator.tryPlacePalmDetailed(
            level, wx, floor + 1, wz, terrain.noise.hsh(wx * 53, wz * 59)
        );
        TreePlacements.recordPalm(diagnostics, benchmark, result);
        if (!result.placed()) return false;
        tally.palmsPlaced++;
        return true;
    }

    private boolean isFlatAround(int wx, int wz, int floor) {
        return Math.abs(terrain.columns.floorAt(wx + 2, wz) - floor) <= 1
            && Math.abs(terrain.columns.floorAt(wx - 2, wz) - floor) <= 1
            && Math.abs(terrain.columns.floorAt(wx, wz + 2) - floor) <= 1
            && Math.abs(terrain.columns.floorAt(wx, wz - 2) - floor) <= 1;
    }

    private BlockState pickFlower(int wx, int wz) {
        double roll = terrain.noise.hsh(wx * 23, wz * 37);
        if (roll < 0.4) return Blocks.POPPY.defaultBlockState();
        if (roll < 0.7) return Blocks.DANDELION.defaultBlockState();
        return Blocks.OXEYE_DAISY.defaultBlockState();
    }

    private void placeBush(
        WorldGenLevel level,
        BlockPos.MutableBlockPos cursor,
        BlockState leaf,
        int wx,
        int wz,
        int floor,
        Tally tally
    ) {
        double roll = terrain.noise.hsh(wx * 97, wz * 83);
        int[][] template = DecorSettings.BUSH_TEMPLATES[
            roll < 0.33 ? 0 : roll < 0.66 ? 1 : 2
        ];
        int placed = 0;
        for (int[] offset : template) {
            cursor.set(wx + offset[0], floor + 1 + offset[1], wz + offset[2]);
            if (level.getBlockState(cursor).isAir()) {
                level.setBlock(cursor, leaf, 2);
                placed++;
            }
        }
        if (placed > 0) tally.bushes++;
        tally.bushLeaves += placed;
    }

    private void recordCounters(Token benchmark, Tally tally) {
        diagnostics.add(benchmark, Counter.PALM_ATTEMPTS, tally.palmAttempts);
        diagnostics.add(benchmark, Counter.PALMS_PLACED, tally.palmsPlaced);
        diagnostics.add(benchmark, Counter.SHELLS_PLACED, tally.shells);
        diagnostics.add(
            benchmark, Counter.GROUND_DECORATIONS_PLACED, tally.groundDecorations
        );
        diagnostics.add(benchmark, Counter.SHORT_GRASS_PLACED, tally.shortGrass);
        diagnostics.add(benchmark, Counter.FLOWERS_PLACED, tally.flowers);
        diagnostics.add(benchmark, Counter.BUSHES_PLACED, tally.bushes);
        diagnostics.add(benchmark, Counter.BUSH_LEAVES_PLACED, tally.bushLeaves);
    }
}
