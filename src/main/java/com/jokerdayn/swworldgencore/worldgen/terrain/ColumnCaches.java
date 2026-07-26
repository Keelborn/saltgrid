package com.jokerdayn.swworldgencore.worldgen.terrain;

import com.jokerdayn.swworldgencore.diagnostics.CacheId;
import com.jokerdayn.swworldgencore.diagnostics.GeneratorDiagnostics;
import java.util.Iterator;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The generator's column memo tables.
 *
 * <p>Two tiers: a per-thread {@link HotColumnCache} that answers repeated neighbour
 * probes without allocating, backed by shared maps so that work done by one worker thread
 * is reused by the others. Cached values are pure functions of {@code (x, z, seed)}, which
 * is why eviction order and timing can never influence generated terrain.</p>
 */
public final class ColumnCaches {

    /** Returned by the floor lookup when the column is not cached. */
    public static final int FLOOR_MISS = HotColumnCache.MISS;

    /** Returned by the beach lookup when the column is not cached. */
    public static final int BEACH_UNKNOWN = -1;

    static final int FLOOR_LIMIT = 262_144;
    static final int BIOME_LIMIT = 131_072;
    static final int BEACH_LIMIT = 131_072;

    /**
     * Entries removed per eviction pass. The original implementation removed ~40k
     * entries synchronously once the map crossed 110% of its limit, which showed up as
     * visible worldgen stalls; small batches keep the map near its limit instead.
     */
    private static final int TRIM_BATCH = 256;

    /**
     * {@code ConcurrentHashMap.size()} sums per-CPU counter cells, so checking it on
     * every single insert is measurable on the hot path. Checking one insert in 64 lets
     * the map overshoot by at most 64 entries, which is irrelevant next to a 262k limit.
     */
    private static final int SIZE_CHECK_MASK = 63;

    private final GeneratorDiagnostics diagnostics;

    private final ConcurrentHashMap<Long, Integer> floors = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Boolean> beaches = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, BiomeCategory> biomes = new ConcurrentHashMap<>();

    private final ThreadLocal<HotColumnCache> hotFloors =
        ThreadLocal.withInitial(HotColumnCache::new);
    private final ThreadLocal<HotColumnCache> hotBeaches =
        ThreadLocal.withInitial(HotColumnCache::new);

    private int floorInserts;
    private int beachInserts;
    private int biomeInserts;

    public ColumnCaches(GeneratorDiagnostics diagnostics) {
        this.diagnostics = diagnostics;
    }

    // -------------------------------------------------------------------------
    // Floor heights
    // -------------------------------------------------------------------------

    /** @return the cached floor, or {@link #FLOOR_MISS} */
    public int lookupFloor(long key, long seed) {
        HotColumnCache hot = hotFloors.get();
        int hotValue = hot.get(key, seed);
        if (hotValue != HotColumnCache.MISS) {
            diagnostics.cacheAccess(CacheId.FLOOR, true);
            return hotValue;
        }
        Integer shared = floors.get(key);
        diagnostics.cacheAccess(CacheId.FLOOR, shared != null);
        if (shared == null) return FLOOR_MISS;
        hot.put(key, shared, seed);
        return shared;
    }

    public void storeFloor(long key, int floor, long seed) {
        floors.put(key, floor);
        hotFloors.get().put(key, floor, seed);
        if ((++floorInserts & SIZE_CHECK_MASK) == 0) trim(CacheId.FLOOR, floors, FLOOR_LIMIT);
    }

    /**
     * Seeds only the calling thread's tier with a floor it already computed.
     *
     * <p>The chunk terrain pass evaluates 324 column floors up front and used to discard
     * them, so the shoreline search that runs moments later on the very same columns paid
     * for the noise all over again. Priming is allocation-free and touches no shared state,
     * which is why it goes to the per-thread tier only — pushing 324 boxed entries per chunk
     * into the shared map would cost more in garbage and eviction than it saves.</p>
     */
    public void primeFloor(long key, int floor, long seed) {
        hotFloors.get().put(key, floor, seed);
    }

    // -------------------------------------------------------------------------
    // Beach flags
    // -------------------------------------------------------------------------

    /** @return {@code 0}/{@code 1}, or {@link #BEACH_UNKNOWN} */
    public int lookupBeach(long key, long seed) {
        HotColumnCache hot = hotBeaches.get();
        int hotValue = hot.get(key, seed);
        if (hotValue != HotColumnCache.MISS) {
            diagnostics.cacheAccess(CacheId.BEACH, true);
            return hotValue;
        }
        Boolean shared = beaches.get(key);
        diagnostics.cacheAccess(CacheId.BEACH, shared != null);
        if (shared == null) return BEACH_UNKNOWN;
        int value = shared ? 1 : 0;
        hot.put(key, value, seed);
        return value;
    }

    public void storeBeach(long key, boolean beach, long seed) {
        beaches.put(key, beach);
        hotBeaches.get().put(key, beach ? 1 : 0, seed);
        if ((++beachInserts & SIZE_CHECK_MASK) == 0) trim(CacheId.BEACH, beaches, BEACH_LIMIT);
    }

    // -------------------------------------------------------------------------
    // Biome classification
    // -------------------------------------------------------------------------

    public BiomeCategory lookupBiome(long key) {
        BiomeCategory cached = biomes.get(key);
        diagnostics.cacheAccess(CacheId.BIOME, cached != null);
        return cached;
    }

    public void storeBiome(long key, BiomeCategory category) {
        biomes.put(key, category);
        if ((++biomeInserts & SIZE_CHECK_MASK) == 0) trim(CacheId.BIOME, biomes, BIOME_LIMIT);
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    /**
     * Drops everything. Called when the seed changes; the per-thread tiers notice the new
     * seed on their next access and reset themselves, so they need no coordination here.
     */
    public void clear() {
        diagnostics.cacheTrim(CacheId.FLOOR, floors.size());
        diagnostics.cacheTrim(CacheId.BIOME, biomes.size());
        diagnostics.cacheTrim(CacheId.BEACH, beaches.size());
        floors.clear();
        beaches.clear();
        biomes.clear();
    }

    /** Publishes current sizes to the diagnostics view. */
    public void publishSizes() {
        diagnostics.cacheState(CacheId.FLOOR, floors.size(), FLOOR_LIMIT);
        diagnostics.cacheState(CacheId.BIOME, biomes.size(), BIOME_LIMIT);
        diagnostics.cacheState(CacheId.BEACH, beaches.size(), BEACH_LIMIT);
    }

    /**
     * Incremental bounded eviction. Which entries go is deliberately unspecified —
     * iteration order — because every value is reproducible from its key.
     */
    private void trim(CacheId id, ConcurrentHashMap<Long, ?> cache, int limit) {
        int size = cache.size();
        if (size <= limit) return;

        long started = System.nanoTime();
        int overflow = Math.max(1, size - limit);
        int toRemove = Math.min(size, Math.min(TRIM_BATCH, overflow + TRIM_BATCH - 1));
        int removed = 0;
        Iterator<Long> iterator = cache.keySet().iterator();
        while (removed < toRemove && iterator.hasNext()) {
            if (cache.remove(iterator.next()) != null) removed++;
        }
        if (removed > 0) {
            diagnostics.cacheTrim(id, removed, Math.max(0L, System.nanoTime() - started));
        }
    }
}
