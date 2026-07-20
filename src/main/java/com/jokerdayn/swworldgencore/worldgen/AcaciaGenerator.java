package com.jokerdayn.swworldgencore.worldgen;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BooleanProperty;

import java.util.Random;

public final class AcaciaGenerator {

    private AcaciaGenerator() {}

    private static final BlockState LEAF = Blocks.ACACIA_LEAVES.defaultBlockState()
            .setValue(LeavesBlock.PERSISTENT, true);
    private static final BlockState WOOD = Blocks.ACACIA_LOG.defaultBlockState();

    // raintree1 — среднее дерево, 11x13x9, 114 блоков
    private static final int[][] RT1 = {
        {3,0,5,1},{3,0,6,1},{4,0,4,1},{4,0,5,1},{4,1,4,1},
        {4,2,3,1},{4,2,4,1},{5,2,3,1},{6,2,3,1},{7,2,2,1},
        {7,2,3,1},{4,3,3,1},{7,3,2,1},{4,4,3,1},{5,4,1,0},
        {5,4,2,0},{6,4,0,0},{6,4,1,0},{6,4,2,0},{7,4,0,0},
        {7,4,1,0},{7,4,2,0},{8,4,1,0},{8,4,2,0},{9,4,2,0},
        {9,4,3,0},{3,5,3,1},{4,5,3,1},{6,5,1,0},{6,5,2,0},
        {7,5,0,0},{7,5,1,0},{7,5,2,0},{7,5,3,0},{8,5,1,0},
        {8,5,2,0},{8,5,3,0},{3,6,3,1},{3,6,4,1},{3,6,5,1},
        {4,6,5,1},{1,7,5,0},{2,7,3,0},{2,7,4,0},{2,7,5,0},
        {2,7,6,0},{2,7,7,0},{3,7,3,1},{3,7,4,0},{3,7,7,0},
        {4,7,3,0},{4,7,4,0},{4,7,5,1},{4,7,7,0},{5,7,3,0},
        {6,7,3,0},{6,7,4,0},{6,7,5,0},{6,7,6,0},{6,7,7,0},
        {7,7,5,0},{2,8,5,0},{3,8,3,1},{3,8,4,0},{3,8,5,0},
        {3,8,6,0},{3,8,7,0},{4,8,4,0},{4,8,5,0},{4,8,6,0},
        {5,8,4,0},{5,8,5,0},{5,8,6,0},{6,8,5,0},{3,9,3,1},
        {4,9,4,0},{4,9,5,0},{0,10,3,0},{0,10,4,0},{1,10,2,0},
        {1,10,5,0},{2,10,1,0},{2,10,6,0},{3,10,1,0},{3,10,3,1},
        {3,10,6,0},{4,10,1,0},{4,10,6,0},{5,10,1,0},{5,10,6,0},
        {6,10,1,0},{6,10,2,0},{6,10,3,0},{6,10,4,0},{6,10,5,0},
        {6,10,6,0},{1,11,3,0},{1,11,4,0},{2,11,2,0},{2,11,3,0},
        {2,11,4,0},{2,11,5,0},{3,11,2,0},{3,11,3,0},{3,11,4,0},
        {3,11,5,0},{4,11,2,0},{4,11,3,0},{4,11,4,0},{4,11,5,0},
        {5,11,2,0},{5,11,3,0},{5,11,4,0},{5,11,5,0},
    };

    // raintree2 — маленькое дерево, 10x10x10, 114 блоков
    private static final int[][] RT2 = {
        {3,0,3,1},{3,0,4,1},{4,0,3,1},{4,0,4,1},{4,1,4,1},
        {4,2,4,1},{5,2,4,1},{5,3,4,1},{2,4,6,1},{3,4,5,1},
        {3,4,6,1},{4,4,4,1},{4,4,5,1},{5,4,3,1},{5,4,4,1},
        {6,4,2,1},{6,4,3,1},{7,4,1,0},{7,4,2,1},{2,5,6,1},
        {4,5,4,1},{6,5,0,0},{6,5,1,0},{6,5,2,0},{6,5,3,0},
        {7,5,0,0},{7,5,1,0},{7,5,2,1},{7,5,3,0},{8,5,0,0},
        {8,5,1,0},{8,5,2,0},{8,5,3,0},{9,5,1,0},{9,5,2,0},
        {0,6,5,0},{0,6,6,0},{0,6,7,0},{0,6,8,0},{1,6,4,0},
        {1,6,5,0},{1,6,8,0},{1,6,9,0},{2,6,4,0},{2,6,6,0},
        {2,6,9,0},{3,6,4,0},{3,6,5,0},{3,6,8,0},{3,6,9,0},
        {4,6,4,1},{4,6,5,0},{4,6,6,0},{4,6,7,0},{4,6,8,0},
        {6,6,2,0},{6,6,3,0},{7,6,0,0},{7,6,1,0},{7,6,2,0},
        {7,6,3,0},{7,6,4,0},{8,6,1,0},{8,6,2,0},{8,6,3,0},
        {8,6,4,0},{9,6,2,0},{9,6,3,0},{1,7,6,0},{1,7,7,0},
        {2,7,5,0},{2,7,6,0},{2,7,7,0},{2,7,8,0},{3,7,6,0},
        {3,7,7,0},{4,7,4,1},{7,7,2,0},{7,7,3,0},{8,7,2,0},
        {2,8,2,0},{2,8,3,0},{2,8,4,0},{2,8,5,0},{3,8,1,0},
        {3,8,2,0},{3,8,3,0},{3,8,4,0},{3,8,5,0},{3,8,6,0},
        {4,8,1,0},{4,8,2,0},{4,8,4,0},{4,8,5,0},{4,8,6,0},
        {5,8,1,0},{5,8,2,0},{5,8,3,0},{5,8,4,0},{5,8,5,0},
        {5,8,6,0},{6,8,2,0},{6,8,3,0},{6,8,4,0},{6,8,5,0},
        {7,8,3,0},{7,8,4,0},{3,9,4,0},{4,9,2,0},{4,9,3,0},
        {4,9,4,0},{5,9,2,0},{5,9,3,0},{5,9,4,0},
    };

