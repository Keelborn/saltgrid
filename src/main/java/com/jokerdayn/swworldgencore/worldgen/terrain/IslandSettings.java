package com.jokerdayn.swworldgencore.worldgen.terrain;

/**
 * Shape tuning for both island systems.
 *
 * <p>The world has two independent island generators:</p>
 * <ul>
 *   <li>the <b>spawn island</b>, a single hand-tuned landmass centred on
 *       {@code (0,0)} — see {@link SpawnIslandField};</li>
 *   <li>the <b>grid islands</b>, one candidate per {@link #CELL}-sized cell, a
 *       fraction of which become active volcanoes — see {@link GridIslandField}.</li>
 * </ul>
 */
public final class IslandSettings {

    // -------------------------------------------------------------------------
    // Spawn island
    // -------------------------------------------------------------------------

    /** Radius at which the spawn island reaches the shoreline. */
    public static final double SPAWN_ISLAND_RADIUS = 170.0;

    /** Extra ring over which the island fades into the sea floor. */
    public static final double SPAWN_ISLAND_FEATHER = 120.0;

    /** Normalised distance past which the spawn island no longer contributes. */
    public static final double SPAWN_ISLAND_MAX_T =
        1.0 + SPAWN_ISLAND_FEATHER / SPAWN_ISLAND_RADIUS;

    /** Height of the rolling hills, before the mountain spine is added. */
    public static final int SPAWN_ISLAND_MAX_HEIGHT = 18;

    /** Height of the ridged mountain spine in the island interior. */
    public static final int SPAWN_ISLAND_MOUNTAIN_HEIGHT = 80;

    /**
     * Distance beyond which {@link SpawnIslandField#distanceTo} provably saturates
     * at {@link #SPAWN_ISLAND_MAX_T}, so the (expensive) domain warp can be skipped.
     *
     * <p>Derivation: the three warp octaves add at most {@code 70 + 30 + 10 = 110}
     * blocks on each axis, so the radial warp shift is bounded by
     * {@code 110 * sqrt(2) ~= 155.6}. The field then subtracts a further 65 and
     * divides by {@link #SPAWN_ISLAND_RADIUS}, saturating once the raw distance
     * reaches {@code 170 * SPAWN_ISLAND_MAX_T + 65 + 155.6 ~= 510.6}. 512 is the
     * next safe round number, so returning the clamp early is bit-identical.</p>
     */
    public static final int SPAWN_ISLAND_SKIP_DISTANCE = 512;

    // -------------------------------------------------------------------------
    // Grid islands
    // -------------------------------------------------------------------------

    /** Side length of one island candidate cell. */
    public static final int CELL = 2048;

    /** A cell only holds an island when its radius hash falls below this. */
    public static final double GRID_ISLAND_CHANCE = 0.6;

    /** Island radius range, driven by the same hash that gates existence. */
    public static final double GRID_ISLAND_MIN_RADIUS = 80.0;
    public static final double GRID_ISLAND_RADIUS_SPREAD = 120.0;

    /** Island centres are jittered inside this window of their cell. */
    public static final int GRID_ISLAND_CENTER_BASE = 768;
    public static final int GRID_ISLAND_CENTER_JITTER = 512;

    /** Hill height range of a grid island. */
    public static final double GRID_ISLAND_MIN_HEIGHT = 4.0;
    public static final double GRID_ISLAND_HEIGHT_SPREAD = 16.0;

    /** Islands whose mountain hash falls below this grow a ridged peak. */
    public static final double GRID_ISLAND_MOUNTAIN_CHANCE = 0.06;

    /** Sentinel {@code normalizedDistance} reported when a column has no island. */
    public static final double NO_ISLAND_DISTANCE = 2.0;

    // -------------------------------------------------------------------------
    // Volcanoes
    // -------------------------------------------------------------------------

    /** Fraction of eligible grid islands that become active volcanoes. */
    public static final double VOLCANO_CHANCE = 0.085;

    /** Volcanoes never spawn closer than this to the world origin. */
    public static final double VOLCANO_MIN_DISTANCE = 700.0;

    /** Height of the strato-cone above the island shelf. */
    public static final double VOLCANO_CONE_HEIGHT = 74.0;

    /** Compact caldera on top of the wide cone, as a fraction of island radius. */
    public static final double VOLCANO_CRATER_RADIUS = 0.165;

    /** Radius of the lava lake inside the caldera. */
    public static final double VOLCANO_LAVA_RADIUS = 0.137;

    /** Outer edge of the sealed stone belt that keeps the lava lake contained. */
    public static final double VOLCANO_RIM_OUTER_RADIUS = 0.225;

    /** Guaranteed stone height above the lava surface inside the belt. */
    public static final int VOLCANO_RIM_CLEARANCE = 4;

    /** Lava surface height above sea level — well below the cone summit. */
    public static final int VOLCANO_LAVA_ABOVE_SEA = 39;

    /** Etna-style parasitic cones on the flanks. */
    public static final int VOLCANO_PARASITIC_CONES = 2;
    public static final double VOLCANO_PARASITIC_HEIGHT = 13.0;

    /** Radial erosion gullies (barrancos) running from the rim to the foot. */
    public static final int VOLCANO_GULLY_COUNT = 7;
    public static final double VOLCANO_GULLY_DEPTH = 4.5;

    /** Number of radial lava tongues carved into a volcano's flanks. */
    public static final int VOLCANO_FLOW_COUNT = 5;

    /** Obsidian domes rising out of the lava lake. */
    public static final int VOLCANO_LAKE_ISLANDS = 3;

    /**
     * Normalised distance above which a volcano column is treated as the outer
     * shelf rather than the cone itself, switching subsurface strata off.
     */
    public static final double VOLCANO_SHELF_T = 0.70;

    private IslandSettings() {}
}
