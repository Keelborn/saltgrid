package com.jokerdayn.swworldgencore.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;

public class PalmLeafBlock extends LeavesBlock {
    public static final MapCodec<PalmLeafBlock> CODEC = simpleCodec(PalmLeafBlock::new);

    public PalmLeafBlock(Properties properties) {
        super(properties);
    }

    @Override
    public MapCodec<? extends LeavesBlock> codec() {
        return CODEC;
    }

    // Никогда не декеет
    @Override
    protected boolean isRandomlyTicking(BlockState state) {
        return false;
    }

    @Override
    protected void randomTick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
    }

    @Override
    protected boolean decaying(BlockState state) {
        return false;
    }
}
