package com.jokerdayn.swworldgencore.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

public class ShellBlock extends Block {
    public static final MapCodec<ShellBlock> CODEC =
        simpleCodec(ShellBlock::new);
    public static final EnumProperty<Variation> VARIANT = EnumProperty.create("variation", Variation.class);
    private static final VoxelShape SHAPE = Block.box(2, 0, 2, 14, 2, 14);

    public enum Variation implements net.minecraft.util.StringRepresentable {
        YELLOW("yellow"), PINK("pink"), WHITE("white");

        private final String name;
        Variation(String name) { this.name = name; }
        @Override public String getSerializedName() { return name; }
    }

    public ShellBlock(Properties props) {
        super(props);
        registerDefaultState(stateDefinition.any().setValue(VARIANT, Variation.YELLOW));
    }

    @Override
    public MapCodec<? extends ShellBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(VARIANT);
    }

    @Override
    public VoxelShape getShape(BlockState state, net.minecraft.world.level.BlockGetter level,
                               BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    @Override
    protected boolean canSurvive(
        BlockState state,
        LevelReader level,
        BlockPos pos
    ) {
        return Block.canSupportCenter(level, pos.below(), Direction.UP);
    }

    @Override
    protected BlockState updateShape(
        BlockState state,
        Direction direction,
        BlockState neighborState,
        LevelAccessor level,
        BlockPos pos,
        BlockPos neighborPos
    ) {
        return direction == Direction.DOWN && !state.canSurvive(level, pos)
            ? Blocks.AIR.defaultBlockState()
            : super.updateShape(
                state,
                direction,
                neighborState,
                level,
                pos,
                neighborPos
            );
    }
}
