package com.jokerdayn.swworldgencore.worldgen.chunk;

/** Bit flags describing one generated column, packed into a byte. */
public final class ColumnFlags {

    /** The column carries island land rather than plain sea floor. */
    public static final int ISLAND = 1;

    /** The column belongs to an active volcano. */
    public static final int VOLCANO = 2;

    /** The column lies inside the lava lake footprint. */
    public static final int CRATER = 4;

    /** The column belongs to a freshwater lake or its narrow bank. */
    public static final int FRESHWATER_LAKE = 8;

    /** Freshwater covers this column. */
    public static final int FRESHWATER = 16;

    /** The freshwater bed surface is part of the guaranteed clay deposit. */
    public static final int LAKE_CLAY = 32;

    /** The freshwater bed or bank surface uses sparse gravel. */
    public static final int LAKE_GRAVEL = 64;

    /** The freshwater bed or its rim beach uses sand. */
    public static final int LAKE_SAND = 128;

    private ColumnFlags() {}

    public static boolean has(byte flags, int flag) {
        return (flags & flag) != 0;
    }
}
