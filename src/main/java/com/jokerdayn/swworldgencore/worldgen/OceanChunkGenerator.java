package com.jokerdayn.swworldgencore.worldgen;

import static com.jokerdayn.swworldgencore.worldgen.GenSettings.GEN_DEPTH;
import static com.jokerdayn.swworldgencore.worldgen.GenSettings.GEN_MIN_Y;
import static com.jokerdayn.swworldgencore.worldgen.GenSettings.MAX_SEA_LEVEL;
import static com.jokerdayn.swworldgencore.worldgen.GenSettings.MIN_SEA_LEVEL;

import com.jokerdayn.swworldgencore.diagnostics.GeneratorDiagnostics;
import com.jokerdayn.swworldgencore.diagnostics.Phase;
import com.jokerdayn.swworldgencore.diagnostics.Stage;
import com.jokerdayn.swworldgencore.diagnostics.Token;
import com.jokerdayn.swworldgencore.worldgen.chunk.ChunkTerrainBuilder;
import com.jokerdayn.swworldgencore.worldgen.chunk.ColumnProbe;
import com.jokerdayn.swworldgencore.worldgen.spawn.SpawnBeachFinder;
import com.jokerdayn.swworldgencore.worldgen.terrain.BiomeCategory;
import com.jokerdayn.swworldgencore.worldgen.terrain.GridIslandField;
import com.jokerdayn.swworldgencore.worldgen.terrain.GridIslandSample;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * An archipelago world: open ocean, a hand-tuned spawn island, an infinite lattice of grid
 * islands and rare active volcanoes.
 *
 * <p>This class is now only the {@link ChunkGenerator} contract plus seed lifecycle. The
 * actual work lives in focused collaborators, all reached through a single
 * {@link OceanGeneratorPipeline} reference:</p>
 * <ul>
 *   <li>{@code worldgen.noise} — seeded hashing and fractal noise;</li>
 *   <li>{@code worldgen.terrain} — island fields, column heights, beaches, biome
 *       classification, material palettes and the memo tables;</li>
 *   <li>{@code worldgen.chunk} — terrain writing and single-column probes;</li>
 *   <li>{@code worldgen.decor} — boulders, trees, volcanic features, small features;</li>
 *   <li>{@code worldgen.spawn} — the spawn-beach search.</li>
 * </ul>
 */
public class OceanChunkGenerator extends ChunkGenerator {

    private static final Logger LOG = LoggerFactory.getLogger("SWWorldgenCore");

    public static final MapCodec<OceanChunkGenerator> CODEC =
        RecordCodecBuilder.mapCodec(instance -> instance
            .group(
                BiomeSource.CODEC.fieldOf("biome_source").forGetter(g -> g.biomeSource),
                Codec.LONG.fieldOf("seed").forGetter(OceanChunkGenerator::getSeed),
                Codec.intRange(MIN_SEA_LEVEL, MAX_SEA_LEVEL)
                    .fieldOf("sea_level")
                    .forGetter(g -> g.seaLevel)
            )
            .apply(instance, OceanChunkGenerator::new)
        );

    private final int seaLevel;
    private final GeneratorDiagnostics diagnostics = new GeneratorDiagnostics();
    private final Object seedLock = new Object();
    private final AtomicBoolean spawnColumnLogged = new AtomicBoolean();

    /** Swapped wholesale when the seed is resolved; see {@link OceanGeneratorPipeline}. */
    private volatile OceanGeneratorPipeline pipeline;

    public OceanChunkGenerator(BiomeSource biomeSource, long seed, int seaLevel) {
        super(biomeSource);
        if (seaLevel < MIN_SEA_LEVEL || seaLevel > MAX_SEA_LEVEL) {
            throw new IllegalArgumentException(
                "seaLevel must be in [" + MIN_SEA_LEVEL + ", " + MAX_SEA_LEVEL + "]"
            );
        }
        this.seaLevel = seaLevel;
        this.pipeline = new OceanGeneratorPipeline(seed, seaLevel, diagnostics);
        if (biomeSource instanceof OceanBiomeSource oceanBiomes) {
            oceanBiomes.attachGenerator(this);
        }
        LOG.info("[OceanChunkGenerator] Created with seed={}", seed);
    }

    @Override
    protected MapCodec<? extends ChunkGenerator> codec() {
        return CODEC;
    }

    // -------------------------------------------------------------------------
    // Seed lifecycle
    // -------------------------------------------------------------------------

    public long getSeed() {
        return pipeline.seed();
    }

    public GeneratorDiagnostics diagnostics() {
        return diagnostics;
    }

