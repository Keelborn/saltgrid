package com.jokerdayn.swworldgencore.worldgen.terrain;

/**
 * Result of shaping one freshwater-lake column.
 *
 * <p>The struct is mutable so the terrain hot path can reuse one instance for every
 * column instead of allocating hundreds of short-lived records per chunk.</p>
 */
public final class IslandLakeSample {

    /** The column belongs to either the water footprint or its narrow shore. */
    public boolean present;

    /** Water covers this column up to {@link #waterLevel}. */
    public boolean water;

    /** The surface block is one of the lake's guaranteed clay-yield columns. */
    public boolean clay;

    /** The surface block is part of a sparse gravel bed or shore arc. */
    public boolean gravel;

    /** Absolute y of the freshwater surface; zero outside the water footprint. */
    public int waterLevel;

    /** Final solid floor after the lake basin or bank has shaped the base terrain. */
    public int floor;

    void clear(int baseFloor) {
        present = false;
        water = false;
        clay = false;
        gravel = false;
        waterLevel = 0;
        floor = baseFloor;
    }
}
