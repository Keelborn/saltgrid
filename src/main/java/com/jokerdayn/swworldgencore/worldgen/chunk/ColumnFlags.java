package com.jokerdayn.swworldgencore.worldgen.chunk;

/** Bit flags describing one generated column, packed into a byte. */
public final class ColumnFlags {

    /** The column carries island land rather than plain sea floor. */
    public static final int ISLAND = 1;

    /** The column belongs to an active volcano. */
    public static final int VOLCANO = 2;

    /** The column lies inside the lava lake footprint. */
    public static final int CRATER = 4;

    private ColumnFlags() {}

    public static boolean has(byte flags, int flag) {
        return (flags & flag) != 0;
    }
}