    /**
     * Binds the generator to the real world seed.
     *
     * <p>The dimension JSON normally declares {@code "seed": 0}, which is the sentinel for
     * "inherit from the level". A zero seed is therefore never resolved further, which is
     * harmless: the offsets derived from it are self-consistent either way.</p>
     *
     * <p>Called from {@code LevelEvent.Load} before any chunk of the dimension is generated,
     * and defensively from the generation entry points for the case where a dimension is
     * reached without that event having fired.</p>
     */
    public void syncSeedFromLevel(WorldGenLevel level) {
        if (level == null || pipeline.seed() != 0L) return;
        long worldSeed = level.getSeed();
        if (worldSeed == 0L) return;
        synchronized (seedLock) {
            if (pipeline.seed() != 0L) return;
            diagnostics.seedReset();
            // A single volatile write replaces the whole world of derived state.
            pipeline = new OceanGeneratorPipeline(worldSeed, seaLevel, diagnostics);
            spawnColumnLogged.set(false);
        }
        LOG.info("[OceanChunkGenerator] Synced seed from world: {}", worldSeed);
    }

    /** Refreshes the reported cache occupancy; used by the benchmark commands. */
    public void publishCacheSizes() {
        pipeline.publishCacheSizes();
    }

    // -------------------------------------------------------------------------
    // Generation
    // -------------------------------------------------------------------------

    @Override
    public CompletableFuture<ChunkAccess> fillFromNoise(
        Blender blender,
        RandomState randomState,
        StructureManager structureManager,
        ChunkAccess chunk
    ) {
        if (chunk.getLevel() instanceof WorldGenLevel worldGenLevel) {
            syncSeedFromLevel(worldGenLevel);
        }

        OceanGeneratorPipeline current = pipeline;
        ChunkPos pos = chunk.getPos();
        Token benchmark =
            diagnostics.begin(Stage.FILL_NOISE, pos.toLong(), current.seed());
        Throwable error = null;
        String breakdown = null;
        try {
            logSpawnColumnOnce(current, pos);

            ChunkTerrainBuilder.Result result =
                current.terrainBuilder.build(chunk, current.terrain, benchmark);

            long publishStarted = System.nanoTime();
            current.handoff.publish(pos.toLong(), result.columns());
            long publishNs = System.nanoTime() - publishStarted;
            diagnostics.phase(benchmark, Phase.FILL_PUBLISH_CACHE, publishNs);

            breakdown = result.breakdown() + ",publishMs=" + publishNs / 1_000_000.0;
            return CompletableFuture.completedFuture(chunk);
        } catch (RuntimeException | Error thrown) {
            error = thrown;
            throw thrown;
        } finally {
            diagnostics.end(
                benchmark, current.seed(), error, overBudget(benchmark, Stage.FILL_NOISE)
                    ? breakdown
                    : null
            );
        }
    }

    @Override
    public void applyBiomeDecoration(
        WorldGenLevel level,
        ChunkAccess chunk,
        StructureManager structureManager
    ) {
        syncSeedFromLevel(level);

        OceanGeneratorPipeline current = pipeline;
        ChunkPos pos = chunk.getPos();
        Token benchmark =
            diagnostics.begin(Stage.DECORATION, pos.toLong(), current.seed());
        Throwable error = null;
        String breakdown = null;
        try {
            breakdown = current.decorator.decorate(level, pos, benchmark);
        } catch (RuntimeException | Error thrown) {
            error = thrown;
            throw thrown;
        } finally {
            diagnostics.end(
                benchmark, current.seed(), error, overBudget(benchmark, Stage.DECORATION)
                    ? breakdown
                    : null
            );
        }
    }

    /** Surfaces are written directly by {@code fillFromNoise}. */
    @Override
    public void buildSurface(
        WorldGenRegion region,
        StructureManager structureManager,
        RandomState randomState,
        ChunkAccess chunk
    ) {}

    /** No caves or ravines: the islands are thin and the ocean floor should stay solid. */
    @Override
    public void applyCarvers(
        WorldGenRegion region,
        long seed,
        RandomState randomState,
        BiomeManager biomeManager,
        StructureManager structureManager,
        ChunkAccess chunk,
        GenerationStep.Carving step
    ) {}

    /** No initial mob population; spawning is left to the normal runtime rules. */
    @Override
    public void spawnOriginalMobs(WorldGenRegion region) {}

    // -------------------------------------------------------------------------
    // Column queries
    // -------------------------------------------------------------------------

    @Override
    public int getBaseHeight(
        int x,
        int z,
        Heightmap.Types heightmapType,
        LevelHeightAccessor level,
        RandomState random
    ) {
        if (level instanceof WorldGenLevel worldGenLevel) syncSeedFromLevel(worldGenLevel);
        OceanGeneratorPipeline current = pipeline;
        Token benchmark =
            diagnostics.begin(Stage.BASE_HEIGHT, Long.MIN_VALUE, current.seed());
        Throwable error = null;
        try {
            return ColumnProbe.baseHeight(current.terrain, x, z, heightmapType);
        } catch (RuntimeException | Error thrown) {
            error = thrown;
            throw thrown;
        } finally {
            diagnostics.end(
                benchmark, current.seed(), error,
                overBudget(benchmark, Stage.BASE_HEIGHT)
                    ? "block=[" + x + ',' + z + "], heightmap=" + heightmapType
                    : null
            );
        }
    }

