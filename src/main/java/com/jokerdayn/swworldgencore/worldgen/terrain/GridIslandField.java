package com.jokerdayn.swworldgencore.worldgen.terrain;

import static com.jokerdayn.swworldgencore.worldgen.terrain.IslandSettings.CELL;
import static com.jokerdayn.swworldgencore.worldgen.terrain.IslandSettings.GRID_ISLAND_CENTER_BASE;
import static com.jokerdayn.swworldgencore.worldgen.terrain.IslandSettings.GRID_ISLAND_CENTER_JITTER;
import static com.jokerdayn.swworldgencore.worldgen.terrain.IslandSettings.GRID_ISLAND_CHANCE;
import static com.jokerdayn.swworldgencore.worldgen.terrain.IslandSettings.GRID_ISLAND_HEIGHT_SPREAD;
import static com.jokerdayn.swworldgencore.worldgen.terrain.IslandSettings.GRID_ISLAND_MIN_HEIGHT;
import static com.jokerdayn.swworldgencore.worldgen.terrain.IslandSettings.GRID_ISLAND_MIN_RADIUS;
import static com.jokerdayn.swworldgencore.worldgen.terrain.IslandSettings.GRID_ISLAND_MOUNTAIN_CHANCE;
import static com.jokerdayn.swworldgencore.worldgen.terrain.IslandSettings.GRID_ISLAND_RADIUS_SPREAD;
import static com.jokerdayn.swworldgencore.worldgen.terrain.IslandSettings.VOLCANO_CHANCE;
import static com.jokerdayn.swworldgencore.worldgen.terrain.IslandSettings.VOLCANO_CONE_HEIGHT;
import static com.jokerdayn.swworldgencore.worldgen.terrain.IslandSettings.VOLCANO_CRATER_RADIUS;
import static com.jokerdayn.swworldgencore.worldgen.terrain.IslandSettings.VOLCANO_GULLY_COUNT;
import static com.jokerdayn.swworldgencore.worldgen.terrain.IslandSettings.VOLCANO_GULLY_DEPTH;
import static com.jokerdayn.swworldgencore.worldgen.terrain.IslandSettings.VOLCANO_LAVA_ABOVE_SEA;
import static com.jokerdayn.swworldgencore.worldgen.terrain.IslandSettings.VOLCANO_LAVA_RADIUS;
import static com.jokerdayn.swworldgencore.worldgen.terrain.IslandSettings.VOLCANO_MIN_DISTANCE;
import static com.jokerdayn.swworldgencore.worldgen.terrain.IslandSettings.VOLCANO_PARASITIC_CONES;
import static com.jokerdayn.swworldgencore.worldgen.terrain.IslandSettings.VOLCANO_PARASITIC_HEIGHT;
import static com.jokerdayn.swworldgencore.worldgen.terrain.IslandSettings.VOLCANO_RIM_OUTER_RADIUS;

import com.jokerdayn.swworldgencore.worldgen.noise.TerrainNoise;
import net.minecraft.util.Mth;

/**
 * The infinite lattice of islands: one candidate per {@link IslandSettings#CELL}-sized
 * cell, a fraction of which are promoted to active volcanoes.
 *
 * <p>Everything a cell owns — whether it holds an island, the island's radius, centre,
 * hill height, mountain flag and volcano flag — is a pure function of the cell
 * coordinates and the seed. Those derivations used to be copy-pasted across five call
 * sites; they now live here once, which is what guarantees the terrain shaper, the
 * biome classifier, the boulder placer and the {@code /island} and {@code /volcano}
 * commands all agree about where the islands are.</p>
 */
public final class GridIslandField {

    /** Cell rings searched by the locator commands (~131k blocks). */
    public static final int MAX_CELL_SEARCH_RADIUS = 64;

    private final TerrainNoise noise;
    private final int seaLevel;

    public GridIslandField(TerrainNoise noise, int seaLevel) {
        this.noise = noise;
        this.seaLevel = seaLevel;
    }

    // -------------------------------------------------------------------------
    // Per-cell derivations — the single source of truth
    // -------------------------------------------------------------------------

