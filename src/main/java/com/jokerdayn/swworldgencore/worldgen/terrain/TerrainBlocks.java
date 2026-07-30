package com.jokerdayn.swworldgencore.worldgen.terrain;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.TallSeagrassBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

/**
 * Pre-resolved block states used inside the per-column loops.
 *
 * <p>Hoisting these out matters for the two-tall seagrass in particular: building it with
 * {@code defaultBlockState().setValue(HALF, ...)} inside the write loop walked the state
 * table twice for every plant placed.</p>
 */
public final class TerrainBlocks {

    public static final BlockState STONE = Blocks.STONE.defaultBlockState();
    public static final BlockState WATER = Blocks.WATER.defaultBlockState();
    public static final BlockState BEDROCK = Blocks.BEDROCK.defaultBlockState();
    public static final BlockState LAVA = Blocks.LAVA.defaultBlockState();

    public static final BlockState SAND = Blocks.SAND.defaultBlockState();
    public static final BlockState GRAVEL = Blocks.GRAVEL.defaultBlockState();
    public static final BlockState CLAY = Blocks.CLAY.defaultBlockState();
    public static final BlockState COBBLESTONE = Blocks.COBBLESTONE.defaultBlockState();
    public static final BlockState ANDESITE = Blocks.ANDESITE.defaultBlockState();
    public static final BlockState DIRT = Blocks.DIRT.defaultBlockState();
    public static final BlockState COARSE_DIRT = Blocks.COARSE_DIRT.defaultBlockState();
    public static final BlockState GRASS_BLOCK = Blocks.GRASS_BLOCK.defaultBlockState();

    public static final BlockState BASALT = Blocks.BASALT.defaultBlockState();
    public static final BlockState SMOOTH_BASALT = Blocks.SMOOTH_BASALT.defaultBlockState();
    public static final BlockState BLACKSTONE = Blocks.BLACKSTONE.defaultBlockState();
    public static final BlockState TUFF = Blocks.TUFF.defaultBlockState();
    public static final BlockState MAGMA_BLOCK = Blocks.MAGMA_BLOCK.defaultBlockState();
    public static final BlockState OBSIDIAN = Blocks.OBSIDIAN.defaultBlockState();
    public static final BlockState CRYING_OBSIDIAN = Blocks.CRYING_OBSIDIAN.defaultBlockState();

    public static final BlockState SANDSTONE_SLAB = Blocks.SANDSTONE_SLAB.defaultBlockState();
    public static final BlockState COBBLESTONE_SLAB =
        Blocks.COBBLESTONE_SLAB.defaultBlockState();
    public static final BlockState STONE_SLAB = Blocks.STONE_SLAB.defaultBlockState();

    public static final BlockState SEAGRASS = Blocks.SEAGRASS.defaultBlockState();
    public static final BlockState TALL_SEAGRASS_LOWER = Blocks.TALL_SEAGRASS
        .defaultBlockState()
        .setValue(TallSeagrassBlock.HALF, DoubleBlockHalf.LOWER);
    public static final BlockState TALL_SEAGRASS_UPPER = Blocks.TALL_SEAGRASS
        .defaultBlockState()
        .setValue(TallSeagrassBlock.HALF, DoubleBlockHalf.UPPER);

    private TerrainBlocks() {}
}
