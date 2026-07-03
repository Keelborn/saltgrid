package com.jokerdayn.swworldgencore.block;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SaplingBlock;
import net.minecraft.world.level.block.grower.TreeGrower;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import com.jokerdayn.swworldgencore.worldgen.PalmGenerator;

public class PalmSaplingBlock extends SaplingBlock {
    public static final MapCodec<PalmSaplingBlock> CODEC = RecordCodecBuilder.mapCodec(
        p -> p.group(propertiesCodec()).apply(p, PalmSaplingBlock::new)
    );

    public PalmSaplingBlock(BlockBehaviour.Properties properties) {
        super(TreeGrower.OAK, properties);
    }

    @Override
    public MapCodec<? extends SaplingBlock> codec() {
        return CODEC;
    }

    @Override
    protected boolean mayPlaceOn(BlockState state, BlockGetter level, BlockPos pos) {
        return state.is(BlockTags.DIRT) || state.is(BlockTags.SAND)
            || state.getBlock() instanceof net.minecraft.world.level.block.FarmBlock;
    }

    @Override
    public void advanceTree(ServerLevel level, BlockPos pos, BlockState state, RandomSource random) {
        if (state.getValue(STAGE) == 0) {
            level.setBlock(pos, state.cycle(STAGE), 4);
        } else {
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), 2);
            PalmGenerator.tryPlacePalm(level, pos.getX(), pos.getY(), pos.getZ(), random.nextDouble());
        }
    }
}