    /** Hash that both gates island existence and drives its radius. */
    private double radiusHash(int cellX, int cellZ) {
        return noise.hsh(cellX * 11, cellZ * 13);
    }

    /** Whether this cell holds an island candidate at all. */
    public boolean hasIsland(int cellX, int cellZ) {
        return radiusHash(cellX, cellZ) <= GRID_ISLAND_CHANCE;
    }

    /** Radius in blocks; only meaningful when {@link #hasIsland} is true. */
    public double islandRadius(int cellX, int cellZ) {
        return GRID_ISLAND_MIN_RADIUS + radiusHash(cellX, cellZ) * GRID_ISLAND_RADIUS_SPREAD;
    }

    public int islandCenterX(int cellX, int cellZ) {
        return cellX * CELL +
            GRID_ISLAND_CENTER_BASE +
            (int) (noise.hsh(cellX * 2, cellZ * 2) * GRID_ISLAND_CENTER_JITTER);
    }

    public int islandCenterZ(int cellX, int cellZ) {
        return cellZ * CELL +
            GRID_ISLAND_CENTER_BASE +
            (int) (noise.hsh(cellX * 2 + 1, cellZ * 2 + 1) * GRID_ISLAND_CENTER_JITTER);
    }

    private double islandHillHeight(int cellX, int cellZ) {
        return GRID_ISLAND_MIN_HEIGHT +
            noise.hsh(cellX * 17, cellZ * 19) * GRID_ISLAND_HEIGHT_SPREAD;
    }

    /** Islands flagged as mountains grow a ridged peak instead of soft hills. */
    public boolean isMountainIsland(int cellX, int cellZ) {
        return noise.hsh(cellX * 23, cellZ * 29) < GRID_ISLAND_MOUNTAIN_CHANCE;
    }

    /** Stable per-island hash, used for boulder layouts and clearings. */
    public long islandHash(int cellX, int cellZ) {
        return noise.rawHash(cellX * 37, cellZ * 41);
    }

    /**
     * Whether this island is an active volcano. Requires both a minimum distance from
     * the world origin (so the spawn area stays calm) and the rarity roll.
     */
    public boolean isVolcano(int cellX, int cellZ, int centerX, int centerZ) {
        double originDistance =
            Math.sqrt((double) centerX * centerX + (double) centerZ * centerZ);
        return originDistance >= VOLCANO_MIN_DISTANCE &&
            noise.hsh(cellX * 71 + 19, cellZ * 73 - 23) < VOLCANO_CHANCE;
    }

    /** Convenience overload that derives the centre itself. */
    public boolean isVolcano(int cellX, int cellZ) {
        return isVolcano(cellX, cellZ, islandCenterX(cellX, cellZ), islandCenterZ(cellX, cellZ));
    }

    // -------------------------------------------------------------------------
    // Column sampling
    // -------------------------------------------------------------------------

