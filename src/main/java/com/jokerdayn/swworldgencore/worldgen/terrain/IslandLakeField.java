package com.jokerdayn.swworldgencore.worldgen.terrain;

import static com.jokerdayn.swworldgencore.worldgen.terrain.IslandSettings.SPAWN_ISLAND_MAX_T;

import com.jokerdayn.swworldgencore.worldgen.noise.Hashing;
import com.jokerdayn.swworldgencore.worldgen.noise.TerrainNoise;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;

/**
 * One freshwater lake on every non-volcanic island.
 *
 * <p>The layout is a pure function of the world seed and owning island, so basins and
 * shores remain seamless across chunk borders. Every lake is built from four rings:</p>
 * <ol>
 *   <li>a shelving bed up to {@link #MAX_DEPTH} blocks deep, with a little relief;</li>
 *   <li>a warped water outline that reads as bays and headlands rather than an oval;</li>
 *   <li>a sealed sandy rim whose surface sits <em>exactly</em> at the water level;</li>
 *   <li>a hollow that interpolates from that rim back into the untouched terrain.</li>
 * </ol>
 *
 * <p>Ring 3 is what keeps the water flush with its shore. The water level itself is derived
 * from the land height at the chosen lake site, not from a per-island constant, so a lake no
 * longer sits in a ledge a couple of blocks below the surrounding grass.</p>
 *
 * <p>The bed also carries one connected 48-block clay deposit. Vanilla clay drops four clay
 * balls, making the guaranteed yield exactly {@code 48 * 4 = 192} items, or three stacks.</p>
 */
public final class IslandLakeField {

    private static final double TWO_PI = Math.PI * 2.0;

    /** Outer edge of the water footprint, in normalised lake radius. */
    private static final double WATER_LIMIT = 1.0;

    /**
     * Outer edge of the sealed rim that holds the water in at its own surface height.
     *
     * <p>Sized with room to spare: the outline warp and harmonics push {@code t} by up to
     * {@code ~0.2} per block near the waterline, so a narrower rim could leave a water
     * column with a neighbour that the hollow has already started to slope away.</p>
     */
    private static final double RIM_LIMIT = 1.30;

    /** Where the shaped hollow has faded completely back into the surrounding terrain. */
    private static final double SHORE_LIMIT = 1.85;

    /** Deepest the bed ever gets below the water surface. */
    private static final int MAX_DEPTH = 5;

    /** Outline domain warp, as a fraction of the lake radii. */
    private static final double OUTLINE_WARP = 0.15;

    /**
     * Bound on {@code |local| / radius} for a shaped column: the outline harmonics reach
     * {@code 1.128} and the warp adds {@link #OUTLINE_WARP} on top, so no column further
     * than this multiple of the larger radius can belong to the lake.
     */
    private static final double LAYOUT_REACH = SHORE_LIMIT * 1.128 + OUTLINE_WARP;

    /** Distance at which candidate sites are probed for flatness and dry land. */
    private static final int SITE_PROBE_RADIUS = 24;

    /** Land height a site wants before the full bowl fits above the sea floor. */
    private static final double MIN_SITE_HEIGHT = 6.0;

    private static final int CLAY_BLOCKS_PER_LAKE = 48;

    private final TerrainNoise noise;
    private final SpawnIslandField spawnIsland;
    private final GridIslandField gridIslands;
    private final int seaLevel;
    private final Layout spawnLake;

    /**
     * Per-thread grid-island layouts. Two slots, because a chunk sitting between two
     * islands would otherwise rebuild a layout for every single column.
     */
    private final ThreadLocal<LayoutCache> gridLayouts =
        ThreadLocal.withInitial(LayoutCache::new);

    /** Scratch for the site search; never handed to a caller, so nothing can alias it. */
    private final ThreadLocal<GridIslandSample> siteProbe =
        ThreadLocal.withInitial(GridIslandSample::new);

    public IslandLakeField(
        TerrainNoise noise,
        SpawnIslandField spawnIsland,
        GridIslandField gridIslands,
        int seaLevel
    ) {
        this.noise = noise;
        this.spawnIsland = spawnIsland;
        this.gridIslands = gridIslands;
        this.seaLevel = seaLevel;
        this.spawnLake = buildSpawnLayout();
    }

