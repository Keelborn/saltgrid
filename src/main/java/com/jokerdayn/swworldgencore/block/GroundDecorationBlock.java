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

public class GroundDecorationBlock extends Block {
    public static final MapCodec<GroundDecorationBlock> CODEC =
        simpleCodec(GroundDecorationBlock::new);
    public static final EnumProperty<Type> VARIANT = EnumProperty.create("variant", Type.class);

    // Models are randomly rotated by the blockstate.  A stable footprint makes
    // every variation reliably selectable, rather than exposing a mismatched
    // outline for a client-side random model rotation.
    private static final VoxelShape ROCK_SHAPE = Block.box(0, 0, 0, 16, 3, 16);
    private static final VoxelShape STICK_SHAPE = Block.box(0, 0, 0, 16, 1, 16);

    public enum Type implements net.minecraft.util.StringRepresentable {
        ROCK_TINY("rock_tiny"), ROCK_SMALL("rock_small"), ROCK_MEDIUM("rock_medium"),
        ROCK_LARGE("rock_large"), STICK_SMALL("stick_small"), STICK_MEDIUM("stick_medium"),
        STICK_LARGE("stick_large");

        private final String name;
        Type(String name) { this.name = name; }
        @Override public String getSerializedName() { return name; }
    }

    public GroundDecorationBlock(Properties props) {
        super(props);
        registerDefaultState(stateDefinition.any().setValue(VARIANT, Type.ROCK_TINY));
    }

    @Override
    public MapCodec<? extends GroundDecorationBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(VARIANT);
    }

    @Override
    public VoxelShape getShape(BlockState state, net.minecraft.world.level.BlockGetter level,
                               BlockPos pos, CollisionContext context) {
        return switch (state.getValue(VARIANT)) {
            case ROCK_TINY, ROCK_SMALL, ROCK_MEDIUM, ROCK_LARGE -> ROCK_SHAPE;
            case STICK_SMALL, STICK_MEDIUM, STICK_LARGE -> STICK_SHAPE;
        };
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
