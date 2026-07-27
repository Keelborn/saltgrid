package com.jokerdayn.swworldgencore.worldgen.terrain;

import com.jokerdayn.swworldgencore.worldgen.noise.Hashing;

/**
 * Maps a column to a {@link BiomeCategory}.
 *
 * <p>Memoised because {@code OceanBiomeSource.getNoiseBiome} calls this from the server
 * and client threads, not only from generation: an uncached classification runs the
 * shoreline search and the clearing lookup, which is far too much work to repeat per
 * biome query.</p>
 */
public final class BiomeClassifier {

    /** Water deeper than this counts as deep ocean. */
    private static final int DEEP_OCEAN_DEPTH = 28;

    private final SpawnIslandField spawnIsland;
    private final GridIslandField gridIslands;
    private final IslandLakeField lakes;
    private final TerrainColumnSampler columns;
    private final ColumnCaches caches;
    private final int seaLevel;

    /** Scratch owned solely by {@link #classify}. */
    private final ThreadLocal<GridIslandSample> scratch =
        ThreadLocal.withInitial(GridIslandSample::new);
    private final ThreadLocal<IslandLakeSample> lakeScratch =
        ThreadLocal.withInitial(IslandLakeSample::new);

    public BiomeClassifier(
        SpawnIslandField spawnIsland,
        GridIslandField gridIslands,
        IslandLakeField lakes,
        TerrainColumnSampler columns,
        ColumnCaches caches,
        int seaLevel
    ) {
        this.spawnIsland = spawnIsland;
        this.gridIslands = gridIslands;
        this.lakes = lakes;
        this.columns = columns;
        this.caches = caches;
        this.seaLevel = seaLevel;
    }

    public BiomeCategory classify(int x, int z) {
        long key = Hashing.columnKey(x, z);
        BiomeCategory cached = caches.lookupBiome(key);
        if (cached != null) return cached;

        int floor = columns.floorAt(x, z);
        GridIslandSample sample = scratch.get();
        gridIslands.sample(x, z, sample);
        double spawnDistance = spawnIsland.distanceTo(x, z);
        IslandLakeSample lake = lakeScratch.get();
        lakes.shape(x, z, spawnDistance, sample, floor, lake);

        BiomeCategory result;
        if (sample.volcano && floor >= seaLevel) {
            result = BiomeCategory.VOLCANO;
        } else if (floor < seaLevel) {
            result = seaLevel - floor > DEEP_OCEAN_DEPTH
                ? BiomeCategory.DEEP_OCEAN
                : BiomeCategory.OCEAN;
        } else if (lake.present) {
            boolean clearing = gridIslands.isClearing(x, z, spawnDistance);
            result = clearing ? BiomeCategory.TROPICS : BiomeCategory.SAVANNA;
        } else if (columns.isBeach(x, z, floor)) {
            result = BiomeCategory.BEACH;
        } else {
            boolean clearing = gridIslands.isClearing(x, z, spawnDistance);
            result = clearing ? BiomeCategory.TROPICS : BiomeCategory.SAVANNA;
        }

        caches.storeBiome(key, result);
        return result;
    }
}
