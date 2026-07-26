package com.jokerdayn.swworldgencore.diagnostics;

/** The generator caches whose hit rate, load and eviction cost are tracked. */
public enum CacheId {
    /** Column floor heights. */
    FLOOR,
    /** Biome classification per column. */
    BIOME,
    /** Beach flag per eligible column. */
    BEACH,
    /** Per-chunk column hand-off from terrain filling to decoration. */
    DECOR,
    /** Per-island boulder layouts. */
    BOULDER
}
