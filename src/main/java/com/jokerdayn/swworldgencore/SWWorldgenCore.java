package com.jokerdayn.swworldgencore;

import com.mojang.brigadier.CommandDispatcher;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
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
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.registries.RegisterEvent;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import com.jokerdayn.swworldgencore.block.ShellBlock;

import com.jokerdayn.swworldgencore.block.GroundDecorationBlock;
import com.jokerdayn.swworldgencore.block.PalmSaplingBlock;
import com.jokerdayn.swworldgencore.block.PalmLeafBlock;
import com.jokerdayn.swworldgencore.worldgen.OceanChunkGenerator;

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
        NeoForge.EVENT_BUS.register(this);
        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
    }

    private void onRegister(RegisterEvent event) {
        event.register(Registries.CHUNK_GENERATOR,
            ResourceLocation.fromNamespaceAndPath(MODID, "ocean"),
            () -> OceanChunkGenerator.CODEC);
        LOGGER.info("Registered OceanChunkGenerator codec");
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        LOGGER.info("swworldgencore loaded");
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
    }

    @SubscribeEvent
    public void onCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(Commands.literal("island").executes(ctx -> {
            ServerPlayer player = ctx.getSource().getPlayerOrException();
            int px = (int) player.getX(), pz = (int) player.getZ();

            // Получаем seed из текущего ChunkGenerator
            net.minecraft.server.level.ServerLevel serverLevel =
                    player.getServer().getLevel(player.level().dimension());
            net.minecraft.world.level.chunk.ChunkGenerator gen =
                    serverLevel.getChunkSource().getGenerator();
            long worldSeed = 0;
            if (gen instanceof OceanChunkGenerator oceanGen) {
                worldSeed = oceanGen.getSeed();
            }

            int[] best = {0, 0};
            double bestDist = Double.MAX_VALUE;

            int cellX = Math.floorDiv(px, 2048), cellZ = Math.floorDiv(pz, 2048);

            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    int cx = cellX + dx, cz = cellZ + dz;
                    if (hash(cx * 11, cz * 13, worldSeed) > 0.6) continue;

                    int ix = cx * 2048 + 768 + (int)(hash(cx * 2, cz * 2, worldSeed) * 512);
                    int iz = cz * 2048 + 768 + (int)(hash(cx * 2 + 1, cz * 2 + 1, worldSeed) * 512);

                    double dist = Math.pow(ix - px, 2) + Math.pow(iz - pz, 2);
                    if (dist < bestDist) {
                        bestDist = dist;
                        best[0] = ix;
                        best[1] = iz;
                    }
                }
            }

            int fx = best[0], fz = best[1];
            // Используем getBaseHeight вместо heightmap — работает даже на несгенерированных чанках
            int surfaceY = gen.getBaseHeight(fx, fz,
                    net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING,
                    serverLevel, null) + 1;
            player.teleportTo(fx + 0.5, surfaceY, fz + 0.5);
            ctx.getSource().sendSuccess(() -> Component.literal("Island at " + fx + " " + fz + " y=" + surfaceY), false);
            return 1;
        }));
    }

    private static double hash(int x, int z, long seed) {
        long n = (long) x * 73856093L ^ (long) z * 19349663L ^ seed;
        n = (n ^ (n >> 13)) * 1274126177L;
        n = n ^ (n >> 16);
        return (double) (n & 0x7FFFFFFFL) / (double) 0x7FFFFFFFL;
    }
}
