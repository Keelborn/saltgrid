package com.jokerdayn.swworldgencore.worldgen;

import com.jokerdayn.swworldgencore.SWWorldgenCore;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FarmBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Deterministic procedural palm generator.
 *
 * <p>Every tree is assembled in memory first and written only after a complete
 * collision check. The curved trunk, crown and fronds are generated from the
 * world position and supplied seed, so chunk reloads always produce the same
 * palm.</p>
 */
public final class PalmGenerator {

    private static final BlockState TRUNK = Blocks.JUNGLE_WOOD.defaultBlockState();

    private PalmGenerator() {}

    public record PlacementResult(
        boolean placed,
        long preflightNs,
        long writeNs,
        int blocksWritten
    ) {}

    private record LocalPos(int x, int y, int z) {}

    private record PlannedBlock(BlockState state, boolean trunk) {}

    private enum Silhouette {
        TALL(13, 17, 2.4, 4.4, 7, 9),
        COASTAL(11, 15, 4.0, 6.2, 7, 10),
        COMPACT(9, 13, 1.8, 3.4, 6, 8);

        final int minHeight;
        final int maxHeight;
        final double minLean;
        final double maxLean;
        final int minFrondLength;
        final int maxFrondLength;

        Silhouette(
            int minHeight,
            int maxHeight,
            double minLean,
            double maxLean,
            int minFrondLength,
            int maxFrondLength
        ) {
            this.minHeight = minHeight;
            this.maxHeight = maxHeight;
            this.minLean = minLean;
            this.maxLean = maxLean;
            this.minFrondLength = minFrondLength;
            this.maxFrondLength = maxFrondLength;
        }
    }

    public static PlacementResult tryPlacePalmDetailed(
        WorldGenLevel level,
        int baseX,
        int baseY,
        int baseZ,
        double seedMix
    ) {
        long preflightStarted = System.nanoTime();

        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos(baseX, baseY - 1, baseZ);
        if (!isSoil(level.getBlockState(cursor))) {
            return failed(preflightStarted);
        }

        Random random = new Random(treeSeed(baseX, baseY, baseZ, seedMix));
        Map<LocalPos, PlannedBlock> plan = new LinkedHashMap<>();
        buildPalm(plan, random);

        for (Map.Entry<LocalPos, PlannedBlock> entry : plan.entrySet()) {
            LocalPos p = entry.getKey();
            int worldY = baseY + p.y();
            if (worldY < level.getMinBuildHeight() || worldY >= level.getMaxBuildHeight()) {
                return failed(preflightStarted);
            }

            cursor.set(baseX + p.x(), worldY, baseZ + p.z());
            BlockState existing = level.getBlockState(cursor);
            if (!isReplaceable(existing)) {
                return failed(preflightStarted);
            }
        }

        long preflightNs = System.nanoTime() - preflightStarted;
        long writeStarted = System.nanoTime();
        for (Map.Entry<LocalPos, PlannedBlock> entry : plan.entrySet()) {
            LocalPos p = entry.getKey();
            cursor.set(baseX + p.x(), baseY + p.y(), baseZ + p.z());
            level.setBlock(cursor, entry.getValue().state(), 2);
        }

        return new PlacementResult(
            true,
            preflightNs,
            System.nanoTime() - writeStarted,
            plan.size()
        );
    }

    public static boolean tryPlacePalm(
        WorldGenLevel level,
        int baseX,
        int baseY,
        int baseZ,
        double seedMix
    ) {
        return tryPlacePalmDetailed(level, baseX, baseY, baseZ, seedMix).placed();
    }

    private static PlacementResult failed(long preflightStarted) {
        return new PlacementResult(false, System.nanoTime() - preflightStarted, 0L, 0);
    }

    private static void buildPalm(Map<LocalPos, PlannedBlock> plan, Random random) {
        Silhouette silhouette = pickSilhouette(random);
        int height = between(random, silhouette.minHeight, silhouette.maxHeight);
        double lean = between(random, silhouette.minLean, silhouette.maxLean);
        double leanAngle = random.nextDouble() * Math.PI * 2.0;
        double sideCurve = (random.nextDouble() - 0.5) * 2.2;
        double curveSign = random.nextBoolean() ? 1.0 : -1.0;

        List<LocalPos> spine = new ArrayList<>(height + 1);
        LocalPos previous = null;
        for (int y = 0; y <= height; y++) {
            double t = y / (double) height;
            double easedLean = Math.pow(t, 1.65) * lean;
            double curved = Math.sin(t * Math.PI) * sideCurve * curveSign;
            int x = (int) Math.round(
                Math.cos(leanAngle) * easedLean + Math.cos(leanAngle + Math.PI / 2.0) * curved
            );
            int z = (int) Math.round(
                Math.sin(leanAngle) * easedLean + Math.sin(leanAngle + Math.PI / 2.0) * curved
            );
            LocalPos current = new LocalPos(x, y, z);

            if (previous != null && (previous.x() != x || previous.z() != z)) {
                Direction.Axis bridgeAxis = axisFor(x - previous.x(), z - previous.z());
                putTrunk(plan, previous.x(), y, previous.z(), bridgeAxis);
            }
            putTrunk(plan, x, y, z, Direction.Axis.Y);
            spine.add(current);
            previous = current;
        }

        addButtressRoots(plan, random, leanAngle);
        LocalPos crown = spine.get(spine.size() - 1);
        addCrown(plan, random, crown, silhouette, leanAngle);
    }

