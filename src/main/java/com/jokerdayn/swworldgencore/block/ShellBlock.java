package com.jokerdayn.swworldgencore.block;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

public class ShellBlock extends Block {
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
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(VARIANT);
    }

    @Override
    public VoxelShape getShape(BlockState state, net.minecraft.world.level.BlockGetter level,
                               BlockPos pos, CollisionContext context) {
        return SHAPE;
    }
}