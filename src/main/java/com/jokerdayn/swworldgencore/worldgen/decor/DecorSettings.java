package com.jokerdayn.swworldgencore.worldgen.decor;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Every decoration knob in one place.
 *
 * <p>Grouped by feature rather than by type, because these are the values a designer
 * actually reaches for: boulder size and ore yield, grove density, bush silhouettes.</p>
 */
public final class DecorSettings {

    // -------------------------------------------------------------------------
    // Ore boulders
    // -------------------------------------------------------------------------

    /** Overall size multiplier. {@code 1.0} normal, {@code 2.0} twice as large. */
    public static final double BOULDER_SIZE = 1.0;

    /** Base radius range in blocks, before {@link #BOULDER_SIZE} is applied. */
    public static final double BOULDER_MIN_RADIUS = 4.0;
    public static final double BOULDER_MAX_RADIUS = 5.5;

    /** Flattening: {@code 1.0} as tall as wide, {@code 0.5} low and broad. */
    public static final double BOULDER_HEIGHT_RATIO = 0.7;

    /** Boulders per island. */
    public static final int BOULDER_MIN_COUNT = 3;
    public static final int BOULDER_MAX_COUNT = 7;

    /** Deterministic candidates examined to reach the target count. */
    public static final int BOULDER_PLACEMENT_TRIES = 96;

    /** Minimum centre distance, expressed in the sum of the two radii. */
    public static final double BOULDER_SEPARATION = 2.50;

    /** Inset from the island edge as a fraction of its radius. */
    public static final double BOULDER_EDGE_MARGIN = 0.30;

    /** Maximum ground height difference under a boulder — rejects slopes and cliffs. */
    public static final int BOULDER_MAX_SLOPE = 2;

    /** Highest ground above sea level that still accepts boulders — excludes mountains. */
    public static final int BOULDER_MAX_GROUND_H = 22;

    /** How far the base is sunk into the ground so it never appears to float. */
    public static final int BOULDER_EMBED = 1;

    /**
     * Ore share of the smallest and largest boulder. The final amount scales with volume,
     * so a bigger rock naturally yields more.
     */
    public static final double BOULDER_MIN_ORE_FRACTION = 0.115;
    public static final double BOULDER_MAX_ORE_FRACTION = 0.155;

    // -------------------------------------------------------------------------
    // Savanna groves
    // -------------------------------------------------------------------------

    /** Candidate grid pitch; low-frequency noise then gathers candidates into groves. */
    public static final int SAVANNA_TREE_CELL = 11;
    public static final int SAVANNA_MAX_SLOPE = 3;
    public static final double SAVANNA_GROVE_THRESHOLD = 0.43;

    /** Offsets probed around a candidate to measure local slope. */
    public static final int[][] SAVANNA_SLOPE_SAMPLES = {
        { 3, 0 }, { -3, 0 }, { 0, 3 }, { 0, -3 },
    };

    // -------------------------------------------------------------------------
    // Volcano rewards
    // -------------------------------------------------------------------------

    /** Placeholder for the "molten magma" reward block on the caldera islands. */
    public static final BlockState VOLCANO_REWARD_BLOCK = Blocks.DIAMOND_BLOCK.defaultBlockState();

    // -------------------------------------------------------------------------
    // Bushes
    // -------------------------------------------------------------------------

    /** Leaf offsets {@code {dx, dy, dz}} for the three jungle-bush silhouettes. */
    public static final int[][][] BUSH_TEMPLATES = {
        {
            { 0, 0, 0 }, { 1, 0, 0 }, { -1, 0, 0 }, { 0, 0, 1 },
            { 0, 0, -1 }, { 1, 0, 1 }, { -1, 0, -1 }, { 0, 1, 0 },
            { 1, 1, 0 }, { 0, 1, 1 },
        },
        {
            { 0, 0, 0 }, { 1, 0, 0 }, { -1, 0, 0 }, { 0, 0, 1 },
            { 0, 0, -1 }, { 1, 0, -1 }, { -1, 0, 1 }, { 0, 1, 0 },
            { -1, 1, 0 }, { 0, 1, -1 }, { 1, 1, 1 },
        },
        {
            { 0, 0, 0 }, { 2, 0, 0 }, { -1, 0, 0 }, { 0, 0, 1 },
            { 0, 0, -2 }, { 1, 0, 1 }, { -1, 0, -1 }, { 0, 1, 0 },
            { 1, 1, 0 }, { 0, 1, 1 }, { -1, 1, 0 }, { 0, 2, 0 },
        },
    };

    private DecorSettings() {}
}
