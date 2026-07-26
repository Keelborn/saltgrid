package com.jokerdayn.swworldgencore.worldgen.decor;

import static com.jokerdayn.swworldgencore.worldgen.decor.DecorSettings.BOULDER_EDGE_MARGIN;
import static com.jokerdayn.swworldgencore.worldgen.decor.DecorSettings.BOULDER_EMBED;
import static com.jokerdayn.swworldgencore.worldgen.decor.DecorSettings.BOULDER_HEIGHT_RATIO;
import static com.jokerdayn.swworldgencore.worldgen.decor.DecorSettings.BOULDER_MAX_COUNT;
import static com.jokerdayn.swworldgencore.worldgen.decor.DecorSettings.BOULDER_MAX_GROUND_H;
import static com.jokerdayn.swworldgencore.worldgen.decor.DecorSettings.BOULDER_MAX_ORE_FRACTION;
import static com.jokerdayn.swworldgencore.worldgen.decor.DecorSettings.BOULDER_MAX_RADIUS;
import static com.jokerdayn.swworldgencore.worldgen.decor.DecorSettings.BOULDER_MAX_SLOPE;
import static com.jokerdayn.swworldgencore.worldgen.decor.DecorSettings.BOULDER_MIN_COUNT;
import static com.jokerdayn.swworldgencore.worldgen.decor.DecorSettings.BOULDER_MIN_ORE_FRACTION;
import static com.jokerdayn.swworldgencore.worldgen.decor.DecorSettings.BOULDER_MIN_RADIUS;
import static com.jokerdayn.swworldgencore.worldgen.decor.DecorSettings.BOULDER_PLACEMENT_TRIES;
import static com.jokerdayn.swworldgencore.worldgen.decor.DecorSettings.BOULDER_SEPARATION;
import static com.jokerdayn.swworldgencore.worldgen.decor.DecorSettings.BOULDER_SIZE;
import static com.jokerdayn.swworldgencore.worldgen.terrain.IslandSettings.CELL;
import static com.jokerdayn.swworldgencore.worldgen.terrain.IslandSettings.SPAWN_ISLAND_RADIUS;

import com.jokerdayn.swworldgencore.diagnostics.CacheId;
import com.jokerdayn.swworldgencore.diagnostics.Counter;
import com.jokerdayn.swworldgencore.diagnostics.GeneratorDiagnostics;
import com.jokerdayn.swworldgencore.diagnostics.Token;
import com.jokerdayn.swworldgencore.registry.ModBlocks;
import com.jokerdayn.swworldgencore.worldgen.noise.Hashing;
import com.jokerdayn.swworldgencore.worldgen.terrain.TerrainBlocks;
import com.jokerdayn.swworldgencore.worldgen.terrain.TerrainColumnSampler;
import com.jokerdayn.swworldgencore.worldgen.terrain.TerrainContext;
import java.util.Iterator;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Ore boulders: rounded stone domes veined with bronze ore, scattered over island interiors.
 *
 * <p>A boulder can straddle chunk borders, so it is never written from a neighbouring
 * chunk. Instead every chunk independently derives the island's full boulder list and draws
 * only the slice of each dome that lies inside its own bounds. That is what makes the rocks
 * seamless without any cross-chunk writes and without depending on generation order.</p>
 */
public final class BoulderDecorator {

    private static final int LAYOUT_CACHE_LIMIT = 2048;
    private static final int LAYOUT_TRIM_BATCH = 256;

    private final GeneratorDiagnostics diagnostics;
    private final TerrainContext terrain;
    private final ConcurrentHashMap<Long, BoulderLayout> layouts = new ConcurrentHashMap<>();

    public BoulderDecorator(TerrainContext terrain, GeneratorDiagnostics diagnostics) {
        this.terrain = terrain;
        this.diagnostics = diagnostics;
    }

    // -------------------------------------------------------------------------
    // Entry point
    // -------------------------------------------------------------------------

