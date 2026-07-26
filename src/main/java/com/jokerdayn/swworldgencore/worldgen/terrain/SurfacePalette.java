package com.jokerdayn.swworldgencore.worldgen.terrain;

import static com.jokerdayn.swworldgencore.worldgen.terrain.IslandSettings.SPAWN_ISLAND_MAX_T;

import com.jokerdayn.swworldgencore.worldgen.noise.TerrainNoise;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** Material choice for non-volcanic terrain: sea floor, beaches, island soil and plants. */
public final class SurfacePalette {

    /** Sand reaches this far below sea level around island shores. */
    private static final int SHALLOW_SAND_DEPTH = 6;

    private final TerrainNoise noise;
    private final int seaLevel;

    public SurfacePalette(TerrainNoise noise, int seaLevel) {
        this.noise = noise;
        this.seaLevel = seaLevel;
    }

    /**
     * Top block of a column.
     *
     * @param beach whether {@link TerrainColumnSampler#isBeach} accepted this column
     */
    public BlockState surface(
        int x,
        int z,
        int floor,
        double spawnDistance,
        double gridHeight,
        double gridDistance,
        boolean beach
    ) {
        boolean nearIsland = spawnDistance < SPAWN_ISLAND_MAX_T ||
            (gridDistance <= 1.0 && gridHeight > 0.01);
        if (!nearIsland) return seaFloor(x, z, floor);
        if (floor < seaLevel) {
            // A shallow sandy skirt makes the transition from beach to sea floor readable.
            if (seaLevel - floor <= SHALLOW_SAND_DEPTH) return TerrainBlocks.SAND;
            return seaFloor(x, z, floor);
        }
        if (beach) return TerrainBlocks.SAND;
        return TerrainBlocks.GRASS_BLOCK;
    }

    /** Open sea floor: sand in the shallows grading into gravel with depth. */
    public BlockState seaFloor(int x, int z, int floor) {
        int depth = seaLevel - floor;
        double depthNorm = Mth.clamp((double) (depth - 4) / 50.0, 0.0, 1.0);
        double n =
            noise.fbm(x * 0.016, z * 0.016, 3, 2.0, 0.5) +
            noise.fbm(x * 0.08, z * 0.08, 2, 2.0, 0.5) * 0.2;
        // Deeper water shifts the threshold so gravel wins further out.
        double threshold = 0.7 - depthNorm * 0.2;
        boolean gravel = n > threshold;

        double detail = noise.hsh(x * 7, z * 13);
        if (gravel) {
            return detail < 0.03 ? TerrainBlocks.COBBLESTONE : TerrainBlocks.GRAVEL;
        }
        return detail < 0.02 ? TerrainBlocks.CLAY : TerrainBlocks.SAND;
    }

    /**
     * Block directly beneath the surface layer. Never {@code null}: callers used to have
     * to substitute stone themselves.
     */
    public BlockState subsurface(
        int x,
        int z,
        int floor,
        double spawnDistance,
        double spawnHeight,
        double gridHeight,
        boolean beach
    ) {
        if (beach) return TerrainBlocks.SAND;
        if (floor >= seaLevel) {
            boolean onIsland = spawnHeight > 0.0 || gridHeight > 0.5;
            return onIsland ? TerrainBlocks.DIRT : TerrainBlocks.SAND;
        }
        if (spawnDistance < SPAWN_ISLAND_MAX_T || gridHeight > 0.01) {
            return noise.hsh(x * 11, z * 17) < 0.6 ? TerrainBlocks.SAND : TerrainBlocks.GRAVEL;
        }
        return TerrainBlocks.STONE;
    }

    /** Matching slab for the underwater step that softens island drop-offs. */
    public static BlockState slabFor(BlockState surface) {
        if (surface.is(Blocks.SAND) || surface.is(Blocks.CLAY)) {
            return TerrainBlocks.SANDSTONE_SLAB;
        }
        if (surface.is(Blocks.GRAVEL) || surface.is(Blocks.COBBLESTONE)) {
            return TerrainBlocks.COBBLESTONE_SLAB;
        }
        return TerrainBlocks.STONE_SLAB;
    }

    /** Whether an underwater column grows seagrass; density falls off with depth. */
    public boolean hasSeagrass(int x, int z, int floor) {
        int depth = seaLevel - floor;
        if (depth < 2 || depth > 40) return false;
        double chance;
        if (depth <= 5) chance = 0.7;
        else if (depth <= 10) chance = 0.5;
        else if (depth <= 20) chance = 0.3;
        else if (depth <= 30) chance = 0.1;
        else chance = 0.04;
        return noise.fbm(x * 0.08, z * 0.08, 2, 2.0, 0.5) > 0.3 &&
            noise.hsh(x * 31, z * 37) < chance;
    }

    /** {@code 0} none, {@code 1} single seagrass, {@code 2} two-block tall seagrass. */
    public int seagrassKind(int x, int z, int floor) {
        if (!hasSeagrass(x, z, floor)) return 0;
        return noise.hsh(x * 17, z * 23) < 0.3 ? 2 : 1;
    }
}
