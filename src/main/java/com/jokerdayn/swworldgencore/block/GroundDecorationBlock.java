package com.jokerdayn.swworldgencore.block;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

public class GroundDecorationBlock extends Block {
    public static final EnumProperty<Type> VARIANT = EnumProperty.create("variant", Type.class);

    private static final VoxelShape ROCK_TINY = Block.box(4, 0, 6, 9, 1, 9);
    private static final VoxelShape ROCK_SMALL = Block.box(6, 0, 8, 13, 2, 12);
    private static final VoxelShape ROCK_MEDIUM = Block.box(1, 0, 5, 8, 2, 9);
    private static final VoxelShape ROCK_LARGE = Block.box(1, 0, 3, 13, 4, 12);
    private static final VoxelShape STICK = Block.box(0, 0, 0, 10, 1, 2);

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
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(VARIANT);
    }

    @Override
    public VoxelShape getShape(BlockState state, net.minecraft.world.level.BlockGetter level,
                               BlockPos pos, CollisionContext context) {
        return switch (state.getValue(VARIANT)) {
            case ROCK_TINY -> ROCK_TINY;
            case ROCK_SMALL -> ROCK_SMALL;
            case ROCK_MEDIUM -> ROCK_MEDIUM;
            case ROCK_LARGE -> ROCK_LARGE;
            case STICK_SMALL, STICK_MEDIUM, STICK_LARGE -> STICK;
        };
    }
}