    /**
     * Fills {@code out} with the island state of one column.
     *
     * <p>Only the 3x3 cell neighbourhood is searched: an island centre sits at most
     * {@code 1280} blocks into its own {@code 2048}-wide cell and reaches at most
     * {@code ~300} blocks, so no island from further away can cover this column.</p>
     */
    public void sample(int x, int z, GridIslandSample out) {
        int cellX = Math.floorDiv(x, CELL);
        int cellZ = Math.floorDiv(z, CELL);

        double bestDistSq = Double.MAX_VALUE;
        double bestRadius = 0.0;
        double bestMaxHeight = 0.0;
        boolean bestMountain = false;
        int bestCellX = 0;
        int bestCellZ = 0;
        int bestCenterX = 0;
        int bestCenterZ = 0;

        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                int cx = cellX + dx;
                int cz = cellZ + dz;
                if (!hasIsland(cx, cz)) continue;
                double radius = islandRadius(cx, cz);
                int centerX = islandCenterX(cx, cz);
                int centerZ = islandCenterZ(cx, cz);
                double distSq =
                    (double) (x - centerX) * (x - centerX) +
                    (double) (z - centerZ) * (z - centerZ);
                if (distSq >= bestDistSq) continue;
                if (distSq >= radius * radius) continue;
                bestDistSq = distSq;
                bestRadius = radius;
                bestMaxHeight = islandHillHeight(cx, cz);
                bestMountain = isMountainIsland(cx, cz);
                bestCellX = cx;
                bestCellZ = cz;
                bestCenterX = centerX;
                bestCenterZ = centerZ;
            }
        }

        if (bestRadius < 1) {
            out.clear();
            return;
        }

        double d = Math.sqrt(bestDistSq);
        double t = d / bestRadius;
        double edge = 1.0 - t;
        double falloff = TerrainNoise.smoothstep(edge);

        double hill = TerrainNoise.smoothstepClamped(
            (noise.fbm(
                x * 0.008 + bestCellX * 31.0 + noise.seedOff(bestCellX * 7 + bestCellZ, 1.5),
                z * 0.008 + bestCellZ * 47.0 + noise.seedOff(bestCellX * 11 + bestCellZ, 0.8),
                3, 2.0, 0.55
            ) - 0.15) / 0.7
        );
        double h = hill * bestMaxHeight * falloff;

        if (bestMountain) {
            double ridge = Math.sqrt(noise.ridgeNoise(
                x * 0.012 + bestCellX * 53.0 + noise.seedOff(bestCellX * 13 + bestCellZ, 2.0),
                z * 0.014 + bestCellZ * 67.0 + noise.seedOff(bestCellX * 17 + bestCellZ, 1.3),
                4, 2.0, 0.55
            ));
            double mountainMask = Mth.clamp(1.0 - d / (bestRadius * 0.6), 0.0, 1.0);
            h += bestMaxHeight * 1.5 * ridge * (mountainMask * mountainMask) * falloff;
        }

        boolean volcano = isVolcano(bestCellX, bestCellZ, bestCenterX, bestCenterZ);

        out.islandRadius = bestRadius;
        out.centerX = bestCenterX;
        out.centerZ = bestCenterZ;
        out.volcano = volcano;
        out.crater = false;
        out.lavaLevel = volcano ? seaLevel + VOLCANO_LAVA_ABOVE_SEA : 0;

        if (!volcano) {
            out.normalizedDistance = t;
            out.height = h;
            return;
        }

        shapeVolcano(x, z, bestCellX, bestCellZ, bestCenterX, bestCenterZ, bestRadius, out);
    }

    /**
     * Replaces the plain grid island with a strato-volcano.
     *
     * <p>The original circular island is deliberately discarded: the whole landmass,
     * beach and grassy foot included, follows the warped organic outline so the
     * silhouette never betrays the underlying lattice.</p>
     */
    private void shapeVolcano(
        int x,
        int z,
        int cellX,
        int cellZ,
        int centerX,
        int centerZ,
        double radius,
        GridIslandSample out
    ) {
        double localX = x - centerX;
        double localZ = z - centerZ;

        // Domain warp breaks the circle into a believable coastline.
        double warpX = (noise.fbm(
            x * 0.006 + cellX * 43.0,
            z * 0.006 - cellZ * 37.0,
            3, 2.0, 0.52
        ) - 0.5) * radius * 0.24;
        double warpZ = (noise.fbm(
            x * 0.006 - cellX * 31.0 + 419.0,
            z * 0.006 + cellZ * 47.0 - 271.0,
            3, 2.0, 0.52
        ) - 0.5) * radius * 0.24;
        double warpedX = localX + warpX;
        double warpedZ = localZ + warpZ;
        double angle = Math.atan2(warpedZ, warpedX);
        double outline =
            1.0 +
            0.13 * Math.sin(angle * 3.0 + cellX * 1.9) +
            0.08 * Math.sin(angle * 5.0 - cellZ * 2.1) +
            0.05 * Math.sin(angle * 8.0 + cellX - cellZ);

        // NOTE: the plain-island edge falloff is deliberately *not* reused here. The
        // cone/shelf blend below already accounts for the island edge, so recomputing
        // it (as the pre-refactor code did) produced two dead assignments.
        double t = Math.sqrt(warpedX * warpedX + warpedZ * warpedZ) / (radius * outline);

        double ribs = Math.pow(
            0.5 + 0.5 * Math.sin(angle * 9.0 + cellX * 1.7 - cellZ * 2.3),
            3.0
        );
        double broken = noise.fbm(
            x * 0.018 + cellX * 41.0,
            z * 0.018 - cellZ * 37.0,
            4, 2.0, 0.52
        );

        // The outer part of the island is a walkable plain in its own right rather than
        // a continuation of the cone: low-frequency noise gives soft hills instead of
        // endless steps all the way around the circumference.
        double shelfNoise = noise.fbm(
            x * 0.010 + cellX * 307.0,
            z * 0.010 - cellZ * 313.0,
            3, 2.0, 0.50
        );
        double broadNoise = noise.fbm(
            x * 0.004 - cellX * 149.0,
            z * 0.004 + cellZ * 157.0,
            2, 2.0, 0.50
        );
        double shoreLift = TerrainNoise.smoothstepClamped((1.0 - t) / 0.18);
        double rollingHills =
            Math.pow(Mth.clamp(broadNoise, 0.0, 1.0), 1.35) * 5.0 +
            (shelfNoise - 0.5) * 3.0;
        double inlandMask = TerrainNoise.smoothstepClamped((0.88 - t) / 0.18);
        double islandShelf = 0.4 + shoreLift * 6.2 + inlandMask * rollingHills;

        // The volcano proper only occupies the inner part of the island; the smoothstep
        // creates a wide foothill instead of a sudden wall.
        double coneEdge = 0.68 + (broken - 0.5) * 0.09;
        double coneBlend = TerrainNoise.smoothstepClamped((coneEdge - t) / 0.27);
        double coneLocalT = Mth.clamp(t / Math.max(0.01, coneEdge), 0.0, 1.0);
        // Concave profile of a real stratovolcano (Fuji, Mayon): the summit gains height
        // steeply while the flank flattens into a long apron. Mixing two powers is what
        // produces that classic bell instead of a straight shield or a tower.
        double cone =
            0.60 * Math.pow(1.0 - coneLocalT, 3.1) +
            0.40 * Math.pow(1.0 - coneLocalT, 1.25);
        double ribStrength = (ribs * 0.075 + (broken - 0.5) * 0.07) * coneBlend;
        double coneHeight = islandShelf + VOLCANO_CONE_HEIGHT * cone * (0.94 + ribStrength);
        double height = Mth.lerp(coneBlend, islandShelf, coneHeight);

        double craterT = Math.sqrt(localX * localX + localZ * localZ) / radius;

        height -= gullyDepth(cellX, cellZ, t, craterT, angle, coneEdge, broken);
        height += parasiticCones(x, z, cellX, cellZ, centerX, centerZ, radius);
        height -= valleyDepth(cellX, cellZ, t, angle, shelfNoise);

        // The crater stays a readable bowl; the arbitrariness belongs to the island
        // outline and its foot, not to the lava lake.
        double rim = Math.exp(-Math.pow((craterT - VOLCANO_CRATER_RADIUS) / 0.060, 2.0));
        // Asymmetric rim: on real volcanoes the leeward side is noticeably higher from
        // accumulated tephra. Containment is guaranteed by the caldera seal below, so
        // this asymmetry is purely a silhouette effect.
        double windwardAngle = noise.hsh(cellX * 503, cellZ * 509) * Math.PI * 2.0;
        double rimAsymmetry = 0.8 + 0.45 * (0.5 + 0.5 * Math.cos(angle - windwardAngle));
        height += rim * (7.0 + ribs * 2.6 + broken * 1.8) * rimAsymmetry;

        boolean crater = craterT < VOLCANO_LAVA_RADIUS;
        if (crater) {
            double inner = craterT / VOLCANO_LAVA_RADIUS;
            double innerRough = noise.fbm(x * 0.045 + 901.0, z * 0.045 - 607.0, 3, 2.0, 0.5);
            // The bowl floor always sits below the lake and its inner wall runs straight
            // into the lava.
            height = VOLCANO_LAVA_ABOVE_SEA - 8.0 + inner * inner * 7.0 + innerRough;
        }

        out.normalizedDistance = t;
        out.crater = crater;
        out.height = VolcanoGeometry.sealedCalderaHeight(craterT, height);
    }

    /**
     * Barrancos — radial erosion gullies cut by rain and pyroclastics. They start just
     * below the rim and fade towards the foot, giving the flank its ribbed texture.
     */
    private double gullyDepth(
        int cellX,
        int cellZ,
        double t,
        double craterT,
        double angle,
        double coneEdge,
        double broken
    ) {
        if (craterT <= VOLCANO_RIM_OUTER_RADIUS + 0.03 || t >= coneEdge) return 0.0;

        double gullyMask = 0.0;
        for (int gully = 0; gully < VOLCANO_GULLY_COUNT; gully++) {
            double gullyAngle =
                noise.hsh(cellX * 331 + gully * 43, cellZ * 337 - gully * 47) * Math.PI * 2.0;
            double meander = Math.sin(craterT * 14.0 + gully * 1.9) * 0.055;
            double delta = VolcanoGeometry.angularDistance(angle, gullyAngle + meander);
            double width =
                0.045 + noise.hsh(cellX * 347 + gully, cellZ * 349 - gully) * 0.035;
            double cut = 1.0 - Mth.clamp(delta / width, 0.0, 1.0);
            gullyMask = Math.max(gullyMask, cut * cut);
        }
        // Deeper on the steep mid-flank, vanishing at both the rim and the shore.
        double slopeBand =
            Mth.clamp((t - 0.16) / 0.14, 0.0, 1.0) *
            Mth.clamp((coneEdge - t) / 0.20, 0.0, 1.0);
        return gullyMask * VOLCANO_GULLY_DEPTH * slopeBand * (0.7 + broken * 0.6);
    }

    /**
     * Parasitic cones: small lateral craters halfway up the flank, as on Etna.
     * Gaussian bumps with their own miniature vent.
     */
    private double parasiticCones(
        int x,
        int z,
        int cellX,
        int cellZ,
        int centerX,
        int centerZ,
        double radius
    ) {
        double total = 0.0;
        for (int cone = 0; cone < VOLCANO_PARASITIC_CONES; cone++) {
            double coneAngle =
                noise.hsh(cellX * 401 + cone * 61, cellZ * 409 - cone * 67) * Math.PI * 2.0;
            double coneDistance = radius *
                (0.38 + noise.hsh(cellX * 419 + cone, cellZ * 421 - cone) * 0.14);
            double coneX = centerX + Math.cos(coneAngle) * coneDistance;
            double coneZ = centerZ + Math.sin(coneAngle) * coneDistance;
            double dx = x - coneX;
            double dz = z - coneZ;
            double coneRadius = radius * 0.085;
            double normalized = Math.sqrt(dx * dx + dz * dz) / coneRadius;
            if (normalized >= 1.6) continue;
            double bump = Math.exp(-normalized * normalized * 1.6) * VOLCANO_PARASITIC_HEIGHT;
            if (normalized < 0.30) bump -= (1.0 - normalized / 0.30) * 4.0;
            total += bump;
        }
        return total;
    }

    /**
     * Shallow valleys exist on the plain as walking routes, but their amplitude stays
     * small so the player is not forced to jump 3-5 blocks constantly.
     */
    private double valleyDepth(int cellX, int cellZ, double t, double angle, double shelfNoise) {
        if (t <= 0.52 || t >= 0.88) return 0.0;

        double valleyMask = 0.0;
        for (int valley = 0; valley < 4; valley++) {
            double valleyAngle =
                noise.hsh(cellX * 211 + valley * 31, cellZ * 223 - valley * 37) * Math.PI * 2.0;
            double meander = Math.sin(t * 10.0 + valley * 2.4) * 0.12;
            double delta = VolcanoGeometry.angularDistance(angle, valleyAngle + meander);
            double width =
                0.14 + noise.hsh(cellX * 227 + valley, cellZ * 229 - valley) * 0.08;
            valleyMask = Math.max(valleyMask, 1.0 - Mth.clamp(delta / width, 0.0, 1.0));
        }
        return valleyMask * (1.0 + shelfNoise * 1.4);
    }

    // -------------------------------------------------------------------------
    // Locators — deterministic cell scans that never load a chunk
    // -------------------------------------------------------------------------

    /**
     * Finds a safe standing point on the outer rim of the nearest volcano.
     *
     * @return {@code {targetX, targetZ, centerX, centerZ}}, or {@code null} if none is
     *         within {@code maxCellRadius} cells
     */
    public int[] findNearestVolcano(int x, int z, int maxCellRadius) {
        int originCellX = Math.floorDiv(x, CELL);
        int originCellZ = Math.floorDiv(z, CELL);
        int[] best = null;
        double bestDistanceSq = Double.MAX_VALUE;
        int limit = Math.max(1, maxCellRadius);

        for (int ring = 0; ring <= limit; ring++) {
            for (int dx = -ring; dx <= ring; dx++) {
                for (int dz = -ring; dz <= ring; dz++) {
                    if (ring > 0 && Math.abs(dx) != ring && Math.abs(dz) != ring) continue;

                    int cellX = originCellX + dx;
                    int cellZ = originCellZ + dz;
                    if (!hasIsland(cellX, cellZ)) continue;

                    double radius = islandRadius(cellX, cellZ);
                    int centerX = islandCenterX(cellX, cellZ);
                    int centerZ = islandCenterZ(cellX, cellZ);
                    if (!isVolcano(cellX, cellZ, centerX, centerZ)) continue;

                    double distanceSq =
                        (double) (centerX - x) * (centerX - x) +
                        (double) (centerZ - z) * (centerZ - z);
                    if (distanceSq >= bestDistanceSq) continue;

                    // Just outside the caldera: a view of the lava lake without landing in it.
                    double approachAngle =
                        noise.hsh(cellX * 131 + 7, cellZ * 137 - 11) * Math.PI * 2.0;
                    double approachRadius = radius * (VOLCANO_CRATER_RADIUS + 0.105);
                    bestDistanceSq = distanceSq;
                    best = new int[] {
                        centerX + (int) Math.round(Math.cos(approachAngle) * approachRadius),
                        centerZ + (int) Math.round(Math.sin(approachAngle) * approachRadius),
                        centerX,
                        centerZ,
                    };
                }
            }

            // Cells in every further ring are at least this far away, so once the current
            // best beats that bound no later ring can improve on it.
            if (best != null &&
                !ringCanImprove(x, z, originCellX, originCellZ, ring, bestDistanceSq)) {
                break;
            }
        }
        return best;
    }

    /** Nearest island centre of any kind; falls back to the world origin. */
    public int[] findNearestIslandCenter(int x, int z) {
        int originCellX = Math.floorDiv(x, CELL);
        int originCellZ = Math.floorDiv(z, CELL);
        int[] best = null;
        double bestDistanceSq = Double.MAX_VALUE;

        for (int ring = 0; ring <= MAX_CELL_SEARCH_RADIUS; ring++) {
            for (int dx = -ring; dx <= ring; dx++) {
                for (int dz = -ring; dz <= ring; dz++) {
                    if (ring > 0 && Math.abs(dx) != ring && Math.abs(dz) != ring) continue;
                    int cellX = originCellX + dx;
                    int cellZ = originCellZ + dz;
                    if (!hasIsland(cellX, cellZ)) continue;
                    int centerX = islandCenterX(cellX, cellZ);
                    int centerZ = islandCenterZ(cellX, cellZ);
                    double distanceSq =
                        (double) (centerX - x) * (centerX - x) +
                        (double) (centerZ - z) * (centerZ - z);
                    if (distanceSq < bestDistanceSq) {
                        bestDistanceSq = distanceSq;
                        best = new int[] { centerX, centerZ };
                    }
                }
            }

            if (best != null &&
                !ringCanImprove(x, z, originCellX, originCellZ, ring, bestDistanceSq)) {
                break;
            }
        }
        return best != null ? best : new int[] { 0, 0 };
    }

    /**
     * Whether any cell outside the rings searched so far could still hold a centre
     * closer than {@code bestDistanceSq}. Island centres live in
     * {@code [cell*CELL + 768, cell*CELL + 1280)}, which is what makes this bound tight.
     */
    private static boolean ringCanImprove(
        int x,
        int z,
        int originCellX,
        int originCellZ,
        int searchedRing,
        double bestDistanceSq
    ) {
        long next = (long) searchedRing + 1L;
        double positiveX = ((long) originCellX + next) * CELL + 768.0 - x;
        double negativeX = x - (((long) originCellX - next) * CELL + 1280.0);
        double positiveZ = ((long) originCellZ + next) * CELL + 768.0 - z;
        double negativeZ = z - (((long) originCellZ - next) * CELL + 1280.0);
        double minimum = Math.max(
            0.0,
            Math.min(Math.min(positiveX, negativeX), Math.min(positiveZ, negativeZ))
        );
        return minimum * minimum <= bestDistanceSq;
    }

    // -------------------------------------------------------------------------
    // Clearings
    // -------------------------------------------------------------------------

    /**
     * Whether the column sits inside one of an island's two large clearings, which is
     * what separates the {@code TROPICS} biome from {@code SAVANNA}.
     *
     * @param spawnIslandDistance the {@link SpawnIslandField#distanceTo} value, so this
     *                            method does not have to recompute the expensive warp
     */
    public boolean isClearing(int x, int z, double spawnIslandDistance) {
        int centerX;
        int centerZ;
        double radius;
        long islandHash;

        if (spawnIslandDistance <= 1.0) {
            centerX = 0;
            centerZ = 0;
            radius = IslandSettings.SPAWN_ISLAND_RADIUS;
            islandHash = noise.rawHash(0, 0);
        } else {
            int cellX = Math.floorDiv(x, CELL);
            int cellZ = Math.floorDiv(z, CELL);
            int bestCellX = 0;
            int bestCellZ = 0;
            double bestDistSq = Double.MAX_VALUE;
            double bestRadius = 0.0;

            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    int cx = cellX + dx;
                    int cz = cellZ + dz;
                    if (!hasIsland(cx, cz)) continue;
                    int cix = islandCenterX(cx, cz);
                    int ciz = islandCenterZ(cx, cz);
                    double distSq =
                        (double) (x - cix) * (x - cix) + (double) (z - ciz) * (z - ciz);
                    if (distSq < bestDistSq) {
                        bestDistSq = distSq;
                        bestCellX = cx;
                        bestCellZ = cz;
                        bestRadius = islandRadius(cx, cz);
                    }
                }
            }

            if (bestRadius < 1) return false;
            centerX = islandCenterX(bestCellX, bestCellZ);
            centerZ = islandCenterZ(bestCellX, bestCellZ);
            radius = bestRadius;
            islandHash = islandHash(bestCellX, bestCellZ);
        }

        double relativeX = (x - centerX) / radius;
        double relativeZ = (z - centerZ) / radius;
        for (int i = 0; i < 2; i++) {
            long h = noise.rawHash(
                (int) islandHash + i * 1000,
                (int) (islandHash >> 32) + i * 1000
            );
            double clearingX = ((h & 0xFFFF) / (double) 0xFFFF - 0.5) * 1.2;
            double clearingZ = (((h >> 16) & 0xFFFF) / (double) 0xFFFF - 0.5) * 1.2;
            double clearingRadius = 0.35 + (((h >> 32) & 0xFF) / 255.0) * 0.25;
            double distSq =
                (relativeX - clearingX) * (relativeX - clearingX) +
                (relativeZ - clearingZ) * (relativeZ - clearingZ);
            if (distSq < clearingRadius * clearingRadius) return true;
        }
        return false;
    }
}
