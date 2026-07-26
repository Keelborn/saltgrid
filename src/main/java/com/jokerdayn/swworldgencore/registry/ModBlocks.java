package com.jokerdayn.swworldgencore.registry;

import com.jokerdayn.swworldgencore.SWWorldgenCore;
import com.jokerdayn.swworldgencore.block.GroundDecorationBlock;
import com.jokerdayn.swworldgencore.block.PalmLeafBlock;
import com.jokerdayn.swworldgencore.block.PalmSaplingBlock;
import com.jokerdayn.swworldgencore.block.ShellBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.PushReaction;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Block registrations for the mod. */
public final class ModBlocks {

    public static final DeferredRegister.Blocks BLOCKS =
        DeferredRegister.createBlocks(SWWorldgenCore.MODID);

    /** Decorative seashell scattered along beaches. Broken instantly, no collision. */
    public static final DeferredBlock<ShellBlock> SHELL = BLOCKS.register(
        "shell",
        () -> new ShellBlock(BlockBehaviour.Properties.of()
            .strength(0.1f)
            .noOcclusion()
            .noCollission()
            .pushReaction(PushReaction.DESTROY))
    );

    /** Pebbles and sticks on island ground. */
    public static final DeferredBlock<GroundDecorationBlock> GROUND_DECORATION = BLOCKS.register(
        "ground_decoration",
        () -> new GroundDecorationBlock(BlockBehaviour.Properties.of()
            .strength(0.2f)
            .noOcclusion()
            .noCollission()
            .pushReaction(PushReaction.DESTROY))
    );

    public static final DeferredBlock<PalmSaplingBlock> PALM_SAPLING = BLOCKS.register(
        "palm_sapling",
        () -> new PalmSaplingBlock(BlockBehaviour.Properties.of()
            .noCollission()
            .randomTicks()
            .instabreak()
            .sound(SoundType.GRASS))
    );

    /**
     * Palm frond. Never decays — the fronds are placed by the generator as a designed
     * silhouette, so vanilla leaf decay would eat them.
     */
    public static final DeferredBlock<PalmLeafBlock> PALM_LEAF = BLOCKS.register(
        "palm_leaf",
        () -> new PalmLeafBlock(BlockBehaviour.Properties.of()
            .strength(0.2f)
            .noOcclusion()
            .sound(SoundType.GRASS))
    );

    /** Bronze ore, found in veins inside the island boulders. */
    public static final DeferredBlock<Block> BRONZE_ORE = BLOCKS.register(
        "bronze_ore",
        () -> new Block(BlockBehaviour.Properties.of()
            .strength(2.5f, 2.5f)
            .requiresCorrectToolForDrops()
            .sound(SoundType.STONE))
    );

    private ModBlocks() {}

    public static void register(IEventBus modEventBus) {
        BLOCKS.register(modEventBus);
    }
}
