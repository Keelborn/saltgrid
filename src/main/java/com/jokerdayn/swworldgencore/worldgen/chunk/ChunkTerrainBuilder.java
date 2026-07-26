package com.jokerdayn.swworldgencore.worldgen.chunk;

import static com.jokerdayn.swworldgencore.worldgen.GenSettings.LOCAL_SAMPLE_SIZE;
import static com.jokerdayn.swworldgencore.worldgen.terrain.IslandSettings.SPAWN_ISLAND_MAX_T;
import static com.jokerdayn.swworldgencore.worldgen.terrain.IslandSettings.VOLCANO_SHELF_T;

import com.jokerdayn.swworldgencore.diagnostics.Counter;
import com.jokerdayn.swworldgencore.diagnostics.GeneratorDiagnostics;
import com.jokerdayn.swworldgencore.diagnostics.Phase;
import com.jokerdayn.swworldgencore.diagnostics.Token;
import com.jokerdayn.swworldgencore.worldgen.noise.Hashing;
import com.jokerdayn.swworldgencore.worldgen.terrain.GridIslandSample;
import com.jokerdayn.swworldgencore.worldgen.terrain.SurfacePalette;
import com.jokerdayn.swworldgencore.worldgen.terrain.TerrainBlocks;
import com.jokerdayn.swworldgencore.worldgen.terrain.TerrainContext;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Writes the solid terrain, water and lava of one chunk.
 *
 * <p>Three passes, deliberately separated so the profiler can tell them apart:</p>
 * <ol>
 *   <li><b>sample</b> — the padded 18x18 column grid (noise, island fields, floor heights);</li>
 *   <li><b>classify</b> — shoreline searches and material selection per column, which is
 *       expensive terrain maths that must not masquerade as slow block I/O in reports;</li>
 *   <li><b>write</b> — the actual section writes and heightmap updates.</li>
 * </ol>
 */
public final class ChunkTerrainBuilder {

    /** Working set per generation thread; see {@link ChunkTerrainScratch}. */
    private static final ThreadLocal<ChunkTerrainScratch> SCRATCH =
        ThreadLocal.withInitial(ChunkTerrainScratch::new);

    private final GeneratorDiagnostics diagnostics;

    public ChunkTerrainBuilder(GeneratorDiagnostics diagnostics) {
        this.diagnostics = diagnostics;
    }

    /**
     * What one chunk produced.
     *
     * @param columns   summary to hand to the decoration pass
     * @param breakdown per-pass timings, attached to a slow-call report
     */
    public record Result(ChunkColumnCache columns, String breakdown) {}

    /** Mutable per-chunk tallies, kept out of the pass methods' signatures. */
    private static final class Tally {
        int land;
        int ocean;
        int spawnIsland;
        int gridIsland;
        int volcano;
        int crater;
        int beach;
        int slope;
        int underwaterSlabs;
        int seagrassBlocks;
        long solidWrites;
        long waterWrites;
        long lavaWrites;
        long sampleNs;
        long classifyNs;
        long prepareNs;
        long writeNs;
        int maxFloor = Integer.MIN_VALUE;
        int maxLavaLevel;
    }

    /** Generates the chunk and returns its column summary for the decoration pass. */
    public Result build(ChunkAccess chunk, TerrainContext terrain, Token benchmark) {
        ChunkPos pos = chunk.getPos();
        ChunkTerrainScratch scratch = SCRATCH.get();
        Tally tally = new Tally();
        tally.maxLavaLevel = terrain.seaLevel;

        ChunkColumnCache columnCache = new ChunkColumnCache();

        long started = System.nanoTime();
        sampleTerrain(pos, terrain, scratch, columnCache, tally);
        tally.sampleNs = System.nanoTime() - started;
        diagnostics.phase(benchmark, Phase.FILL_SAMPLE_TERRAIN, tally.sampleNs);

        started = System.nanoTime();
        classifyColumns(pos, terrain, scratch, tally);
        tally.classifyNs = System.nanoTime() - started;
        diagnostics.phase(benchmark, Phase.FILL_CLASSIFY_SURFACE, tally.classifyNs);

        writeChunk(chunk, pos, terrain, scratch, tally, benchmark);

        recordCounters(benchmark, tally);
        return new Result(columnCache, describe(tally));
    }

    // -------------------------------------------------------------------------
    // Pass 1 — sample
    // -------------------------------------------------------------------------

