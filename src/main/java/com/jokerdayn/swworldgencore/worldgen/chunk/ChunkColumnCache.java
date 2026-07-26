package com.jokerdayn.swworldgencore.worldgen.chunk;

/**
 * The 16x16 column summary handed from terrain filling to decoration.
 *
 * <p>Decoration would otherwise have to recompute every column's floor and island flags
 * from scratch. The entry is published when a chunk finishes filling and consumed (and
 * removed) when the same chunk is decorated; the decorator keeps a slower fallback path
 * for the case where the hand-off is missing, e.g. after a seed sync flushed the caches
 * or when the chunk was filled before a restart.</p>
 */
public final class ChunkColumnCache {

    public final int[] floor = new int[256];
    public final byte[] flags = new byte[256];

    /** Row-major index used by every column loop in the generator. */
    public static int index(int localX, int localZ) {
        return (localX << 4) | localZ;
    }
}
