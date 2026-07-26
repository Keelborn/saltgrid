package com.jokerdayn.swworldgencore.worldgen.decor;

import com.jokerdayn.swworldgencore.diagnostics.GeneratorDiagnostics;
import com.jokerdayn.swworldgencore.diagnostics.Phase;
import com.jokerdayn.swworldgencore.diagnostics.Token;
import com.jokerdayn.swworldgencore.worldgen.chunk.ChunkColumnCache;
import com.jokerdayn.swworldgencore.worldgen.chunk.ChunkHandoffCache;
import com.jokerdayn.swworldgencore.worldgen.terrain.TerrainContext;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;

/**
 * Runs the decoration passes for one chunk, in the order they must happen.
 *
 * <p>Order is load-bearing: the volcanic passes claim their columns first, boulders go down
 * before the small stuff so grass and flowers cannot grow through solid rock, and the trees
 * are placed by their own deterministic lattice pass before small features fill the gaps.</p>
 */
public final class ChunkDecorator {

    private final GeneratorDiagnostics diagnostics;
    private final ChunkHandoffCache handoff;
    private final VolcanicBiomeDecorator volcanicBiome;
    private final VolcanicFeatureDecorator volcanicFeatures;
    private final BoulderDecorator boulders;
    private final SavannaTreeDecorator savannaTrees;
    private final SmallFeatureDecorator smallFeatures;

    public ChunkDecorator(
        TerrainContext terrain,
        ChunkHandoffCache handoff,
        GeneratorDiagnostics diagnostics
    ) {
        this.diagnostics = diagnostics;
        this.handoff = handoff;
        this.volcanicBiome = new VolcanicBiomeDecorator(terrain, diagnostics);
        this.volcanicFeatures = new VolcanicFeatureDecorator(terrain, diagnostics);
        this.boulders = new BoulderDecorator(terrain, diagnostics);
        this.savannaTrees = new SavannaTreeDecorator(terrain, diagnostics);
        this.smallFeatures = new SmallFeatureDecorator(terrain, diagnostics);
    }

    /** @return a per-pass timing breakdown for a slow-call report */
    public String decorate(WorldGenLevel level, ChunkPos pos, Token benchmark) {
        int chunkX = pos.x;
        int chunkZ = pos.z;

        long started = System.nanoTime();
        ChunkColumnCache columns = handoff.consume(pos.toLong());
        long readCacheNs = System.nanoTime() - started;
        diagnostics.phase(benchmark, Phase.DECOR_READ_CACHE, readCacheNs);

        started = System.nanoTime();
        volcanicBiome.decorate(level, chunkX, chunkZ, columns, benchmark);
        long volcanicBiomeNs = System.nanoTime() - started;
        diagnostics.phase(benchmark, Phase.DECOR_VOLCANIC_BIOME, volcanicBiomeNs);

        started = System.nanoTime();
        volcanicFeatures.decorate(level, chunkX, chunkZ, columns, benchmark);
        long volcanicActiveNs = System.nanoTime() - started;
        diagnostics.phase(benchmark, Phase.DECOR_VOLCANIC_ACTIVE, volcanicActiveNs);

        started = System.nanoTime();
        boulders.decorate(level, chunkX, chunkZ, benchmark);
        long boulderNs = System.nanoTime() - started;
        diagnostics.phase(benchmark, Phase.DECOR_BOULDERS, boulderNs);

        started = System.nanoTime();
        savannaTrees.decorate(level, chunkX, chunkZ, benchmark);
        long savannaNs = System.nanoTime() - started;
        diagnostics.phase(benchmark, Phase.DECOR_SAVANNA_TREES, savannaNs);

        started = System.nanoTime();
        smallFeatures.decorate(level, chunkX, chunkZ, columns, benchmark);
        long smallFeatureNs = System.nanoTime() - started;
        diagnostics.phase(benchmark, Phase.DECOR_SMALL_FEATURES, smallFeatureNs);

        return "decorCacheMs=" + readCacheNs / 1_000_000.0 +
            ",volcanicBiomeMs=" + volcanicBiomeNs / 1_000_000.0 +
            ",volcanicActiveMs=" + volcanicActiveNs / 1_000_000.0 +
            ",bouldersMs=" + boulderNs / 1_000_000.0 +
            ",savannaTreesMs=" + savannaNs / 1_000_000.0 +
            ",smallFeaturesMs=" + smallFeatureNs / 1_000_000.0 +
            ",handoff=" + (columns != null);
    }

    /** Spawn-island boulder positions, for the {@code /boulder} command. */
    public int[][] spawnBoulderPositions() {
        return boulders.spawnIslandPositions();
    }
}