    private static Silhouette pickSilhouette(Random random) {
        double roll = random.nextDouble();
        if (roll < 0.46) return Silhouette.TALL;
        if (roll < 0.78) return Silhouette.COASTAL;
        return Silhouette.COMPACT;
    }

    private static void addButtressRoots(
        Map<LocalPos, PlannedBlock> plan,
        Random random,
        double leanAngle
    ) {
        int roots = 3 + random.nextInt(2);
        double start = leanAngle + Math.PI + random.nextDouble() * 0.7;
        for (int i = 0; i < roots; i++) {
            double angle = start + i * (Math.PI * 2.0 / roots) + random.nextGaussian() * 0.18;
            int length = 1 + random.nextInt(2);
            for (int step = 1; step <= length; step++) {
                int x = (int) Math.round(Math.cos(angle) * step);
                int z = (int) Math.round(Math.sin(angle) * step);
                int y = step == 1 ? 0 : -1;
                // Do not bury roots: the second segment is kept at ground level
                // when the generated terrain cannot expose y=-1.
                if (y < 0) y = 0;
                putTrunk(plan, x, y, z, axisFor(x, z));
            }
        }
    }

    private static void addCrown(
        Map<LocalPos, PlannedBlock> plan,
        Random random,
        LocalPos crown,
        Silhouette silhouette,
        double leanAngle
    ) {
        BlockState leaf = SWWorldgenCore.PALM_LEAF.get().defaultBlockState();

        // Dense but irregular heart: it hides the meeting point of all fronds.
        putLeaf(plan, leaf, crown.x(), crown.y() + 1, crown.z());
        putLeaf(plan, leaf, crown.x() + 1, crown.y(), crown.z());
        putLeaf(plan, leaf, crown.x() - 1, crown.y(), crown.z());
        putLeaf(plan, leaf, crown.x(), crown.y(), crown.z() + 1);
        putLeaf(plan, leaf, crown.x(), crown.y(), crown.z() - 1);

        int frondCount = 8 + random.nextInt(4);
        double startAngle = leanAngle + random.nextDouble() * 0.45;
        for (int i = 0; i < frondCount; i++) {
            double evenAngle = startAngle + i * Math.PI * 2.0 / frondCount;
            double angle = evenAngle + random.nextGaussian() * 0.13;
            int length = between(random, silhouette.minFrondLength, silhouette.maxFrondLength);
            double lift = 1.35 + random.nextDouble() * 1.15;
            double drop = 2.0 + random.nextDouble() * 2.2;
            double sideways = random.nextGaussian() * 0.45;
            addFrond(plan, leaf, random, crown, angle, length, lift, drop, sideways);
        }

        // A few shorter, lower fronds make the underside less perfectly radial.
        int lowerFronds = 2 + random.nextInt(2);
        for (int i = 0; i < lowerFronds; i++) {
            double angle = startAngle + (i + 0.5) * Math.PI * 2.0 / lowerFronds
                + random.nextGaussian() * 0.2;
            int length = between(random, 4, 6);
            addFrond(plan, leaf, random, crown, angle, length, 0.5, 3.2, 0.25);
        }
    }

