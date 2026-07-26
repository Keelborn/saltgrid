package com.jokerdayn.swworldgencore.worldgen.decor;

import static com.jokerdayn.swworldgencore.worldgen.terrain.IslandSettings.VOLCANO_LAKE_ISLANDS;
import static com.jokerdayn.swworldgencore.worldgen.terrain.IslandSettings.VOLCANO_RIM_OUTER_RADIUS;

import com.jokerdayn.swworldgencore.diagnostics.Counter;
import com.jokerdayn.swworldgencore.diagnostics.GeneratorDiagnostics;
import com.jokerdayn.swworldgencore.diagnostics.Token;
import com.jokerdayn.swworldgencore.worldgen.chunk.ChunkColumnCache;
import com.jokerdayn.swworldgencore.worldgen.chunk.ColumnFlags;
import com.jokerdayn.swworldgencore.worldgen.noise.Hashing;
import com.jokerdayn.swworldgencore.worldgen.terrain.GridIslandSample;
import com.jokerdayn.swworldgencore.worldgen.terrain.TerrainBlocks;
import com.jokerdayn.swworldgencore.worldgen.terrain.TerrainContext;
import com.jokerdayn.swworldgencore.worldgen.terrain.VolcanoGeometry;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The active machinery of a volcano: obsidian domes in the lava lake, lava channels running
 * down to the sea, fumaroles just outside the rim, and volcanic bombs on the ash fields.
 *
 * <p>Each column belongs to at most one of these features. The placers therefore return
 * {@link #NOT_APPLICABLE} when the column is not theirs and a write count when they claimed
 * it, which lets the loop stop at the first match without evaluating any predicate twice.</p>
 *
 * <p>Deliberately no free-standing spires and no random fire: the silhouette is carried by
 * the wide cone and the large flows themselves.</p>
 */
public final class VolcanicFeatureDecorator {

    /** Returned by a placer whose feature does not own this column. */
    private static final int NOT_APPLICABLE = -1;

    /**
     * Which of the radial tongues are still molten. The rest are rendered as frozen black
     * scars by the surface palette instead.
     */
    private static final int[] ACTIVE_FLOWS = { 0, 3 };

    private final TerrainContext terrain;
    private final GeneratorDiagnostics diagnostics;

    public VolcanicFeatureDecorator(TerrainContext terrain, GeneratorDiagnostics diagnostics) {
        this.terrain = terrain;
        this.diagnostics = diagnostics;
    }

    public void decorate(
        WorldGenLevel level,
        int chunkX,
        int chunkZ,
        ChunkColumnCache columns,
        Token benchmark
    ) {
        int minX = chunkX * 16;
        int minZ = chunkZ * 16;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        GridIslandSample sample = new GridIslandSample();
        int[] nearestFlow = new int[1];
        int featureWrites = 0;

        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int wx = minX + lx;
                int wz = minZ + lz;
                int columnIndex = ChunkColumnCache.index(lx, lz);
                if (columns != null
                    && !ColumnFlags.has(columns.flags[columnIndex], ColumnFlags.VOLCANO)) {
                    continue;
                }
                terrain.gridIslands.sample(wx, wz, sample);
                if (!sample.volcano) continue;

                double t = sample.normalizedDistance;
                double craterT = VolcanoGeometry.craterT(wx, wz, sample);
                int floor = columns != null
                    ? columns.floor[columnIndex]
                    : terrain.columns.floorAt(wx, wz);
                long volcanoHash = VolcanoGeometry.volcanoHash(
                    terrain.noise, sample.centerX, sample.centerZ
                );
                double pick = terrain.noise.hsh(wx * 197 + floor, wz * 199 - floor);

                int writes = placeLakeIsland(
                    level, cursor, wx, wz, sample, volcanoHash, sample.lavaLevel, pick
                );
                if (writes == NOT_APPLICABLE) {
                    double flowDistance = VolcanoGeometry.flowDistance(
                        wx, wz, t, sample.centerX, sample.centerZ, volcanoHash, nearestFlow
                    );
                    writes = placeLavaChannel(
                        level, cursor, wx, wz, t, craterT, floor, flowDistance,
                        nearestFlow[0], pick
                    );
                }
                if (writes == NOT_APPLICABLE) {
                    writes = placeFumarole(level, cursor, wx, wz, craterT, floor, pick);
                }
                if (writes == NOT_APPLICABLE) {
                    writes = placeVolcanicBomb(level, cursor, wx, wz, t, floor, pick);
                }
                if (writes > 0) featureWrites += writes;
            }
        }

        diagnostics.add(benchmark, Counter.VOLCANIC_FEATURE_WRITES, featureWrites);
    }

    // -------------------------------------------------------------------------
    // Lava-lake islands
    // -------------------------------------------------------------------------

    /**
     * Stepped obsidian domes with crying-obsidian veins; the central one carries the
     * placeholder reward block on its upper tier.
     */
    private int placeLakeIsland(
        WorldGenLevel level,
        BlockPos.MutableBlockPos cursor,
        int wx,
        int wz,
        GridIslandSample sample,
        long volcanoHash,
        int lavaLevel,
        double pick
    ) {
        for (int island = 0; island < VOLCANO_LAKE_ISLANDS; island++) {
            double angle = Hashing.frac(volcanoHash >> (island * 15 + 5)) * Math.PI * 2.0;
            double orbit = sample.islandRadius * (0.052 + island * 0.018);
            double dx = wx - (sample.centerX + Math.cos(angle) * orbit);
            double dz = wz - (sample.centerZ + Math.sin(angle) * orbit);
            double distanceSq = dx * dx + dz * dz;
            double platformRadius = island == 0 ? 3.2 : 2.4;
            if (distanceSq > platformRadius * platformRadius) continue;

            // Lower tier barely breaks the lava surface.
            cursor.set(wx, lavaLevel + 1, wz);
            level.setBlock(
                cursor,
                pick < 0.18 ? TerrainBlocks.CRYING_OBSIDIAN : TerrainBlocks.OBSIDIAN,
                2
            );
            int writes = 1;

            // Upper tier: a small platform for the reward block to stand on.
            if (Math.sqrt(distanceSq) < platformRadius * 0.48) {
                BlockState top;
                if (distanceSq < 0.55) {
                    top = DecorSettings.VOLCANO_REWARD_BLOCK;
                } else {
                    top = pick < 0.30 ? TerrainBlocks.CRYING_OBSIDIAN : TerrainBlocks.OBSIDIAN;
                }
                cursor.set(wx, lavaLevel + 2, wz);
                level.setBlock(cursor, top, 2);
                writes++;
            }
            return writes;
        }
        return NOT_APPLICABLE;
    }

    // -------------------------------------------------------------------------
    // Lava channels
    // -------------------------------------------------------------------------

    /**
     * Active tongues run from the rim all the way to the ocean, like the flows of Kilauea:
     * a glowing channel high up, a magma crust mid-slope, and fully cooled black rock at
     * the water.
     */
    private int placeLavaChannel(
        WorldGenLevel level,
        BlockPos.MutableBlockPos cursor,
        int wx,
        int wz,
        double t,
        double craterT,
        int floor,
        double flowDistance,
        int nearestFlowIndex,
        double pick
    ) {
        double channelWidth = 0.017 + Mth.clamp((t - 0.24) / 0.40, 0.0, 1.0) * 0.018;
        boolean inChannel = isActiveFlow(nearestFlowIndex)
            && craterT > VOLCANO_RIM_OUTER_RADIUS + 0.025
            && !VolcanoGeometry.isCalderaBarrier(craterT)
            && t > 0.25
            && t < 0.985
            && flowDistance < channelWidth;
        if (!inChannel) return NOT_APPLICABLE;

        double edge = flowDistance / channelWidth;
        BlockState channel;
        if (floor <= terrain.seaLevel + 1) {
            // Lava entering the ocean: magma blocks at the waterline give steam and bubble
            // columns — a "lava beach".
            channel = edge < 0.6 ? TerrainBlocks.MAGMA_BLOCK : TerrainBlocks.BASALT;
        } else if (edge < 0.34 && t < 0.52) {
            channel = TerrainBlocks.LAVA;
        } else if (edge < 0.60 && t < 0.70) {
            channel = TerrainBlocks.MAGMA_BLOCK;
        } else if (edge < 0.67) {
            channel = TerrainBlocks.SMOOTH_BASALT;
        } else {
            channel = TerrainBlocks.BLACKSTONE;
        }
        cursor.set(wx, floor, wz);
        level.setBlock(cursor, channel, 2);
        int writes = 1;

        // Frozen levees: a low blackstone ridge along the channel edges, as on real lava
        // channels.
        if (edge > 0.80 && t > 0.30 && t < 0.80 && floor > terrain.seaLevel + 2 && pick < 0.55) {
            cursor.set(wx, floor + 1, wz);
            if (level.getBlockState(cursor).isAir()) {
                level.setBlock(cursor, TerrainBlocks.BLACKSTONE, 2);
                writes++;
            }
        }
        return writes;
    }

    private static boolean isActiveFlow(int nearestFlowIndex) {
        for (int active : ACTIVE_FLOWS) {
            if (nearestFlowIndex == active) return true;
        }
        return false;
    }

    // -------------------------------------------------------------------------
    // Fumaroles and bombs
    // -------------------------------------------------------------------------

    /** Geothermal vents right outside the rim: a magma patch and sometimes a low chimney. */
    private int placeFumarole(
        WorldGenLevel level,
        BlockPos.MutableBlockPos cursor,
        int wx,
        int wz,
        double craterT,
        int floor,
        double pick
    ) {
        if (craterT <= VOLCANO_RIM_OUTER_RADIUS + 0.02
            || craterT >= VOLCANO_RIM_OUTER_RADIUS + 0.12
            || floor <= terrain.seaLevel + 4
            || pick >= 0.012) {
            return NOT_APPLICABLE;
        }

        cursor.set(wx, floor, wz);
        level.setBlock(cursor, TerrainBlocks.MAGMA_BLOCK, 2);
        int writes = 1;
        if (pick < 0.004) {
            int chimney = 2 + (int) (terrain.noise.hsh(wx * 233, wz * 239) * 2);
            for (int dy = 1; dy <= chimney; dy++) {
                cursor.set(wx + 1, floor + dy, wz);
                if (level.getBlockState(cursor).isAir()) {
                    level.setBlock(cursor, TerrainBlocks.BASALT, 2);
                    writes++;
                }
            }
        }
        return writes;
    }

    /** Single blocks thrown out by an eruption and lodged in the mid-slope ash fields. */
    private int placeVolcanicBomb(
        WorldGenLevel level,
        BlockPos.MutableBlockPos cursor,
        int wx,
        int wz,
        double t,
        int floor,
        double pick
    ) {
        if (t <= 0.36 || t >= 0.66 || floor <= terrain.seaLevel + 3 || pick >= 0.0035) {
            return NOT_APPLICABLE;
        }

        cursor.set(wx, floor + 1, wz);
        if (!level.getBlockState(cursor).isAir()) return 0;
        level.setBlock(
            cursor,
            terrain.noise.hsh(wx * 241, wz * 251) < 0.5
                ? TerrainBlocks.BLACKSTONE
                : TerrainBlocks.SMOOTH_BASALT,
            2
        );
        int writes = 1;
        if (terrain.noise.hsh(wx * 257, wz * 263) < 0.3) {
            cursor.set(wx, floor + 2, wz);
            if (level.getBlockState(cursor).isAir()) {
                level.setBlock(cursor, TerrainBlocks.BLACKSTONE, 2);
                writes++;
            }
        }
        return writes;
    }
}
