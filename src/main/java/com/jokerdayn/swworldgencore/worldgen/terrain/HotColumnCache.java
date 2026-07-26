package com.jokerdayn.swworldgencore.worldgen.terrain;

/**
 * Per-thread, direct-mapped column cache in front of the shared maps.
 *
 * <p>Exists purely to keep the hottest lookups allocation-free: the shared caches are
 * {@code ConcurrentHashMap<Long, ...>}, so every probe there boxes a key. One fixed-size
 * array trio per worker thread removes that garbage and the hash-map indirection for the
 * repeated neighbour lookups that shoreline detection and slope tests perform.</p>
 *
 * <p>Single-threaded by construction, so no synchronisation and no torn reads. Entries
 * are dropped wholesale when the world seed changes.</p>
 */
final class HotColumnCache {

    /** Returned by {@link #get} when the key is not present. */
    static final int MISS = Integer.MIN_VALUE;

    private static final int SIZE = 8192;
    private static final int MASK = SIZE - 1;

    private long seed = Long.MIN_VALUE;
    private boolean initialized;
    private long[] keys;
    private int[] values;
    private boolean[] present;

    int get(long key, long currentSeed) {
        ensureSeed(currentSeed);
        int index = index(key);
        return present[index] && keys[index] == key ? values[index] : MISS;
    }

    void put(long key, int value, long currentSeed) {
        ensureSeed(currentSeed);
        int index = index(key);
        keys[index] = key;
        values[index] = value;
        present[index] = true;
    }

    private void ensureSeed(long currentSeed) {
        if (initialized && seed == currentSeed) return;
        initialized = true;
        seed = currentSeed;
        keys = new long[SIZE];
        values = new int[SIZE];
        present = new boolean[SIZE];
    }

    /** Murmur3 finaliser — column keys are structured, so they need real mixing. */
    private static int index(long key) {
        long mixed = key;
        mixed ^= mixed >>> 33;
        mixed *= 0xff51afd7ed558ccdL;
        mixed ^= mixed >>> 33;
        return (int) mixed & MASK;
    }
}