    /**
     * Applies the freshwater basin belonging to the current island, if any.
     *
     * @param spawnDistance already-sampled spawn-island distance for this column
     * @param grid          already-sampled grid-island state for this column
     * @param baseFloor     solid floor before freshwater shaping
     */
    public void shape(
        int x,
        int z,
        double spawnDistance,
        GridIslandSample grid,
        int baseFloor,
        IslandLakeSample out
    ) {
        out.clear(baseFloor);

        // The spawn island is independent of the grid and always receives one calm lake.
        if (spawnDistance < SPAWN_ISLAND_MAX_T
            && insideLayoutSquare(x, z, spawnLake)
            && shapeLake(x, z, spawnLake, baseFloor, out)) {
            return;
        }

        // Active volcanoes already own a lava lake; every other grid island gets freshwater.
        if (!grid.hasIsland() || grid.volcano) return;
        Layout lake = gridLayout(grid);
        if (!insideLayoutSquare(x, z, lake)) return;
        shapeLake(x, z, lake, baseFloor, out);
    }

    private boolean shapeLake(
        int x,
        int z,
        Layout lake,
        int baseFloor,
        IslandLakeSample out
    ) {
        int dx = x - lake.centerX;
        int dz = z - lake.centerZ;
        int localX = rotateX(dx, dz, lake.quarterTurn);
        int localZ = rotateZ(dx, dz, lake.quarterTurn);

        // Warping the outline domain grows bays and headlands out of the base ellipse. The
        // warp reads world coordinates, so it stays seamless across chunk borders.
        double nx = localX / lake.radiusX + outlineWarp(x, z, 311.0, -187.0, 233, 234);
        double nz = localZ / lake.radiusZ + outlineWarp(x, z, -643.0, 829.0, 235, 236);
        double angle = Math.atan2(nz, nx);
        double outline =
            1.0 +
            Math.sin(angle * 3.0 + lake.phase) * 0.070 +
            Math.sin(angle * 5.0 - lake.phase * 0.7) * 0.038 +
            Math.sin(angle * 8.0 + lake.phase * 1.9) * 0.020;
        double t = Math.sqrt(nx * nx + nz * nz) / outline;
        if (t > SHORE_LIMIT) return false;

        out.present = true;

        if (t <= WATER_LIMIT) {
            shapeBed(x, z, localX, localZ, t, lake, out);
            return true;
        }

        if (t <= RIM_LIMIT) {
            // The whole fix for the old sunken lakes: the last solid ring is exactly the
            // water surface, so the shore reads as flush instead of a two-block ledge. It
            // also seals the basin when the site slopes away from the water.
            out.floor = lake.waterLevel;
            out.sand = sandyShore(x, z, t);
            out.gravel = !out.sand && gravelPatch(x, z, t);
            return true;
        }

        // Beyond the rim the shaped floor interpolates back into the untouched terrain, so
        // the lake sits in a soft hollow and joins the hillside without a step.
        double blend = TerrainNoise.smoothstep((t - RIM_LIMIT) / (SHORE_LIMIT - RIM_LIMIT));
        out.floor = (int) Math.round(lake.waterLevel + (baseFloor - lake.waterLevel) * blend);
        out.gravel = blend < 0.30 && gravelPatch(x, z, t);
        return true;
    }

    /** Shelving bed: one block at the reeds, deepest in the calm middle. */
    private void shapeBed(
        int x,
        int z,
        int localX,
        int localZ,
        double t,
        Layout lake,
        IslandLakeSample out
    ) {
        out.water = true;
        out.waterLevel = lake.waterLevel;

        // A low island cannot afford the full bowl, or its bed would sink under the sea
        // floor it is supposed to sit on.
        int budget = Mth.clamp(lake.waterLevel - seaLevel - 1, 2, MAX_DEPTH);
        double bowl = TerrainNoise.smoothstepClamped((0.90 - t) / 0.62);
        double relief = (noise.fbm(
            x * 0.085 + 517.0 + noise.seedOff(237, 1.4),
            z * 0.085 - 733.0 + noise.seedOff(238, 2.1),
            2, 2.0, 0.5
        ) - 0.5) * 1.6;
        int depth = Mth.clamp(
            1 + (int) Math.round(bowl * (budget - 1) + relief * bowl), 1, budget
        );
        out.floor = lake.waterLevel - depth;

        out.clay = isClayMask(localX, localZ);
        out.gravel = !out.clay && gravelPatch(x, z, t);
        out.sand = !out.clay && !out.gravel;
    }

