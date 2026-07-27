package com.jokerdayn.swworldgencore.worldgen.terrain;

import com.jokerdayn.swworldgencore.diagnostics.GeneratorDiagnostics;
import com.jokerdayn.swworldgencore.worldgen.noise.TerrainNoise;

/**
 * Everything the generator needs to answer questions about terrain, for one seed.
 *
 * <p>This is the unit of atomic seed publication. The old code kept the seed, the noise
 * offset table and five caches in separate mutable fields and hoped that writing the
 * {@code volatile} seed last was enough; a reader could still observe the new offset table
 * beside the old hash seed, or a cache entry computed under the previous seed. Building a
 * whole new context and publishing that single reference makes the swap indivisible.</p>
 *
 * <p>Immutable apart from the caches, and safe to share across generation threads.</p>
 */
public final class TerrainContext {

    public final TerrainNoise noise;
    public final int seaLevel;
    public final ColumnCaches caches;
    public final SpawnIslandField spawnIsland;
    public final GridIslandField gridIslands;
    public final IslandLakeField lakes;
    public final TerrainColumnSampler columns;
    public final BiomeClassifier biomes;
    public final SurfacePalette surface;
    public final VolcanicPalette volcanic;

    public TerrainContext(long seed, int seaLevel, GeneratorDiagnostics diagnostics) {
        this.noise = new TerrainNoise(seed);
        this.seaLevel = seaLevel;
        this.caches = new ColumnCaches(diagnostics);
        this.spawnIsland = new SpawnIslandField(noise);
        this.gridIslands = new GridIslandField(noise, seaLevel);
        this.lakes = new IslandLakeField(noise, spawnIsland, seaLevel);
        this.columns = new TerrainColumnSampler(
            noise, spawnIsland, gridIslands, lakes, caches, diagnostics, seaLevel
        );
        this.biomes = new BiomeClassifier(
            spawnIsland, gridIslands, lakes, columns, caches, seaLevel
        );
        this.surface = new SurfacePalette(noise, seaLevel);
        this.volcanic = new VolcanicPalette(noise, seaLevel);
    }

    public long seed() {
        return noise.seed();
    }

    /** Convenience: allocates a fresh sample struct for a caller that owns it. */
    public GridIslandSample newIslandSample() {
        return new GridIslandSample();
    }
}