    // raintree3 — большое дерево, 12x15x11, 165 блоков
    private static final int[][] RT3 = {
        {5,0,4,1},{6,0,4,1},{6,0,5,1},{5,1,4,1},{5,2,4,1},
        {5,3,4,1},{5,4,4,1},{2,5,4,1},{3,5,4,1},{4,5,4,1},
        {5,5,2,1},{5,5,3,1},{5,5,4,1},{6,5,1,1},{6,5,2,1},
        {6,5,4,1},{2,6,4,1},{6,6,1,1},{6,6,4,1},{0,7,2,0},
        {0,7,3,0},{0,7,4,0},{0,7,5,0},{0,7,6,0},{1,7,2,0},
        {1,7,6,0},{2,7,2,0},{2,7,4,1},{2,7,6,0},{3,7,2,0},
        {3,7,3,0},{3,7,6,0},{4,7,2,0},{4,7,3,0},{4,7,4,0},
        {4,7,5,0},{5,7,0,0},{5,7,1,0},{5,7,2,0},{6,7,0,0},
        {6,7,1,0},{6,7,2,0},{6,7,4,1},{6,7,5,1},{7,7,0,0},
        {7,7,1,0},{7,7,2,0},{1,8,3,0},{1,8,4,0},{1,8,5,0},
        {2,8,3,0},{2,8,4,0},{2,8,5,0},{3,8,3,0},{3,8,4,0},
        {3,8,5,0},{6,8,0,0},{6,8,1,0},{6,8,4,1},{6,8,5,1},
        {7,8,1,0},{6,9,5,1},{6,10,5,1},{6,11,5,1},{1,12,3,0},
        {1,12,4,0},{2,12,1,0},{2,12,2,0},{2,12,3,0},{2,12,4,0},
        {2,12,5,0},{2,12,6,0},{2,12,7,0},{2,12,8,0},{2,12,9,0},
        {3,12,0,0},{3,12,1,0},{3,12,9,0},{4,12,1,0},{4,12,9,0},
        {5,12,1,0},{5,12,9,0},{5,12,10,0},{6,12,5,1},{6,12,9,0},
        {6,12,10,0},{7,12,0,0},{7,12,1,0},{7,12,9,0},{8,12,1,0},
        {8,12,9,0},{9,12,1,0},{9,12,9,0},{10,12,0,0},{10,12,1,0},
        {10,12,2,0},{10,12,3,0},{10,12,4,0},{10,12,5,0},{10,12,6,0},
        {10,12,7,0},{10,12,8,0},{10,12,9,0},{2,13,5,0},{3,13,2,0},
        {3,13,3,0},{3,13,4,0},{3,13,5,0},{3,13,6,0},{3,13,7,0},
        {3,13,8,0},{4,13,2,0},{4,13,3,0},{4,13,4,0},{4,13,5,0},
        {4,13,6,0},{4,13,7,0},{4,13,8,0},{5,13,2,0},{5,13,3,0},
        {5,13,4,0},{5,13,5,0},{5,13,6,0},{5,13,7,0},{5,13,8,0},
        {6,13,2,0},{6,13,3,0},{6,13,4,0},{6,13,5,0},{6,13,6,0},
        {6,13,7,0},{6,13,8,0},{6,13,9,0},{7,13,2,0},{7,13,3,0},
        {7,13,4,0},{7,13,5,0},{7,13,6,0},{7,13,7,0},{7,13,8,0},
        {8,13,2,0},{8,13,3,0},{8,13,4,0},{8,13,5,0},{8,13,6,0},
        {8,13,7,0},{8,13,8,0},{9,13,2,0},{9,13,3,0},{9,13,4,0},
        {9,13,5,0},{9,13,6,0},{9,13,7,0},{9,13,8,0},{10,13,6,0},
        {3,14,6,0},{4,14,4,0},{5,14,6,0},{6,14,4,0},{6,14,5,0},
        {7,14,3,0},{7,14,5,0},{8,14,2,0},{8,14,6,0},{9,14,6,0},
    };