    /** Warp component in {@code [-OUTLINE_WARP, OUTLINE_WARP]}, as a fraction of a radius. */
    private double outlineWarp(
        int x,
        int z,
        double originX,
        double originZ,
        int saltX,
        int saltZ
    ) {
        return (noise.fbm(
            x * 0.042 + originX + noise.seedOff(saltX, 1.5),
            z * 0.042 + originZ + noise.seedOff(saltZ, 0.9),
            2, 2.0, 0.5
        ) - 0.5) * 2.0 * OUTLINE_WARP;
    }

    /**
     * Connected 48-column clay bed, sitting in the deep middle where a real lake collects
     * its sediment. Quarter-turning the whole lake varies its orientation without changing
     * the exact resource yield.
     */
    private static boolean isClayMask(int x, int z) {
        return switch (z) {
            case -4 -> x >= -1 && x <= 1;           // 3
            case -3 -> x >= -3 && x <= 1;           // 5
            case -2 -> x >= -4 && x <= 2;           // 7
            case -1 -> x >= -4 && x <= 3;           // 8
            case 0 -> x >= -3 && x <= 4;            // 8
            case 1 -> x >= -4 && x <= 3;            // 8
            case 2 -> x >= -2 && x <= 3;            // 6
            case 3 -> x >= 0 && x <= 2;             // 3
            default -> false;
        };
    }

    /** Sparse gravel: patches on the shelf, bare in the calm middle and up the shore. */
    private boolean gravelPatch(int x, int z, double t) {
        double patch = noise.fbm(
            x * 0.115 + 631.0 + noise.seedOff(231, 2.0),
            z * 0.115 - 419.0 + noise.seedOff(232, 2.0),
            2, 2.0, 0.5
        );
        double band =
            TerrainNoise.smoothstepClamped((t - 0.42) / 0.28) *
            TerrainNoise.smoothstepClamped((1.45 - t) / 0.35);
        return patch > 0.72 - band * 0.12;
    }

    /** Sand hugs the waterline and gives way to grass as the rim climbs out of the water. */
    private boolean sandyShore(int x, int z, double t) {
        double fade = (RIM_LIMIT - t) / (RIM_LIMIT - WATER_LIMIT);
        double grain = noise.fbm(
            x * 0.135 + 907.0 + noise.seedOff(239, 1.1),
            z * 0.135 + 233.0 + noise.seedOff(240, 2.6),
            2, 2.0, 0.5
        );
        return grain < 0.30 + fade * 0.55;
    }

    /** Material override for a lake column, or {@code null} for its ordinary grassy bank. */
    public static BlockState surfaceOverride(IslandLakeSample lake) {
        return surfaceOverride(lake.clay, lake.gravel, lake.sand);
    }

    /** Allocation-free material override for packed chunk-column lake flags. */
    public static BlockState surfaceOverride(boolean clay, boolean gravel, boolean sand) {
        if (clay) return TerrainBlocks.CLAY;
        if (gravel) return TerrainBlocks.GRAVEL;
        if (sand) return TerrainBlocks.SAND;
        return null;
    }

    // -------------------------------------------------------------------------
    // Stable per-island layouts
    // -------------------------------------------------------------------------

    /** Everything about one lake that is constant across all of its columns. */
    private static final class Layout {
        long ownerKey = Long.MIN_VALUE;
        int centerX;
        int centerZ;
        int waterLevel;
        int reach;
        double radiusX;
        double radiusZ;
        int quarterTurn;
        double phase;

