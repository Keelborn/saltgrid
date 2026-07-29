package com.jokerdayn.swworldgencore.worldgen.terrain;

/**
 * Everything {@link GridIslandField} knows about one column, in a reusable struct.
 *
 * <p>This replaces the former {@code double[8]} out-parameter shared through a
 * static {@code ThreadLocal}. That layout was correct only by convention: any
 * caller that kept the array across a nested call which also sampled the field
 * silently read clobbered values. Each consumer now owns its own instance, so the
 * aliasing hazard is gone structurally rather than by comment.</p>
 */
public final class GridIslandSample {

    /**
     * Normalised distance from the island centre. {@code < 1} inside the island;
     * {@link IslandSettings#NO_ISLAND_DISTANCE} when the column has no island.
     * For volcanoes this is the organic (domain-warped) distance, not the circular one.
     */
    public double normalizedDistance = IslandSettings.NO_ISLAND_DISTANCE;

    /** Radius of the owning island in blocks, or {@code 1.0} when there is none. */
    public double islandRadius = 1.0;

    /** Land height above sea level; {@code 0} for open ocean. */
    public double height;

    /** Whether the owning island is an active volcano. */
    public boolean volcano;

    /** Whether this column lies inside the lava lake footprint. */
    public boolean crater;

    /** Island centre, valid only when an island was found. */
    public double centerX;
    public double centerZ;

    /** Absolute y of the lava surface, or {@code 0} when not a volcano. */
    public int lavaLevel;

    /** Resets to the "no island here" state. */
    void clear() {
        normalizedDistance = IslandSettings.NO_ISLAND_DISTANCE;
        islandRadius = 1.0;
        height = 0.0;
        volcano = false;
        crater = false;
        centerX = 0.0;
        centerZ = 0.0;
        lavaLevel = 0;
    }

    /** True when this column belongs to some grid island. */
    public boolean hasIsland() {
        return normalizedDistance < IslandSettings.NO_ISLAND_DISTANCE;
    }

    /** True when the column carries enough land to count as island ground. */
    public boolean isLand() {
        return height > 0.5;
    }
}
