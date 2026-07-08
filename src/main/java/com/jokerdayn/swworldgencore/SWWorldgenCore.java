package com.jokerdayn.swworldgencore;

import com.mojang.brigadier.CommandDispatcher;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.client.event.RegisterColorHandlersEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.registries.RegisterEvent;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import com.jokerdayn.swworldgencore.block.ShellBlock;
import com.jokerdayn.swworldgencore.block.GroundDecorationBlock;
import com.jokerdayn.swworldgencore.block.PalmSaplingBlock;
import com.jokerdayn.swworldgencore.block.PalmLeafBlock;
import com.jokerdayn.swworldgencore.worldgen.OceanChunkGenerator;
import com.jokerdayn.swworldgencore.worldgen.OceanBiomeSource;

@Mod(SWWorldgenCore.MODID)
public class SWWorldgenCore {
    public static final String MODID = "swworldgencore";
    public static final Logger LOGGER = LogUtils.getLogger();

    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MODID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MODID);

    public static final DeferredBlock<ShellBlock> SHELL = BLOCKS.register("shell",
        () -> new ShellBlock(BlockBehaviour.Properties.of().strength(0.1f).noOcclusion().noCollission().pushReaction(net.minecraft.world.level.material.PushReaction.DESTROY)));
    public static final DeferredHolder<Item, BlockItem> SHELL_ITEM = ITEMS.register("shell",
        () -> new BlockItem(SHELL.get(), new Item.Properties()));

    public static final DeferredBlock<GroundDecorationBlock> GROUND_DECO = BLOCKS.register("ground_decoration",
        () -> new GroundDecorationBlock(BlockBehaviour.Properties.of().strength(0.2f).noOcclusion().noCollission().pushReaction(net.minecraft.world.level.material.PushReaction.DESTROY)));
    public static final DeferredHolder<Item, BlockItem> GROUND_DECO_ITEM = ITEMS.register("ground_decoration",
        () -> new BlockItem(GROUND_DECO.get(), new Item.Properties()));

    public static final DeferredBlock<PalmSaplingBlock> PALM_SAPLING = BLOCKS.register("palm_sapling",
        () -> new PalmSaplingBlock(BlockBehaviour.Properties.of().noCollission().randomTicks().instabreak().sound(net.minecraft.world.level.block.SoundType.GRASS)));
    public static final DeferredHolder<Item, BlockItem> PALM_SAPLING_ITEM = ITEMS.register("palm_sapling",
        () -> new BlockItem(PALM_SAPLING.get(), new Item.Properties()));

    public static final DeferredBlock<PalmLeafBlock> PALM_LEAF = BLOCKS.register("palm_leaf",
        () -> new PalmLeafBlock(BlockBehaviour.Properties.of().strength(0.2f).noOcclusion().randomTicks().sound(net.minecraft.world.level.block.SoundType.GRASS)));
    public static final DeferredHolder<Item, BlockItem> PALM_LEAF_ITEM = ITEMS.register("palm_leaf",
        () -> new BlockItem(PALM_LEAF.get(), new Item.Properties()));

    public SWWorldgenCore(IEventBus modEventBus, ModContainer modContainer) {
        BLOCKS.register(modEventBus);
        ITEMS.register(modEventBus);
        modEventBus.addListener(this::commonSetup);
        modEventBus.addListener(this::onRegister);
        modEventBus.addListener(this::onBlockColor);
        NeoForge.EVENT_BUS.register(this);
        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
    }

    private void onRegister(RegisterEvent event) {
        event.register(Registries.CHUNK_GENERATOR,
            ResourceLocation.fromNamespaceAndPath(MODID, "ocean"),
            () -> OceanChunkGenerator.CODEC);
        event.register(Registries.BIOME_SOURCE,
            ResourceLocation.fromNamespaceAndPath(MODID, "ocean_biomes"),
            () -> OceanBiomeSource.CODEC);
        LOGGER.info("Registered OceanChunkGenerator + OceanBiomeSource codecs");
    }

    private void onBlockColor(RegisterColorHandlersEvent.Block event) {
        // Palm leaf — static green
        final int JUNGLE_FOLIAGE = 0x399013;
        event.register((state, level, pos, tintIndex) -> JUNGLE_FOLIAGE, PALM_LEAF.get());

        // Calm grass for short grass
        final int GRASS_CALM = 0x5A9E3A;
        event.register((state, level, pos, tintIndex) -> GRASS_CALM, net.minecraft.world.level.block.Blocks.SHORT_GRASS);

        // Grass block (дёрн) — biome-specific
        event.register((state, level, pos, tintIndex) -> {
            if (!(level instanceof net.minecraft.world.level.Level lv)) return 0xA0B14A;
            var key = lv.getBiome(pos).unwrapKey().orElse(null);
            if (key == null) return 0xA0B14A;
            return switch (key.location().getPath()) {
                case "savanna" -> 0xA0B14A;
                case "tropics" -> 0xA6A450;
                default -> 0xA0B14A;
            };
        }, net.minecraft.world.level.block.Blocks.GRASS_BLOCK);
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        LOGGER.info("swworldgencore loaded");
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
    }

    @SubscribeEvent
    public void onLevelLoad(LevelEvent.Load event) {
        if (event.getLevel() instanceof net.minecraft.server.level.ServerLevel serverLevel) {
            net.minecraft.world.level.chunk.ChunkGenerator gen =
                    serverLevel.getChunkSource().getGenerator();
            if (gen instanceof OceanChunkGenerator oceanGen) {
                oceanGen.syncSeedFromLevel(serverLevel);
            }
        }
    }

    @SubscribeEvent
    public void onCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(Commands.literal("island").executes(ctx -> {
            ServerPlayer player = ctx.getSource().getPlayerOrException();
            int px = (int) player.getX(), pz = (int) player.getZ();

            net.minecraft.server.level.ServerLevel serverLevel =
                    player.getServer().getLevel(player.level().dimension());
            net.minecraft.world.level.chunk.ChunkGenerator gen =
                    serverLevel.getChunkSource().getGenerator();

            int[] island;
            if (gen instanceof OceanChunkGenerator oceanGen) {
                island = oceanGen.findNearestIslandCenter(px, pz);
            } else {
                ctx.getSource().sendSuccess(() -> Component.literal("Not an ocean world"), false);
                return 0;
            }

            int fx = island[0], fz = island[1];
            int surfaceY = gen.getBaseHeight(fx, fz,
                    net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING,
                    serverLevel, null) + 1;
            player.teleportTo(fx + 0.5, surfaceY, fz + 0.5);
            ctx.getSource().sendSuccess(() -> Component.literal("Island at " + fx + " " + fz + " y=" + surfaceY), false);
            return 1;
        }));
    }

}