    private static final int[][][] ALL = {RT1, RT2, RT3};

    private static boolean isSoil(BlockState state) {
        return state.is(Blocks.GRASS_BLOCK) || state.is(Blocks.DIRT)
                || state.is(Blocks.COARSE_DIRT) || state.is(Blocks.PODZOL);
    }

    private static boolean isReplaceable(BlockState state) {
        return state.isAir() || state.is(Blocks.SHORT_GRASS) || state.is(Blocks.TALL_GRASS)
                || state.is(Blocks.FERN) || state.is(Blocks.LARGE_FERN)
                || state.is(Blocks.VINE) || state.is(Blocks.POPPY)
                || state.is(Blocks.DANDELION) || state.is(Blocks.OXEYE_DAISY);
    }

    private static final int[][] ANCHORS = {{4, 5}, {4, 4}, {6, 4}};
    private static final int[][][][] ROTATED = buildRotatedTemplates();

    public record PlacementResult(
        boolean placed,
        long preflightNs,
        long writeNs,
        int blocksWritten
    ) {}

    private static int[][][][] buildRotatedTemplates() {
        int[][][][] rotated = new int[ALL.length][4][][];
        for (int variant = 0; variant < ALL.length; variant++) {
            int[][] source = ALL[variant];
            int anchorX = ANCHORS[variant][0];
            int anchorZ = ANCHORS[variant][1];
            for (int turns = 0; turns < 4; turns++) {
                int[][] blocks = new int[source.length][4];
                for (int i = 0; i < source.length; i++) {
                    int x = source[i][0] - anchorX;
                    int z = source[i][2] - anchorZ;
                    for (int turn = 0; turn < turns; turn++) {
                        int oldX = x;
                        x = -z;
                        z = oldX;
                    }
                    blocks[i][0] = x;
                    blocks[i][1] = source[i][1];
                    blocks[i][2] = z;
                    blocks[i][3] = source[i][3];
                }
                rotated[variant][turns] = blocks;
            }
        }
        return rotated;
    }

    public static PlacementResult tryPlaceDetailed(
        WorldGenLevel level,
        int ax,
        int ay,
        int az,
        double seedMix,
        boolean preferSmall
    ) {
        long preflightStarted = System.nanoTime();
        long cs = Double.doubleToLongBits(seedMix)
                ^ ((long) ax * 982451653L)
                ^ ((long) az * 718364721L)
                ^ ((long) ay * 123456789L);
        Random rng = new Random(cs);
        int variant = preferSmall ? 1 : rng.nextInt(ALL.length);
        int[][] blocks = ROTATED[variant][rng.nextInt(4)];

        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int[] b : blocks) {
            int x = ax + b[0], y = ay + b[1], z = az + b[2];
            pos.set(x, y, z);
            if (!isReplaceable(level.getBlockState(pos))) {
                return new PlacementResult(
                    false,
                    System.nanoTime() - preflightStarted,
                    0L,
                    0
                );
            }

            if (b[3] == 1 && b[1] == 0) {
                pos.set(x, y - 1, z);
                if (!isSoil(level.getBlockState(pos))) {
                    return new PlacementResult(
                        false,
                        System.nanoTime() - preflightStarted,
                        0L,
                        0
                    );
                }
            }
        }
        long preflightNs = System.nanoTime() - preflightStarted;

        long writeStarted = System.nanoTime();
        int blocksWritten = 0;
        for (int[] b : blocks) {
            pos.set(ax + b[0], ay + b[1], az + b[2]);
            level.setBlock(pos, b[3] == 1 ? WOOD : LEAF, 2);
            blocksWritten++;
        }

        BlockPos.MutableBlockPos neighbor = new BlockPos.MutableBlockPos();
        for (int[] b : blocks) {
            pos.set(ax + b[0], ay + b[1], az + b[2]);
            for (Direction dir : Direction.Plane.HORIZONTAL) {
                if (rng.nextDouble() > 0.14) continue;
                neighbor.set(
                    pos.getX() + dir.getStepX(),
                    pos.getY(),
                    pos.getZ() + dir.getStepZ()
                );
                if (!level.getBlockState(neighbor).isAir()) continue;
                BooleanProperty prop = VineBlock.getPropertyForFace(dir.getOpposite());
                level.setBlock(
                    neighbor,
                    Blocks.VINE.defaultBlockState().setValue(prop, true),
                    2
                );
                blocksWritten++;
            }
        }
        return new PlacementResult(
            true,
            preflightNs,
            System.nanoTime() - writeStarted,
            blocksWritten
        );
    }

    public static boolean tryPlace(WorldGenLevel level, int ax, int ay, int az,
                                   double seedMix, boolean preferSmall) {
        return tryPlaceDetailed(level, ax, ay, az, seedMix, preferSmall).placed();
    }

    public static boolean tryPlace(WorldGenLevel level, int ax, int ay, int az, double seedMix) {
        return tryPlace(level, ax, ay, az, seedMix, false);
    }
}