    private void sampleTerrain(
        ChunkPos pos,
        TerrainContext terrain,
        ChunkTerrainScratch scratch,
        ChunkColumnCache columnCache,
        Tally tally
    ) {
        GridIslandSample sample = scratch.islandSample;
        int baseX = pos.getMinBlockX();
        int baseZ = pos.getMinBlockZ();

        // One ring of padding on each side: the classification pass needs the four
        // orthogonal neighbours of every chunk column to detect slopes and cliffs.
        for (int lx = -1; lx <= 16; lx++) {
            for (int lz = -1; lz <= 16; lz++) {
                int wx = baseX + lx;
                int wz = baseZ + lz;
                double spawnDistance = terrain.spawnIsland.distanceTo(wx, wz);
                double spawnHeight = terrain.spawnIsland.heightAt(wx, wz, spawnDistance);
                terrain.gridIslands.sample(wx, wz, sample);
                int floor = terrain.columns.computeFloor(
                    wx, wz, spawnDistance, spawnHeight, sample.height
                );

                // The shoreline search in the next pass probes exactly these columns, so hand
                // the result straight to this thread's memo tier instead of recomputing it.
                terrain.caches.primeFloor(
                    Hashing.columnKey(wx, wz), floor, terrain.seed()
                );

                int index = (lx + 1) * LOCAL_SAMPLE_SIZE + lz + 1;
                scratch.spawnDistances[index] = spawnDistance;
                scratch.spawnHeights[index] = spawnHeight;
                scratch.gridHeights[index] = sample.height;
                scratch.gridDistances[index] = sample.normalizedDistance;
                scratch.volcanoCenterX[index] = sample.centerX;
                scratch.volcanoCenterZ[index] = sample.centerZ;
                scratch.lavaLevels[index] = sample.lavaLevel;
                scratch.floors[index] = floor;

                int flags = sample.volcano ? ColumnFlags.VOLCANO : 0;
                if (sample.crater) {
                    flags |= ColumnFlags.CRATER;
                    tally.maxLavaLevel = Math.max(tally.maxLavaLevel, sample.lavaLevel);
                }
                scratch.terrainFlags[index] = (byte) flags;

                if (floor > tally.maxFloor) tally.maxFloor = floor;

                if (lx >= 0 && lx < 16 && lz >= 0 && lz < 16) {
                    int columnIndex = ChunkColumnCache.index(lx, lz);
                    columnCache.floor[columnIndex] = floor;
                    int cacheFlags =
                        spawnHeight > 0.0 || sample.height > 0.5 ? ColumnFlags.ISLAND : 0;
                    if (sample.volcano) cacheFlags |= ColumnFlags.VOLCANO;
                    columnCache.flags[columnIndex] = (byte) cacheFlags;
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Pass 2 — classify
    // -------------------------------------------------------------------------

    private void classifyColumns(
        ChunkPos pos,
        TerrainContext terrain,
        ChunkTerrainScratch scratch,
        Tally tally
    ) {
        int seaLevel = terrain.seaLevel;
        int baseX = pos.getMinBlockX();
        int baseZ = pos.getMinBlockZ();

        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int wx = baseX + lx;
                int wz = baseZ + lz;
                int columnIndex = ChunkColumnCache.index(lx, lz);
                int index = (lx + 1) * LOCAL_SAMPLE_SIZE + lz + 1;

                int floor = scratch.floors[index];
                double spawnDistance = scratch.spawnDistances[index];
                double spawnHeight = scratch.spawnHeights[index];
                double gridHeight = scratch.gridHeights[index];
                double gridDistance = scratch.gridDistances[index];
                boolean volcano = ColumnFlags.has(scratch.terrainFlags[index], ColumnFlags.VOLCANO);
                boolean crater = ColumnFlags.has(scratch.terrainFlags[index], ColumnFlags.CRATER);
                int lavaLevel = scratch.lavaLevels[index];
                boolean onIsland = spawnHeight > 0.0 || gridHeight > 0.5;

                int north = scratch.floors[index - LOCAL_SAMPLE_SIZE];
                int south = scratch.floors[index + LOCAL_SAMPLE_SIZE];
                int west = scratch.floors[index - 1];
                int east = scratch.floors[index + 1];
                boolean onSlope =
                    floor < north || floor < south || floor < west || floor < east;

                if (floor < seaLevel) tally.ocean++;
                else tally.land++;
                if (spawnHeight > 0.0) tally.spawnIsland++;
                if (gridHeight > 0.5) tally.gridIsland++;
                if (volcano) tally.volcano++;
                if (crater) tally.crater++;
                if (onSlope) tally.slope++;

                // Thicker soil on cliff faces stops stone from showing through the skin.
                int minNeighbor = Math.min(Math.min(north, south), Math.min(west, east));
                int cliffDrop = Math.max(0, floor - minNeighbor);
                int dirtLayers = dirtLayersFor(
                    volcano, gridDistance, cliffDrop, floor >= seaLevel && onIsland,
                    spawnDistance
                );
                scratch.dirtLayers[columnIndex] = dirtLayers;

                boolean beach = terrain.columns.isBeach(wx, wz, floor);
                if (beach) tally.beach++;

                scratch.surfaces[columnIndex] = volcano
                    ? terrain.volcanic.surface(
                        wx, wz, floor, gridDistance, crater, lavaLevel,
                        scratch.volcanoCenterX[index], scratch.volcanoCenterZ[index]
                    )
                    : terrain.surface.surface(
                        wx, wz, floor, spawnDistance, gridHeight, gridDistance, beach
                    );

                // Every entry is assigned unconditionally: the scratch is reused across
                // chunks, so a "leave it alone" branch would read last chunk's value.
                boolean volcanicStrata = volcano && gridDistance <= VOLCANO_SHELF_T;
                if (dirtLayers > 0 && volcanicStrata) {
                    scratch.subsurfaces[columnIndex] = null;
                    scratch.fissures[columnIndex] = terrain.volcanic.fissureAt(wx, wz);
                    scratch.strataShifts[columnIndex] = terrain.volcanic.strataShiftAt(wx, wz);
                } else {
                    scratch.fissures[columnIndex] = 0.0;
                    scratch.strataShifts[columnIndex] = 0;
                    scratch.subsurfaces[columnIndex] = dirtLayers <= 0
                        ? null
                        : volcano
                            ? terrain.volcanic.shelfSubsurface(wx, wz)
                            : terrain.surface.subsurface(
                                wx, wz, floor, spawnDistance, spawnHeight, gridHeight, beach
                            );
                }

                boolean underwaterSlab = floor < seaLevel && onSlope && onIsland;
                scratch.underwaterSlabs[columnIndex] = underwaterSlab;
                scratch.seagrass[columnIndex] = floor < seaLevel && !underwaterSlab
                    ? (byte) terrain.surface.seagrassKind(wx, wz, floor)
                    : 0;
            }
        }
    }

    /**
     * Soil thickness above the stone core.
     *
     * <p>Shared with {@code getBaseColumn} through
     * {@link #dirtLayersFor(boolean, double, int, boolean, double)} so the single-column
     * query and the chunk pass can never disagree about where the soil ends.</p>
     */
    public static int dirtLayersFor(
        boolean volcano,
        double gridDistance,
        int cliffDrop,
        boolean islandAboveWater,
        double spawnDistance
    ) {
        if (volcano) {
            // The cone needs deep strata for its cliff walls; the outer shelf does not.
            int cap = gridDistance > VOLCANO_SHELF_T ? 8 : 24;
            return Math.max(3, Math.min(cliffDrop + 2, cap));
        }
        if (islandAboveWater) return 3;
        if (spawnDistance < SPAWN_ISLAND_MAX_T) return 2;
        return 0;
    }

    // -------------------------------------------------------------------------
    // Pass 3 — write
    // -------------------------------------------------------------------------

    private void writeChunk(
        ChunkAccess chunk,
        ChunkPos pos,
        TerrainContext terrain,
        ChunkTerrainScratch scratch,
        Tally tally,
        Token benchmark
    ) {
        int minY = chunk.getMinBuildHeight();
        // Water is filled up to sea level even in chunks whose highest floor sits far
        // below it, so the acquired section range must always reach the sea surface.
        int maxWorkY = Math.max(
            Math.max(tally.maxFloor + 2, tally.maxLavaLevel),
            terrain.seaLevel
        );
        int minSection = chunk.getSectionIndex(minY);
        int maxSection = chunk.getSectionIndex(maxWorkY);

        long prepareStarted = System.nanoTime();
        // Track how far the acquire loop got: if acquiring or heightmap creation throws
        // part-way, the finally block must release exactly what was taken and no more.
        int acquiredThrough = minSection - 1;
        try {
            for (int i = minSection; i <= maxSection; i++) {
                chunk.getSection(i).acquire();
                acquiredThrough = i;
            }
            Heightmap oceanFloor =
                chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.OCEAN_FLOOR_WG);
            Heightmap worldSurface =
                chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG);
            tally.prepareNs = System.nanoTime() - prepareStarted;
            diagnostics.phase(benchmark, Phase.FILL_PREPARE_SECTIONS, tally.prepareNs);

            long writeStarted = System.nanoTime();
            writeColumns(chunk, pos, terrain, scratch, tally, minY, oceanFloor, worldSurface);
            tally.writeNs = System.nanoTime() - writeStarted;
            diagnostics.phase(benchmark, Phase.FILL_WRITE_SECTIONS, tally.writeNs);
        } finally {
            for (int i = minSection; i <= acquiredThrough; i++) {
                chunk.getSection(i).release();
            }
        }
    }