        void shapeFrom(long hash) {
            // One mixed hash sliced into disjoint bit windows: the pre-shift traps
            // documented on Hashing.frac only bite when the windows overlap or run past
            // the sign bits of an arithmetic shift.
            long shape = Hashing.mix64(hash ^ 0x9E3779B97F4A7C15L);
            radiusX = 11.0 + Hashing.frac(shape) * 1.8;
            radiusZ = 9.2 + Hashing.fracLogical(shape, 24) * 1.4;
            quarterTurn = (int) ((shape >>> 48) & 3L);
            phase = Hashing.fracLogical(shape, 50) * TWO_PI;
            reach = (int) Math.ceil(Math.max(radiusX, radiusZ) * LAYOUT_REACH) + 1;
        }
    }

    /** Two-slot per-thread memo of grid-island layouts. */
    private static final class LayoutCache {
        private final Layout[] slots = { new Layout(), new Layout() };
        private int next;

        Layout find(long ownerKey) {
            for (Layout slot : slots) {
                if (slot.ownerKey == ownerKey) return slot;
            }
            return null;
        }

        Layout claim() {
            Layout slot = slots[next];
            next = (next + 1) & 1;
            return slot;
        }
    }

    int spawnLakeCenterX() {
        return spawnLake.centerX;
    }

    int spawnLakeCenterZ() {
        return spawnLake.centerZ;
    }

    /**
     * Chooses one lake site on the spawn island per seed. The eight rim probes keep the
     * whole footprint away from the ocean even when the domain warp is extreme, and reward
     * flat ground: the flatter the site, the less the hollow has to bite into the hillside.
     */
    private Layout buildSpawnLayout() {
        Layout lake = new Layout();
        lake.shapeFrom(noise.rawHash(1213, -1217));

        double initialAngle = noise.hsh(1187, -1193) * TWO_PI;
        int bestX = (int) Math.round(Math.cos(initialAngle) * 35.0);
        int bestZ = (int) Math.round(Math.sin(initialAngle) * 35.0);
        double bestScore = Double.MAX_VALUE;

        for (int candidate = 0; candidate < 32; candidate++) {
            double angle = initialAngle + candidate * 2.399963229728653;
            double distance =
                102.0 + noise.hsh(1277 + candidate * 17, -1283 - candidate * 19) * 52.0;
            int x = (int) Math.round(Math.cos(angle) * distance);
            int z = (int) Math.round(Math.sin(angle) * distance);
            double centerT = spawnIsland.distanceTo(x, z);
            double centerHeight = spawnIsland.heightAt(x, z, centerT);

            double worstT = centerT;
            double spread = 0.0;
            for (int direction = 0; direction < 8; direction++) {
                double rimAngle = direction * Math.PI / 4.0;
                int rimX = x + (int) Math.round(Math.cos(rimAngle) * SITE_PROBE_RADIUS);
                int rimZ = z + (int) Math.round(Math.sin(rimAngle) * SITE_PROBE_RADIUS);
                double rimT = spawnIsland.distanceTo(rimX, rimZ);
                worstT = Math.max(worstT, rimT);
                spread = Math.max(
                    spread, Math.abs(spawnIsland.heightAt(rimX, rimZ, rimT) - centerHeight)
                );
            }
            if (worstT > 0.90) continue;

            // Flat, comfortably inland, and high enough above the sea that the bowl fits.
            double score =
                spread * 1.2 +
                Math.abs(centerT - 0.55) * 5.0 +
                Math.max(0.0, MIN_SITE_HEIGHT - centerHeight) * 0.8 +
                centerHeight * 0.04;
            if (score >= bestScore) continue;
            bestScore = score;
            bestX = x;
            bestZ = z;
        }

        lake.centerX = bestX;
        lake.centerZ = bestZ;
        lake.waterLevel = waterLevelFor(
            spawnIsland.heightAt(bestX, bestZ, spawnIsland.distanceTo(bestX, bestZ))
        );
        return lake;
    }

    private Layout gridLayout(GridIslandSample grid) {
        long ownerKey = ownerHash(grid);
        LayoutCache cache = gridLayouts.get();
        Layout cached = cache.find(ownerKey);
        if (cached != null) return cached;

        Layout lake = cache.claim();
        buildGridLayout(grid, ownerKey, lake);
        return lake;
    }

