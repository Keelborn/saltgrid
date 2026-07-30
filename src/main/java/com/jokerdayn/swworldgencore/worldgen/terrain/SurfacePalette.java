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

    /** One bounded clay-deposit candidate per this many open-ocean blocks. */
    private static final int OCEAN_CLAY_CELL = 80;

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
        boolean beach,
        int cliffDrop
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
        if (isMountainRock(x, z, floor, cliffDrop)) return mountainRock(x, z);
        return TerrainBlocks.GRASS_BLOCK;
    }

    /**
     * Exposes rock gradually with both altitude and slope. Noise softens the boundary so
     * it reads as broken scree and cliff bands instead of a perfectly level stone ring.
     */
    private boolean isMountainRock(int x, int z, int floor, int cliffDrop) {
        double altitude = TerrainNoise.smoothstepClamped(
            (floor - seaLevel - 22.0) / 34.0
        );
        double steepness = TerrainNoise.smoothstepClamped((cliffDrop - 1.0) / 3.0);
        double breakup =
            noise.fbm(x * 0.045 + 733.0, z * 0.045 - 419.0, 3, 2.0, 0.5) * 0.78 +
            noise.hsh(x * 43, z * 47) * 0.22;
        return altitude * 0.78 + steepness * 0.72 > 0.74 + (breakup - 0.5) * 0.28;
    }

    /** Muted three-block rock palette, clustered rather than salt-and-pepper random. */
    private BlockState mountainRock(int x, int z) {
        double patch = noise.fbm(
            x * 0.075 + 271.0,
            z * 0.075 - 613.0,
            2, 2.0, 0.5
        );
        if (patch > 0.66) return TerrainBlocks.ANDESITE;
        if (patch < 0.31 && noise.hsh(x * 59, z * 61) < 0.34) {
            return TerrainBlocks.COBBLESTONE;
        }
        return TerrainBlocks.STONE;
    }

    /**
     * Open sea floor: sand in the shallows grading into gravel with depth, crossed by
     * occasional broad clay beds rather than isolated one-block specks.
     */
    public BlockState seaFloor(int x, int z, int floor) {
        if (isOceanClayDeposit(x, z)) return TerrainBlocks.CLAY;

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
        return TerrainBlocks.SAND;
    }

    /**
     * A deterministic 12-26 block wide organic clay bed wholly contained in its cell.
     * Keeping each candidate away from the cell edge avoids both one-block fragments and
     * neighbour searches in the hottest surface-classification path.
     */
    public boolean isOceanClayDeposit(int x, int z) {
        int cellX = Math.floorDiv(x, OCEAN_CLAY_CELL);
        int cellZ = Math.floorDiv(z, OCEAN_CLAY_CELL);
        double existence = noise.hsh(cellX * 149 + 17, cellZ * 157 - 23);
        if (existence >= 0.34) return false;

        int localOriginX = cellX * OCEAN_CLAY_CELL;
        int localOriginZ = cellZ * OCEAN_CLAY_CELL;
        double centerX =
            localOriginX + 18.0 + noise.hsh(cellX * 163 + 29, cellZ * 167 - 31) * 44.0;
        double centerZ =
            localOriginZ + 18.0 + noise.hsh(cellX * 173 + 37, cellZ * 179 - 41) * 44.0;
        double radiusX = 7.0 + noise.hsh(cellX * 181 + 43, cellZ * 191 - 47) * 6.0;
        double radiusZ = 6.0 + noise.hsh(cellX * 193 + 53, cellZ * 197 - 59) * 4.5;
        double rotation =
            noise.hsh(cellX * 199 + 61, cellZ * 211 - 67) * Math.PI;

        double dx = x - centerX;
        double dz = z - centerZ;
        double cos = Math.cos(rotation);
        double sin = Math.sin(rotation);
        double rx = dx * cos + dz * sin;
        double rz = -dx * sin + dz * cos;
        double angle = Math.atan2(rz / radiusZ, rx / radiusX);
        double outline =
            1.0 +
            Math.sin(angle * 3.0 + existence * 19.0) * 0.10 +
            Math.sin(angle * 5.0 - existence * 11.0) * 0.055;
        double normalized =
            Math.sqrt((rx * rx) / (radiusX * radiusX) + (rz * rz) / (radiusZ * radiusZ));
        return normalized <= outline;
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
        boolean beach,
        BlockState surface
    ) {
        if (beach) return TerrainBlocks.SAND;
        if (floor >= seaLevel) {
            boolean onIsland = spawnHeight > 0.0 || gridHeight > 0.5;
            if (onIsland && isMountainRock(surface)) {
                return TerrainBlocks.STONE;
            }
            return onIsland ? TerrainBlocks.DIRT : TerrainBlocks.SAND;
        }
        if (spawnDistance < SPAWN_ISLAND_MAX_T || gridHeight > 0.01) {
            return noise.hsh(x * 11, z * 17) < 0.6 ? TerrainBlocks.SAND : TerrainBlocks.GRAVEL;
        }
        return TerrainBlocks.STONE;
    }

    /** Whether a chosen top block belongs to the exposed mountain-rock palette. */
    private static boolean isMountainRock(BlockState surface) {
        return surface.is(Blocks.STONE)
            || surface.is(Blocks.ANDESITE)
            || surface.is(Blocks.COBBLESTONE);
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
