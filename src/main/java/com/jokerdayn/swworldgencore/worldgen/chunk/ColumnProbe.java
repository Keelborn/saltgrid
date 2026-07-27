package com.jokerdayn.swworldgencore.worldgen.chunk;

import static com.jokerdayn.swworldgencore.worldgen.terrain.IslandSettings.VOLCANO_SHELF_T;

import com.jokerdayn.swworldgencore.worldgen.terrain.GridIslandSample;
import com.jokerdayn.swworldgencore.worldgen.terrain.IslandLakeField;
import com.jokerdayn.swworldgencore.worldgen.terrain.IslandLakeSample;
import com.jokerdayn.swworldgencore.worldgen.terrain.SurfacePalette;
import com.jokerdayn.swworldgencore.worldgen.terrain.TerrainBlocks;
import com.jokerdayn.swworldgencore.worldgen.terrain.TerrainContext;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Single-column answers for {@code getBaseHeight} and {@code getBaseColumn}.
 *
 * <p>Minecraft asks these questions outside chunk generation — structure placement, spawn
 * point searches, {@code /locate}, mob spawning rules — and expects them to agree with what
 * {@link ChunkTerrainBuilder} actually writes. They therefore share
 * {@link ChunkTerrainBuilder#dirtLayersFor} and the same palettes rather than
 * reimplementing the layering.</p>
 */
public final class ColumnProbe {

    /** Scratch per calling thread; never handed out, so nothing can alias it. */
    private static final ThreadLocal<GridIslandSample> SCRATCH =
        ThreadLocal.withInitial(GridIslandSample::new);
    private static final ThreadLocal<IslandLakeSample> LAKE_SCRATCH =
        ThreadLocal.withInitial(IslandLakeSample::new);

    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    private ColumnProbe() {}

    /** First y above the solid terrain, matching {@code Heightmap} semantics. */
    public static int baseHeight(
        TerrainContext terrain,
        int x,
        int z,
        Heightmap.Types heightmapType
    ) {
        int seaLevel = terrain.seaLevel;
        double spawnDistance = terrain.spawnIsland.distanceTo(x, z);
        double spawnHeight = terrain.spawnIsland.heightAt(x, z, spawnDistance);
        GridIslandSample sample = SCRATCH.get();
        terrain.gridIslands.sample(x, z, sample);
        IslandLakeSample lake = LAKE_SCRATCH.get();
        int floor = terrain.columns.computeFloor(
            x, z, spawnDistance, spawnHeight, sample, lake
        );

        boolean oceanFloorQuery =
            heightmapType == Heightmap.Types.OCEAN_FLOOR ||
            heightmapType == Heightmap.Types.OCEAN_FLOOR_WG;
        if (oceanFloorQuery) {
            boolean onIsland = spawnHeight > 0.0 || sample.height > 0.5;
            // Submerged island slopes carry a waterlogged slab one block above the floor,
            // so the ocean floor there is one higher than the raw terrain height.
            if (floor < seaLevel && onIsland && isOnSlope(terrain, x, z, floor)) {
                return floor + 2;
            }
            return floor + 1;
        }

        // Non-ocean heightmaps report the top of the water, or of the lava lake.
        int surface = Math.max(floor, seaLevel);
        if (sample.crater) surface = Math.max(surface, sample.lavaLevel);
        if (lake.water) surface = Math.max(surface, lake.waterLevel);
        return surface + 1;
    }

    private static boolean isOnSlope(TerrainContext terrain, int x, int z, int floor) {
        return floor < terrain.columns.floorAt(x - 1, z)
            || floor < terrain.columns.floorAt(x + 1, z)
            || floor < terrain.columns.floorAt(x, z - 1)
            || floor < terrain.columns.floorAt(x, z + 1);
    }

    /** Full material column, used wherever Minecraft inspects terrain outside a chunk. */
    public static NoiseColumn baseColumn(
        TerrainContext terrain,
        int x,
        int z,
        LevelHeightAccessor level
    ) {
        int seaLevel = terrain.seaLevel;
        double spawnDistance = terrain.spawnIsland.distanceTo(x, z);
        double spawnHeight = terrain.spawnIsland.heightAt(x, z, spawnDistance);
        GridIslandSample sample = SCRATCH.get();
        terrain.gridIslands.sample(x, z, sample);
        IslandLakeSample lake = LAKE_SCRATCH.get();

        double gridDistance = sample.normalizedDistance;
        double gridHeight = sample.height;
        boolean volcano = sample.volcano;
        boolean crater = sample.crater;
        int lavaLevel = sample.lavaLevel;
        double centerX = sample.centerX;
        double centerZ = sample.centerZ;

        int floor = terrain.columns.computeFloor(
            x, z, spawnDistance, spawnHeight, sample, lake
        );
        boolean onIsland = spawnHeight > 0 || gridHeight > 0.5;

        int minNeighbor = floor;
        boolean onSlope = false;
        if (volcano || (floor < seaLevel && onIsland)) {
            int west = terrain.columns.floorAt(x - 1, z);
            int east = terrain.columns.floorAt(x + 1, z);
            int north = terrain.columns.floorAt(x, z - 1);
            int south = terrain.columns.floorAt(x, z + 1);
            minNeighbor = Math.min(Math.min(west, east), Math.min(north, south));
            onSlope = floor < west || floor < east || floor < north || floor < south;
        }

        int dirtLayers = ChunkTerrainBuilder.dirtLayersFor(
            volcano,
            gridDistance,
            Math.max(0, floor - minNeighbor),
            floor >= seaLevel && onIsland,
            spawnDistance
        );
        boolean beach = terrain.columns.isBeach(x, z, floor);
        boolean volcanicStrata = volcano && gridDistance <= VOLCANO_SHELF_T;

        BlockState lakeSurface = IslandLakeField.surfaceOverride(lake);
        BlockState surface = lakeSurface != null
            ? lakeSurface
            : volcano
                ? terrain.volcanic.surface(
                    x, z, floor, gridDistance, crater, lavaLevel, centerX, centerZ)
                : terrain.surface.surface(
                    x, z, floor, spawnDistance, gridHeight, gridDistance, beach);
        BlockState flatSubsurface = volcanicStrata
            ? null
            : volcano
                ? terrain.volcanic.shelfSubsurface(x, z)
                : terrain.surface.subsurface(
                    x, z, floor, spawnDistance, spawnHeight, gridHeight, beach);
        double fissure = volcanicStrata ? terrain.volcanic.fissureAt(x, z) : 0.0;
        int strataShift = volcanicStrata ? terrain.volcanic.strataShiftAt(x, z) : 0;

        boolean underwaterSlab = floor < seaLevel && onSlope && onIsland;
        int seagrass = floor < seaLevel && !underwaterSlab
            ? terrain.surface.seagrassKind(x, z, floor)
            : 0;
        BlockState slab = underwaterSlab
            ? SurfacePalette.slabFor(surface)
                .setValue(SlabBlock.TYPE, SlabType.BOTTOM)
                .setValue(SlabBlock.WATERLOGGED, true)
            : null;

        int minY = level.getMinBuildHeight();
        int maxY = level.getMaxBuildHeight();
        BlockState[] column = new BlockState[maxY - minY];
        for (int y = minY; y < maxY; y++) {
            BlockState state;
            if (y == minY) {
                state = TerrainBlocks.BEDROCK;
            } else if (y < floor - dirtLayers) {
                state = TerrainBlocks.STONE;
            } else if (y < floor) {
                state = volcanicStrata
                    ? terrain.volcanic.subsurface(
                        x, y, z, gridDistance, crater, lavaLevel, fissure, strataShift)
                    : flatSubsurface;
            } else if (y == floor) {
                state = surface;
            } else if (crater && y <= lavaLevel) {
                state = TerrainBlocks.LAVA;
            } else if (lake.water && y <= lake.waterLevel) {
                state = TerrainBlocks.WATER;
            } else if (y <= seaLevel && floor < seaLevel) {
                if (y == floor + 1 && underwaterSlab) {
                    state = slab;
                } else if (y == floor + 1 && seagrass == 1) {
                    state = TerrainBlocks.SEAGRASS;
                } else if (y == floor + 1 && seagrass == 2) {
                    state = TerrainBlocks.TALL_SEAGRASS_LOWER;
                } else if (y == floor + 2 && seagrass == 2) {
                    state = TerrainBlocks.TALL_SEAGRASS_UPPER;
                } else {
                    state = TerrainBlocks.WATER;
                }
            } else {
                state = AIR;
            }
            column[y - minY] = state;
        }
        return new NoiseColumn(minY, column);
    }
}