    private void writeColumns(
        ChunkAccess chunk,
        ChunkPos pos,
        TerrainContext terrain,
        ChunkTerrainScratch scratch,
        Tally tally,
        int minY,
        Heightmap oceanFloor,
        Heightmap worldSurface
    ) {
        int seaLevel = terrain.seaLevel;
        int baseX = pos.getMinBlockX();
        int baseZ = pos.getMinBlockZ();

        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int wx = baseX + lx;
                int wz = baseZ + lz;
                int index = (lx + 1) * LOCAL_SAMPLE_SIZE + lz + 1;
                int columnIndex = ChunkColumnCache.index(lx, lz);

                int floor = scratch.floors[index];
                double gridDistance = scratch.gridDistances[index];
                boolean volcano =
                    ColumnFlags.has(scratch.terrainFlags[index], ColumnFlags.VOLCANO);
                boolean crater =
                    ColumnFlags.has(scratch.terrainFlags[index], ColumnFlags.CRATER);
                int lavaLevel = scratch.lavaLevels[index];
                int dirtLayers = scratch.dirtLayers[columnIndex];
                BlockState surface = scratch.surfaces[columnIndex];
                tally.solidWrites += floor - (long) minY + 1L;

                setBlock(chunk, lx, minY, lz, TerrainBlocks.BEDROCK);
                fillColumn(chunk, lx, lz, minY + 1, floor - dirtLayers - 1, TerrainBlocks.STONE);

                if (dirtLayers > 0) {
                    BlockState subsurface = scratch.subsurfaces[columnIndex];
                    if (subsurface == null) {
                        // Volcanic cone: every skin block picks its own rock from its own y,
                        // so a cliff face reads as real layered strata.
                        double fissure = scratch.fissures[columnIndex];
                        int strataShift = scratch.strataShifts[columnIndex];
                        for (int depth = 1; depth <= dirtLayers; depth++) {
                            int y = floor - depth;
                            setBlock(chunk, lx, y, lz, terrain.volcanic.subsurface(
                                wx, y, wz, gridDistance, crater, lavaLevel, fissure, strataShift
                            ));
                        }
                    } else {
                        fillColumn(chunk, lx, lz, floor - dirtLayers, floor - 1, subsurface);
                    }
                }

                setBlock(chunk, lx, floor, lz, surface);
                oceanFloor.update(lx, floor, lz, surface);
                worldSurface.update(lx, floor, lz, surface);

                if (crater && floor < lavaLevel) {
                    tally.lavaWrites += lavaLevel - (long) floor;
                    fillColumn(chunk, lx, lz, floor + 1, lavaLevel, TerrainBlocks.LAVA);
                    worldSurface.update(lx, lavaLevel, lz, TerrainBlocks.LAVA);
                }

                if (floor >= seaLevel) continue;

                int waterFrom = floor + 1;
                if (scratch.underwaterSlabs[columnIndex]) {
                    tally.underwaterSlabs++;
                    tally.solidWrites++;
                    BlockState slab = SurfacePalette.slabFor(surface)
                        .setValue(SlabBlock.TYPE, SlabType.BOTTOM)
                        .setValue(SlabBlock.WATERLOGGED, true);
                    setBlock(chunk, lx, floor + 1, lz, slab);
                    oceanFloor.update(lx, floor + 1, lz, slab);
                    worldSurface.update(lx, floor + 1, lz, slab);
                    waterFrom = floor + 2;
                } else if (scratch.seagrass[columnIndex] == 2) {
                    tally.seagrassBlocks += 2;
                    setBlock(chunk, lx, floor + 1, lz, TerrainBlocks.TALL_SEAGRASS_LOWER);
                    setBlock(chunk, lx, floor + 2, lz, TerrainBlocks.TALL_SEAGRASS_UPPER);
                    worldSurface.update(lx, floor + 2, lz, TerrainBlocks.TALL_SEAGRASS_UPPER);
                    waterFrom = floor + 3;
                } else if (scratch.seagrass[columnIndex] == 1) {
                    tally.seagrassBlocks++;
                    setBlock(chunk, lx, floor + 1, lz, TerrainBlocks.SEAGRASS);
                    worldSurface.update(lx, floor + 1, lz, TerrainBlocks.SEAGRASS);
                    waterFrom = floor + 2;
                }

                if (waterFrom <= seaLevel) {
                    tally.waterWrites += seaLevel - (long) waterFrom + 1L;
                    fillColumn(chunk, lx, lz, waterFrom, seaLevel, TerrainBlocks.WATER);
                    worldSurface.update(lx, seaLevel, lz, TerrainBlocks.WATER);
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Section writes
    // -------------------------------------------------------------------------

    /** Direct section write — skips the chunk's own bookkeeping and light updates. */
    private static void setBlock(ChunkAccess chunk, int lx, int y, int lz, BlockState state) {
        chunk.getSection(chunk.getSectionIndex(y)).setBlockState(lx, y & 15, lz, state, false);
    }

    /**
     * Fills an inclusive y range, resolving the section once per 16-block span instead of
     * per block. {@code y | 15} is the top of the current span and is correct for negative
     * y under two's complement.
     */
    private static void fillColumn(
        ChunkAccess chunk,
        int lx,
        int lz,
        int fromY,
        int toY,
        BlockState state
    ) {
        int y = fromY;
        while (y <= toY) {
            LevelChunkSection section = chunk.getSection(chunk.getSectionIndex(y));
            int end = Math.min(toY, y | 15);
            for (; y <= end; y++) {
                section.setBlockState(lx, y & 15, lz, state, false);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Diagnostics
    // -------------------------------------------------------------------------

    private void recordCounters(Token benchmark, Tally tally) {
        diagnostics.add(benchmark, Counter.COLUMNS_TOTAL, 256L);
        diagnostics.add(benchmark, Counter.COLUMNS_LAND, tally.land);
        diagnostics.add(benchmark, Counter.COLUMNS_OCEAN, tally.ocean);
        diagnostics.add(benchmark, Counter.COLUMNS_SPAWN_ISLAND, tally.spawnIsland);
        diagnostics.add(benchmark, Counter.COLUMNS_GRID_ISLAND, tally.gridIsland);
        diagnostics.add(benchmark, Counter.COLUMNS_VOLCANO, tally.volcano);
        diagnostics.add(benchmark, Counter.COLUMNS_CRATER, tally.crater);
        diagnostics.add(benchmark, Counter.COLUMNS_BEACH, tally.beach);
        diagnostics.add(benchmark, Counter.COLUMNS_SLOPE, tally.slope);
        diagnostics.add(benchmark, Counter.SOLID_BLOCK_WRITES, tally.solidWrites);
        diagnostics.add(benchmark, Counter.WATER_BLOCK_WRITES, tally.waterWrites);
        diagnostics.add(benchmark, Counter.LAVA_BLOCK_WRITES, tally.lavaWrites);
        diagnostics.add(benchmark, Counter.UNDERWATER_SLABS, tally.underwaterSlabs);
        diagnostics.add(benchmark, Counter.SEAGRASS_BLOCKS, tally.seagrassBlocks);
    }

    private static String describe(Tally tally) {
        return "sampleMs=" + tally.sampleNs / 1_000_000.0 +
            ",classifyMs=" + tally.classifyNs / 1_000_000.0 +
            ",prepareMs=" + tally.prepareNs / 1_000_000.0 +
            ",writeMs=" + tally.writeNs / 1_000_000.0 +
            ",land=" + tally.land +
            ",ocean=" + tally.ocean +
            ",volcano=" + tally.volcano +
            ",crater=" + tally.crater +
            ",slopes=" + tally.slope;
    }
}