    /** Draws every boulder slice that intersects this chunk. */
    public void decorate(WorldGenLevel level, int chunkX, int chunkZ, Token benchmark) {
        int minX = chunkX * 16;
        int minZ = chunkZ * 16;
        int maxX = minX + 15;
        int maxZ = minZ + 15;
        double reach = BOULDER_MAX_RADIUS * BOULDER_SIZE + 2.0;

        int cellX = Math.floorDiv(minX + 8, CELL);
        int cellZ = Math.floorDiv(minZ + 8, CELL);

        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                int cx = cellX + dx;
                int cz = cellZ + dz;
                if (!terrain.gridIslands.hasIsland(cx, cz)) continue;
                // Mountain islands are skipped wholesale, and volcanoes get their own lava
                // flows and spires instead.
                if (terrain.gridIslands.isMountainIsland(cx, cz)) continue;

                double radius = terrain.gridIslands.islandRadius(cx, cz);
                int islandX = terrain.gridIslands.islandCenterX(cx, cz);
                int islandZ = terrain.gridIslands.islandCenterZ(cx, cz);
                if (terrain.gridIslands.isVolcano(cx, cz, islandX, islandZ)) continue;

                if (!diskTouchesChunk(islandX, islandZ, radius + reach, minX, minZ, maxX, maxZ)) {
                    continue;
                }
                placeIslandBoulders(
                    level, islandX, islandZ, radius,
                    terrain.gridIslands.islandHash(cx, cz),
                    minX, minZ, maxX, maxZ, benchmark
                );
            }
        }

        // The spawn island is a separate system; its mountain is excluded by the height
        // check inside the site validation rather than by an island-level flag.
        if (diskTouchesChunk(0, 0, SPAWN_ISLAND_RADIUS + reach, minX, minZ, maxX, maxZ)) {
            placeIslandBoulders(
                level, 0, 0, SPAWN_ISLAND_RADIUS, terrain.noise.rawHash(0, 0),
                minX, minZ, maxX, maxZ, benchmark
            );
        }
    }

    /** Every valid boulder position on the spawn island, for the {@code /boulder} command. */
    public int[][] spawnIslandPositions() {
        BoulderLayout layout =
            layoutFor(0, 0, SPAWN_ISLAND_RADIUS, terrain.noise.rawHash(0, 0), null);
        int[][] result = new int[layout.count()][2];
        for (int i = 0; i < layout.count(); i++) {
            result[i][0] = layout.x(i);
            result[i][1] = layout.z(i);
        }
        return result;
    }

    private static boolean diskTouchesChunk(
        int centerX,
        int centerZ,
        double radius,
        int minX,
        int minZ,
        int maxX,
        int maxZ
    ) {
        double nearestX = Mth.clamp((double) centerX, minX, maxX);
        double nearestZ = Mth.clamp((double) centerZ, minZ, maxZ);
        double dx = centerX - nearestX;
        double dz = centerZ - nearestZ;
        return dx * dx + dz * dz <= radius * radius;
    }

    private void placeIslandBoulders(
        WorldGenLevel level,
        int islandX,
        int islandZ,
        double radius,
        long islandHash,
        int minX,
        int minZ,
        int maxX,
        int maxZ,
        Token benchmark
    ) {
        BoulderLayout layout = layoutFor(islandX, islandZ, radius, islandHash, benchmark);
        for (int i = 0; i < layout.count(); i++) {
            double boulderRadius = layout.radius(i);
            if (layout.x(i) + boulderRadius < minX
                || layout.x(i) - boulderRadius > maxX
                || layout.z(i) + boulderRadius < minZ
                || layout.z(i) - boulderRadius > maxZ) {
                continue;
            }
            placeBoulder(
                level, layout.x(i), layout.z(i), boulderRadius, layout.hash(i),
                minX, minZ, maxX, maxZ, benchmark
            );
        }
    }

    // -------------------------------------------------------------------------
    // Layout
    // -------------------------------------------------------------------------

    private BoulderLayout layoutFor(
        int islandX,
        int islandZ,
        double radius,
        long islandHash,
        Token benchmark
    ) {
        long key = Hashing.columnKey(islandX, islandZ);
        BoulderLayout cached = layouts.get(key);
        diagnostics.cacheAccess(CacheId.BOULDER, cached != null);
        if (cached != null) return cached;

        BoulderLayout created = createLayout(islandX, islandZ, radius, islandHash);
        BoulderLayout raced = layouts.putIfAbsent(key, created);
        if (raced == null) {
            diagnostics.add(benchmark, Counter.BOULDER_LAYOUTS_BUILT, 1L);
        }
        trimLayouts();
        diagnostics.cacheState(CacheId.BOULDER, layouts.size(), LAYOUT_CACHE_LIMIT);
        return raced != null ? raced : created;
    }

    private BoulderLayout createLayout(
        int islandX,
        int islandZ,
        double radius,
        long islandHash
    ) {
        int span = BOULDER_MAX_COUNT - BOULDER_MIN_COUNT + 1;
        int targetCount = BOULDER_MIN_COUNT + (int) (Hashing.frac(islandHash) * span);
        double usableRadius = radius * (1.0 - BOULDER_EDGE_MARGIN);
        BoulderLayout layout = new BoulderLayout();

        for (int attempt = 0;
             attempt < BOULDER_PLACEMENT_TRIES && layout.count() < targetCount;
             attempt++) {
            long boulderHash = terrain.noise.rawHash(
                (int) (islandHash + attempt * 9176L),
                (int) ((islandHash >> 21) + attempt * 7919L)
            );
            double angle = Hashing.frac(boulderHash >> 8) * Math.PI * 2.0;
            // sqrt of a uniform sample spreads centres evenly over the disc area.
            double distance = Math.sqrt(Hashing.frac(boulderHash >> 16)) * usableRadius;
            int boulderX = islandX + (int) Math.round(Math.cos(angle) * distance);
            int boulderZ = islandZ + (int) Math.round(Math.sin(angle) * distance);
            double boulderRadius = (BOULDER_MIN_RADIUS +
                Hashing.frac(boulderHash >> 24) * (BOULDER_MAX_RADIUS - BOULDER_MIN_RADIUS))
                * BOULDER_SIZE;

            if (!isValidSite(boulderX, boulderZ, boulderRadius)) continue;
            if (layout.overlaps(boulderX, boulderZ, boulderRadius, BOULDER_SEPARATION)) continue;
            layout.add(boulderX, boulderZ, boulderRadius, boulderHash);
        }
        return layout;
    }

    /**
     * Site test that depends only on the deterministic terrain field, never on chunk state
     * or generation order — otherwise the same boulder could be accepted by one chunk and
     * rejected by its neighbour.
     */
    private boolean isValidSite(int boulderX, int boulderZ, double radius) {
        int seaLevel = terrain.seaLevel;
        int centerGround = terrain.columns.floorAt(boulderX, boulderZ);
        if (centerGround < seaLevel + 1) return false;
        if (centerGround > seaLevel + BOULDER_MAX_GROUND_H) return false;
        if (terrain.columns.isBeach(boulderX, boulderZ, centerGround)) return false;

        int ringRadius = (int) Math.ceil(radius);
        int minGround = centerGround;
        int maxGround = centerGround;
        for (int[] dir : TerrainColumnSampler.NEIGHBOUR_DIRS) {
            int sampleX = boulderX + dir[0] * ringRadius;
            int sampleZ = boulderZ + dir[1] * ringRadius;
            int ground = terrain.columns.floorAt(sampleX, sampleZ);
            // A rim overhanging water, or sand, means the site is not island interior.
            if (ground < seaLevel + 1) return false;
            if (terrain.columns.isBeach(sampleX, sampleZ, ground)) return false;
            minGround = Math.min(minGround, ground);
            maxGround = Math.max(maxGround, ground);
        }
        return maxGround - minGround <= BOULDER_MAX_SLOPE;
    }

    private void trimLayouts() {
        int size = layouts.size();
        if (size <= LAYOUT_CACHE_LIMIT) return;
        long started = System.nanoTime();
        int toRemove =
            Math.min(LAYOUT_TRIM_BATCH, Math.max(1, size - LAYOUT_CACHE_LIMIT) + LAYOUT_TRIM_BATCH - 1);
        int removed = 0;
        Iterator<Long> iterator = layouts.keySet().iterator();
        while (removed < toRemove && iterator.hasNext()) {
            if (layouts.remove(iterator.next()) != null) removed++;
        }
        if (removed > 0) {
            diagnostics.cacheTrim(
                CacheId.BOULDER, removed, Math.max(0L, System.nanoTime() - started)
            );
        }
    }

    // -------------------------------------------------------------------------
    // Geometry
    // -------------------------------------------------------------------------

    /**
     * Writes the part of one boulder that falls inside the current chunk.
     *
     * <p>The shape is a flattened ellipsoid with noise-driven asymmetry. Each column runs
     * from {@code ground - BOULDER_EMBED} up to the dome surface, so the rock is always
     * rooted in the terrain: no floating blocks, no holes, and no half-buried lumps.</p>
     */
    private void placeBoulder(
        WorldGenLevel level,
        int boulderX,
        int boulderZ,
        double radius,
        long boulderHash,
        int minX,
        int minZ,
        int maxX,
        int maxZ,
        Token benchmark
    ) {
        int seaLevel = terrain.seaLevel;
        // The layout already validated this site against the same deterministic field, so
        // no second ring test is needed here.
        int centerGround = terrain.columns.floorAt(boulderX, boulderZ);
        double radiusY = radius * BOULDER_HEIGHT_RATIO;
        BlockState ore = ModBlocks.BRONZE_ORE.get().defaultBlockState();
        double noiseOffset = Hashing.frac(boulderHash) * 1000.0;
        int reach = (int) Math.ceil(radius) + 1;

        double sizeT = Mth.clamp(
            (radius - BOULDER_MIN_RADIUS * BOULDER_SIZE) /
                ((BOULDER_MAX_RADIUS - BOULDER_MIN_RADIUS) * BOULDER_SIZE),
            0.0,
            1.0
        );
        double oreFraction =
            Mth.lerp(sizeT, BOULDER_MIN_ORE_FRACTION, BOULDER_MAX_ORE_FRACTION);

        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        int writtenBlocks = 0;
        int writtenOreBlocks = 0;

        for (int dx = -reach; dx <= reach; dx++) {
            for (int dz = -reach; dz <= reach; dz++) {
                int wx = boulderX + dx;
                int wz = boulderZ + dz;
                if (wx < minX || wx > maxX || wz < minZ || wz > maxZ) continue;

                double horizontal = Math.sqrt(dx * (double) dx + dz * (double) dz);
                // Direction-dependent radius, so the boulder is not a sphere.
                double effectiveRadius = radius * (0.72 + 0.55 * terrain.noise.fbm(
                    wx * 0.35 + noiseOffset, wz * 0.35 + noiseOffset, 2, 2.0, 0.5
                ));
                if (horizontal > effectiveRadius) continue;

                double norm = horizontal / effectiveRadius;
                double dome = radiusY * Math.sqrt(Math.max(0.0, 1.0 - norm * norm));
                dome *= 0.78 + 0.44 * terrain.noise.fbm(
                    wx * 0.55 + noiseOffset, wz * 0.55 - noiseOffset, 2, 2.0, 0.5
                );

                int localGround = terrain.columns.floorAt(wx, wz);
                if (localGround < seaLevel + 1) continue;
                if (localGround > centerGround + BOULDER_MAX_SLOPE) continue;

                int topY = centerGround + (int) Math.round(dome);
                if (topY < localGround) continue;
                int baseY = localGround - BOULDER_EMBED;

                for (int y = baseY; y <= topY; y++) {
                    boolean interior = y < topY && y > baseY && horizontal < effectiveRadius - 1.0;
                    // Ore is no longer a fixed core. Its share scales with radius, so total
                    // yield grows with the rock, while a coarse 3D noise gathers it into
                    // veins across the whole body and a hash breaks up the vein edges —
                    // which keeps the result seamless between chunks.
                    double veinNoise = terrain.noise.fbm(
                        wx * 0.24 + noiseOffset + y * 0.07,
                        wz * 0.24 - noiseOffset - y * 0.09,
                        3, 2.0, 0.5
                    );
                    double veinWeight = 0.30 + veinNoise * 1.45;
                    boolean shell = !interior && y > baseY;
                    double visibilityWeight = shell ? 0.58 : 1.0;
                    double oreChance =
                        Mth.clamp(oreFraction * veinWeight * visibilityWeight, 0.0, 0.72);
                    boolean oreBlock = y > baseY
                        && Hashing.frac(terrain.noise.hash3(wx, y, wz) ^ boulderHash) < oreChance;

                    cursor.set(wx, y, wz);
                    if (!isOverwritable(level.getBlockState(cursor))) continue;
                    level.setBlock(cursor, oreBlock ? ore : TerrainBlocks.STONE, 2);
                    writtenBlocks++;
                    if (oreBlock) writtenOreBlocks++;
                }
            }
        }

        if (writtenBlocks > 0) {
            diagnostics.add(benchmark, Counter.BOULDER_FRAGMENTS, 1);
            diagnostics.add(benchmark, Counter.BOULDER_BLOCK_WRITES, writtenBlocks);
            diagnostics.add(benchmark, Counter.BOULDER_ORE_WRITES, writtenOreBlocks);
        }
    }

    /** Only natural ground is replaced, so trees and any player build survive. */
    private static boolean isOverwritable(BlockState state) {
        return state.isAir()
            || state.is(Blocks.GRASS_BLOCK)
            || state.is(Blocks.DIRT)
            || state.is(Blocks.SAND)
            || state.is(Blocks.GRAVEL)
            || state.is(Blocks.STONE);
    }
}