    @Override
    public NoiseColumn getBaseColumn(
        int x,
        int z,
        LevelHeightAccessor level,
        RandomState random
    ) {
        if (level instanceof WorldGenLevel worldGenLevel) syncSeedFromLevel(worldGenLevel);
        OceanGeneratorPipeline current = pipeline;
        Token benchmark =
            diagnostics.begin(Stage.BASE_COLUMN, Long.MIN_VALUE, current.seed());
        Throwable error = null;
        try {
            return ColumnProbe.baseColumn(current.terrain, x, z, level);
        } catch (RuntimeException | Error thrown) {
            error = thrown;
            throw thrown;
        } finally {
            diagnostics.end(
                benchmark, current.seed(), error,
                overBudget(benchmark, Stage.BASE_COLUMN)
                    ? "block=[" + x + ',' + z + ']'
                    : null
            );
        }
    }

    @Override
    public int getGenDepth() {
        return GEN_DEPTH;
    }

    @Override
    public int getSeaLevel() {
        return seaLevel;
    }

    @Override
    public int getMinY() {
        return GEN_MIN_Y;
    }

    // -------------------------------------------------------------------------
    // Public API used by the biome source, the commands and the spawn handler
    // -------------------------------------------------------------------------

    /** Biome classification for a column; consumed by {@link OceanBiomeSource}. */
    public BiomeCategory classifyBiome(int x, int z) {
        return pipeline.terrain.biomes.classify(x, z);
    }

    /** @see GridIslandField#findNearestVolcano */
    public int[] findNearestVolcano(int x, int z, int maxCellRadius) {
        return pipeline.terrain.gridIslands.findNearestVolcano(x, z, maxCellRadius);
    }

    /** @see GridIslandField#findNearestIslandCenter */
    public int[] findNearestIslandCenter(int x, int z) {
        return pipeline.terrain.gridIslands.findNearestIslandCenter(x, z);
    }

    /** Every valid boulder position on the spawn island, as {@code {x, z}} pairs. */
    public int[][] getSpawnBoulderPositions() {
        return pipeline.decorator.spawnBoulderPositions();
    }

    /** @see SpawnBeachFinder#find */
    public SpawnBeachFinder.SpawnBeachPosition findSpawnBeachPosition(
        int preferredOceanDistance,
        long searchSalt
    ) {
        return pipeline.spawnFinder.find(preferredOceanDistance, searchSalt);
    }

    @Override
    public void addDebugScreenInfo(List<String> info, RandomState random, BlockPos pos) {
        OceanGeneratorPipeline current = pipeline;
        info.add("OceanChunkGenerator");
        info.add("seaLevel=" + seaLevel + "  seed=" + current.seed());

        double spawnDistance = current.terrain.spawnIsland.distanceTo(pos.getX(), pos.getZ());
        double spawnHeight =
            current.terrain.spawnIsland.heightAt(pos.getX(), pos.getZ(), spawnDistance);
        GridIslandSample sample = new GridIslandSample();
        current.terrain.gridIslands.sample(pos.getX(), pos.getZ(), sample);
        info.add(String.format(
            "spawn: dist=%.2f  h=%.2f", spawnDistance, spawnHeight
        ));
        info.add(String.format(
            "grid:  t=%.2f  h=%.2f  volcano=%b", sample.normalizedDistance, sample.height,
            sample.volcano
        ));
        // compactStatus is deliberately cheap: this line is rendered every frame.
        info.add(diagnostics.compactStatus(current.seed()));
    }

    // -------------------------------------------------------------------------
    // Internals
    // -------------------------------------------------------------------------

    private boolean overBudget(Token benchmark, Stage stage) {
        return System.nanoTime() - benchmark.startedNs() > stage.budgetNs();
    }

    /** One-shot sanity dump of the origin column, to confirm the seed took effect. */
    private void logSpawnColumnOnce(OceanGeneratorPipeline current, ChunkPos pos) {
        if (pos.x != 0 || pos.z != 0 || !spawnColumnLogged.compareAndSet(false, true)) return;
        double spawnDistance = current.terrain.spawnIsland.distanceTo(0, 0);
        double spawnHeight = current.terrain.spawnIsland.heightAt(0, 0, spawnDistance);
        GridIslandSample sample = new GridIslandSample();
        current.terrain.gridIslands.sample(0, 0, sample);
        LOG.info(
            "[DEBUG] seed={} spawnDist={} spawnH={} gridH={} gridT={} floor={}",
            current.seed(),
            String.format("%.4f", spawnDistance),
            String.format("%.4f", spawnHeight),
            String.format("%.4f", sample.height),
            String.format("%.4f", sample.normalizedDistance),
            current.terrain.columns.computeFloor(0, 0, spawnDistance, spawnHeight, sample.height)
        );
    }
}