    /**
     * Picks the flattest of eight candidate sites on the island's inner slope, keeping the
     * footprint clear of the coast.
     */
    private void buildGridLayout(GridIslandSample grid, long ownerKey, Layout lake) {
        lake.shapeFrom(ownerKey);

        GridIslandSample probe = siteProbe.get();
        double initialAngle = Hashing.frac(Hashing.mix64(ownerKey)) * TWO_PI;
        int bestX = (int) Math.round(grid.centerX);
        int bestZ = (int) Math.round(grid.centerZ);
        double bestHeight = grid.height;
        double bestScore = Double.MAX_VALUE;

        for (int candidate = 0; candidate < 8; candidate++) {
            // Re-mixed per candidate, so the eight sites are independent instead of eight
            // overlapping windows of the same hash.
            long siteHash = Hashing.mix64(ownerKey + candidate * 0x9E3779B97F4A7C15L);
            double angle = initialAngle
                + candidate * (TWO_PI / 8.0)
                + (Hashing.frac(siteHash) - 0.5) * 0.35;
            double distance = grid.islandRadius *
                (0.30 + Hashing.fracLogical(siteHash, 24) * 0.16);
            int x = (int) Math.round(grid.centerX + Math.cos(angle) * distance);
            int z = (int) Math.round(grid.centerZ + Math.sin(angle) * distance);
            gridIslands.sample(x, z, probe);
            double centerHeight = probe.height;

            double spread = 0.0;
            double lowestRim = centerHeight;
            for (int direction = 0; direction < 4; direction++) {
                double rimAngle = angle + direction * (Math.PI / 2.0);
                int rimX = x + (int) Math.round(Math.cos(rimAngle) * SITE_PROBE_RADIUS);
                int rimZ = z + (int) Math.round(Math.sin(rimAngle) * SITE_PROBE_RADIUS);
                gridIslands.sample(rimX, rimZ, probe);
                spread = Math.max(spread, Math.abs(probe.height - centerHeight));
                lowestRim = Math.min(lowestRim, probe.height);
            }

            // Uneven ground costs a little, a shore that would spill into the sea costs a
            // lot, and ground too low to hold a proper bowl costs something in between.
            double score =
                spread * 1.2 +
                Math.max(0.0, 3.0 - lowestRim) * 6.0 +
                Math.max(0.0, MIN_SITE_HEIGHT - centerHeight) * 0.8;
            if (score >= bestScore) continue;
            bestScore = score;
            bestX = x;
            bestZ = z;
            bestHeight = centerHeight;
        }

        lake.centerX = bestX;
        lake.centerZ = bestZ;
        lake.waterLevel = waterLevelFor(bestHeight);
        lake.ownerKey = ownerKey;
    }

    /**
     * The water surface sits at the land height of the lake site, which is what makes the
     * shore flush. The floor keeps it clear of the ocean so a lake never merges with the sea.
     */
    private int waterLevelFor(double siteHeight) {
        return seaLevel + Math.max(3, (int) Math.round(siteHeight));
    }

    private long ownerHash(GridIslandSample grid) {
        return noise.rawHash(
            (int) Math.round(grid.centerX) * 37 + 127,
            (int) Math.round(grid.centerZ) * 41 - 131
        );
    }

    int gridLakeCenterX(GridIslandSample grid) {
        return gridLayout(grid).centerX;
    }

    int gridLakeCenterZ(GridIslandSample grid) {
        return gridLayout(grid).centerZ;
    }

    private static int rotateX(int x, int z, int quarterTurn) {
        return switch (quarterTurn & 3) {
            case 1 -> z;
            case 2 -> -x;
            case 3 -> -z;
            default -> x;
        };
    }

    private static int rotateZ(int x, int z, int quarterTurn) {
        return switch (quarterTurn & 3) {
            case 1 -> -x;
            case 2 -> -z;
            case 3 -> x;
            default -> z;
        };
    }

    private static boolean insideLayoutSquare(int x, int z, Layout lake) {
        return Math.abs(x - lake.centerX) <= lake.reach
            && Math.abs(z - lake.centerZ) <= lake.reach;
    }

    public static int clayBlocksPerLake() {
        return CLAY_BLOCKS_PER_LAKE;
    }
}
