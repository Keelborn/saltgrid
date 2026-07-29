package com.jokerdayn.swworldgencore.diagnostics;

/**
 * Cumulative terrain and decoration tallies.
 *
 * <p>Values are approximate under concurrency: they are summed from
 * {@code LongAdder}s that other generation threads may still be updating.</p>
 */
public enum Counter {
    COLUMNS_TOTAL,
    COLUMNS_LAND,
    COLUMNS_OCEAN,
    COLUMNS_SPAWN_ISLAND,
    COLUMNS_GRID_ISLAND,
    COLUMNS_VOLCANO,
    COLUMNS_CRATER,
    COLUMNS_BEACH,
    COLUMNS_SLOPE,
    BEACH_SEARCHES,
    BEACH_FLOOR_SAMPLES,
    SOLID_BLOCK_WRITES,
    WATER_BLOCK_WRITES,
    LAVA_BLOCK_WRITES,
    UNDERWATER_SLABS,
    SEAGRASS_BLOCKS,
    PALM_ATTEMPTS,
    PALMS_PLACED,
    PALM_BLOCK_WRITES,
    ACACIA_ATTEMPTS,
    ACACIAS_PLACED,
    ACACIA_BLOCK_WRITES,
    TREE_PREFLIGHT_FAILURES,
    BOULDER_LAYOUTS_BUILT,
    BOULDER_FRAGMENTS,
    BOULDER_BLOCK_WRITES,
    BOULDER_ORE_WRITES,
    SHELLS_PLACED,
    GROUND_DECORATIONS_PLACED,
    SHORT_GRASS_PLACED,
    FLOWERS_PLACED,
    BUSHES_PLACED,
    BUSH_LEAVES_PLACED,
    LAKE_PLANTS_PLACED,
    VOLCANIC_FEATURE_WRITES
}