    private static void addFrond(
        Map<LocalPos, PlannedBlock> plan,
        BlockState leaf,
        Random random,
        LocalPos crown,
        double angle,
        int length,
        double lift,
        double drop,
        double sideways
    ) {
        int previousX = crown.x();
        int previousY = crown.y();
        int previousZ = crown.z();

        for (int step = 1; step <= length; step++) {
            double t = step / (double) length;
            double radius = step * (0.94 + 0.06 * Math.sin(t * Math.PI));
            double lateral = Math.sin(t * Math.PI) * sideways;
            int x = crown.x() + (int) Math.round(
                Math.cos(angle) * radius + Math.cos(angle + Math.PI / 2.0) * lateral
            );
            int z = crown.z() + (int) Math.round(
                Math.sin(angle) * radius + Math.sin(angle + Math.PI / 2.0) * lateral
            );
            int y = crown.y() + (int) Math.round(
                lift * Math.sin(t * Math.PI) - drop * Math.pow(t, 2.15)
            );

            connectLeaves(plan, leaf, previousX, previousY, previousZ, x, y, z);
            putLeaf(plan, leaf, x, y, z);

            // Broad leaflets close to the crown, tapering to a clean tip.
            if (step >= 2 && step < length - 1 && (step % 2 == 0 || random.nextDouble() < 0.32)) {
                int px = (int) Math.round(Math.cos(angle + Math.PI / 2.0));
                int pz = (int) Math.round(Math.sin(angle + Math.PI / 2.0));
                putLeaf(plan, leaf, x + px, y, z + pz);
                putLeaf(plan, leaf, x - px, y, z - pz);
            }
            if (step == length) {
                putLeaf(plan, leaf, x, y - 1, z);
            }

            previousX = x;
            previousY = y;
            previousZ = z;
        }
    }

    private static void connectLeaves(
        Map<LocalPos, PlannedBlock> plan,
        BlockState leaf,
        int x0,
        int y0,
        int z0,
        int x1,
        int y1,
        int z1
    ) {
        int dx = x1 - x0;
        int dy = y1 - y0;
        int dz = z1 - z0;
        int steps = Math.max(Math.abs(dx), Math.max(Math.abs(dy), Math.abs(dz)));
        for (int i = 1; i < steps; i++) {
            double t = i / (double) steps;
            putLeaf(
                plan,
                leaf,
                x0 + (int) Math.round(dx * t),
                y0 + (int) Math.round(dy * t),
                z0 + (int) Math.round(dz * t)
            );
        }
    }

    private static void putTrunk(
        Map<LocalPos, PlannedBlock> plan,
        int x,
        int y,
        int z,
        Direction.Axis axis
    ) {
        BlockState state = TRUNK;
        if (state.hasProperty(RotatedPillarBlock.AXIS)) {
            state = state.setValue(RotatedPillarBlock.AXIS, axis);
        }
        plan.put(new LocalPos(x, y, z), new PlannedBlock(state, true));
    }

    private static void putLeaf(
        Map<LocalPos, PlannedBlock> plan,
        BlockState state,
        int x,
        int y,
        int z
    ) {
        plan.putIfAbsent(new LocalPos(x, y, z), new PlannedBlock(state, false));
    }

    private static Direction.Axis axisFor(int dx, int dz) {
        return Math.abs(dx) >= Math.abs(dz) ? Direction.Axis.X : Direction.Axis.Z;
    }

    private static boolean isSoil(BlockState state) {
        return state.is(BlockTags.SAND)
            || state.is(BlockTags.DIRT)
            || state.getBlock() instanceof FarmBlock;
    }

    private static boolean isReplaceable(BlockState state) {
        return state.isAir()
            || state.is(SWWorldgenCore.PALM_SAPLING.get())
            || state.is(SWWorldgenCore.PALM_LEAF.get())
            || state.is(Blocks.SHORT_GRASS)
            || state.is(Blocks.TALL_GRASS)
            || state.is(Blocks.FERN)
            || state.is(Blocks.LARGE_FERN)
            || state.is(Blocks.VINE)
            || state.is(Blocks.DEAD_BUSH)
            || state.is(Blocks.POPPY)
            || state.is(Blocks.DANDELION)
            || state.is(Blocks.OXEYE_DAISY)
            || state.is(Blocks.BLUE_ORCHID)
            || state.is(Blocks.ALLIUM)
            || state.is(Blocks.AZURE_BLUET);
    }

    private static int between(Random random, int min, int max) {
        return min + random.nextInt(max - min + 1);
    }

    private static double between(Random random, double min, double max) {
        return min + random.nextDouble() * (max - min);
    }

    private static long treeSeed(int x, int y, int z, double seedMix) {
        long seed = Double.doubleToLongBits(seedMix);
        seed ^= (long) x * 0x9E3779B97F4A7C15L;
        seed ^= (long) y * 0xC2B2AE3D27D4EB4FL;
        seed ^= (long) z * 0x165667B19E3779F9L;
        return mix64(seed);
    }

    private static long mix64(long value) {
        value = (value ^ (value >>> 30)) * 0xBF58476D1CE4E5B9L;
        value = (value ^ (value >>> 27)) * 0x94D049BB133111EBL;
        return value ^ (value >>> 31);
    }
}
