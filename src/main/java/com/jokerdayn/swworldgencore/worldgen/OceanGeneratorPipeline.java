package com.jokerdayn.swworldgencore.worldgen;

import com.jokerdayn.swworldgencore.diagnostics.GeneratorDiagnostics;
import com.jokerdayn.swworldgencore.worldgen.chunk.ChunkHandoffCache;
import com.jokerdayn.swworldgencore.worldgen.chunk.ChunkTerrainBuilder;
import com.jokerdayn.swworldgencore.worldgen.decor.ChunkDecorator;
import com.jokerdayn.swworldgencore.worldgen.spawn.SpawnBeachFinder;
import com.jokerdayn.swworldgencore.worldgen.terrain.TerrainContext;

/**
 * Everything the generator needs for one seed, wired together.
 *
 * <p>This is the unit of atomic seed publication. The dimension JSON may declare
 * {@code "seed": 0}, in which case the real world seed only becomes known when the level
 * loads; rebinding to it means throwing away every memo table, noise offset table and cached
 * layout at once. Building a whole new pipeline and publishing that single reference makes
 * the swap indivisible — a generation thread can never observe half of one seed's state
 * beside half of another's.</p>
 */
public final class OceanGeneratorPipeline {

    public final TerrainContext terrain;
    public final ChunkHandoffCache handoff;
    public final ChunkTerrainBuilder terrainBuilder;
    public final ChunkDecorator decorator;
    public final SpawnBeachFinder spawnFinder;

    OceanGeneratorPipeline(long seed, int seaLevel, GeneratorDiagnostics diagnostics) {
        this.terrain = new TerrainContext(seed, seaLevel, diagnostics);
        this.handoff = new ChunkHandoffCache(diagnostics);
        this.terrainBuilder = new ChunkTerrainBuilder(diagnostics);
        this.decorator = new ChunkDecorator(terrain, handoff, diagnostics);
        this.spawnFinder = new SpawnBeachFinder(terrain);
    }

    public long seed() {
        return terrain.seed();
    }

    /** Publishes current cache occupancy to the diagnostics view. */
    public void publishCacheSizes() {
        terrain.caches.publishSizes();
    }
}
