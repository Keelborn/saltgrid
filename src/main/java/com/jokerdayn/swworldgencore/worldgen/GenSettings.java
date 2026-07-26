package com.jokerdayn.swworldgencore.worldgen;

/**
 * Vertical geometry of the ocean dimension.
 *
 * <p>These values must stay in sync with {@code data/swworldgencore/dimension_type/ocean.json}
 * ({@code min_y}, {@code height}) — the generator reports them through
 * {@code getMinY()}/{@code getGenDepth()} and Minecraft trusts the dimension type
 * for chunk section layout.</p>
 */
public final class GenSettings {

    /** Lowest generated block layer (bedrock). */
    public static final int GEN_MIN_Y = -64;

    /** Total generated height, i.e. {@code dimension_type.height}. */
    public static final int GEN_DEPTH = 384;

    /** Highest generated block layer. */
    public static final int GEN_MAX_Y = GEN_MIN_Y + GEN_DEPTH - 1;

    /** Lowest sea level the codec accepts. */
    public static final int MIN_SEA_LEVEL = GEN_MIN_Y + 1;

    /**
     * Highest sea level the codec accepts. Leaves head-room above the water for
     * the volcano cone, its caldera and the lava lake sitting inside it.
     */
    public static final int MAX_SEA_LEVEL = GEN_MAX_Y - 64;

    /** Mean sea-floor height before any noise is applied. */
    public static final int BASE_FLOOR = 25;

    /** Inclusive bounds a column floor may occupy after all shaping. */
    public static final int MIN_FLOOR = GEN_MIN_Y + 1;
    public static final int MAX_FLOOR = GEN_MAX_Y - 2;

    /**
     * Side length of the padded column sample grid used per chunk: the 16 chunk
     * columns plus one ring of neighbours on each side, needed for slope tests.
     */
    public static final int LOCAL_SAMPLE_SIZE = 18;
    public static final int LOCAL_SAMPLE_AREA = LOCAL_SAMPLE_SIZE * LOCAL_SAMPLE_SIZE;

    private GenSettings() {}
}
