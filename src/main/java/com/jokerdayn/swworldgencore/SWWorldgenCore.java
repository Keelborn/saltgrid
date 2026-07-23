package com.jokerdayn.swworldgencore;

import com.mojang.brigadier.CommandDispatcher;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.registries.RegisterEvent;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
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

    public static final ResourceKey<Level> OCEAN_DIMENSION =
        ResourceKey.create(Registries.DIMENSION,
            ResourceLocation.fromNamespaceAndPath(MODID, "ocean"));

    private static final String OCEAN_SPAWNED_TAG =
        MODID + ":spawned_in_ocean";
    private static final String VISITED_BOULDERS_TAG =
        MODID + ":visited_boulders";
    private static final int OCEAN_SPAWN_SEARCH_ATTEMPTS = 24;
    private static final long OCEAN_SPAWN_ATTEMPT_SALT =
        0x9E3779B97F4A7C15L;

    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MODID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MODID);
    public static final DeferredRegister<CreativeModeTab> CREATIVE_TABS =
        DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MODID);

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
    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> MAIN_TAB =
        CREATIVE_TABS.register(
            "main",
            () -> CreativeModeTab.builder()
                .title(Component.translatable("itemGroup.swworldgencore"))
                .icon(() -> new ItemStack(PALM_SAPLING_ITEM.get()))
                .displayItems((parameters, output) -> {
                    output.accept(SHELL_ITEM.get());
                    output.accept(GROUND_DECO_ITEM.get());
                    output.accept(PALM_SAPLING_ITEM.get());
                    output.accept(PALM_LEAF_ITEM.get());
                })
                .build()
        );

    public SWWorldgenCore(IEventBus modEventBus, ModContainer modContainer) {
        BLOCKS.register(modEventBus);
        ITEMS.register(modEventBus);
        CREATIVE_TABS.register(modEventBus);
        modEventBus.addListener(this::commonSetup);
        modEventBus.addListener(this::onRegister);
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
        if (event.getRegistryKey().equals(Registries.BIOME_SOURCE)) {
            LOGGER.info("Registered OceanChunkGenerator + OceanBiomeSource codecs");
        }
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        LOGGER.info("swworldgencore loaded");
    }

    @SubscribeEvent
    public void onLevelLoad(LevelEvent.Load event) {
        if (event.getLevel() instanceof ServerLevel serverLevel) {
            ChunkGenerator gen = serverLevel.getChunkSource().getGenerator();
            if (gen instanceof OceanChunkGenerator oceanGen) {
                oceanGen.syncSeedFromLevel(serverLevel);
            }
        }
    }

    @SubscribeEvent
    public void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (!Config.SPAWN_NEW_PLAYERS_IN_OCEAN.get()) return;

        boolean hasOceanSpawn =
            OCEAN_DIMENSION.equals(player.getRespawnDimension()) &&
            player.getRespawnPosition() != null;
        if (
            player.getPersistentData().getBoolean(OCEAN_SPAWNED_TAG) &&
            hasOceanSpawn
        ) return;

        if (teleportToOceanSpawn(player)) {
            player.getPersistentData().putBoolean(OCEAN_SPAWNED_TAG, true);
        }
    }

    @SubscribeEvent
    public void onPlayerRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (
            Config.RESPAWN_IN_OCEAN.get() &&
            !player.level().dimension().equals(OCEAN_DIMENSION) &&
            teleportToOceanSpawn(player)
        ) {
            player.getPersistentData().putBoolean(OCEAN_SPAWNED_TAG, true);
        }
    }

    @SubscribeEvent
    public void onPlayerClone(PlayerEvent.Clone event) {
        CompoundTag original = event.getOriginal().getPersistentData();
        CompoundTag replacement = event.getEntity().getPersistentData();
        if (original.contains(OCEAN_SPAWNED_TAG)) {
            replacement.putBoolean(
                OCEAN_SPAWNED_TAG,
                original.getBoolean(OCEAN_SPAWNED_TAG)
            );
        }
        if (original.contains(VISITED_BOULDERS_TAG)) {
            replacement.put(
                VISITED_BOULDERS_TAG,
                original.getCompound(VISITED_BOULDERS_TAG).copy()
            );
        }
    }

    private boolean teleportToOceanSpawn(ServerPlayer player) {
        if (player.getServer() == null) return false;
        ServerLevel ocean = player.getServer().getLevel(OCEAN_DIMENSION);
        if (ocean == null) {
            LOGGER.error(
                "Ocean dimension is unavailable; player {} was not teleported",
                player.getName().getString()
            );
            return false;
        }

        ChunkGenerator generator = ocean.getChunkSource().getGenerator();
        if (!(generator instanceof OceanChunkGenerator oceanGenerator)) {
            LOGGER.error(
                "Dimension {} does not use OceanChunkGenerator",
                OCEAN_DIMENSION.location()
            );
            return false;
        }
        oceanGenerator.syncSeedFromLevel(ocean);

        int oceanDistance = 1 + player.getRandom().nextInt(3);
        long searchSalt =
            player.getUUID().getMostSignificantBits() ^
            player.getUUID().getLeastSignificantBits() ^
            player.getRandom().nextLong();
        for (int attempt = 0; attempt < OCEAN_SPAWN_SEARCH_ATTEMPTS; attempt++) {
            OceanChunkGenerator.SpawnBeachPosition generated =
                oceanGenerator.findSpawnBeachPosition(
                    oceanDistance,
                    searchSalt + OCEAN_SPAWN_ATTEMPT_SALT * attempt
                );
            if (generated == null) break;

            BlockPos predicted = generated.feet();
            ocean.getChunk(predicted.getX() >> 4, predicted.getZ() >> 4);
            BlockPos safeFeet = actualSafeBeachFeet(
                ocean,
                predicted,
                generated.oceanDistance()
            );
            if (safeFeet == null) continue;

            float yaw = player.getYRot();
            player.teleportTo(
                ocean,
                safeFeet.getX() + 0.5,
                safeFeet.getY(),
                safeFeet.getZ() + 0.5,
                yaw,
                0.0F
            );
            player.setRespawnPosition(
                OCEAN_DIMENSION,
                safeFeet,
                yaw,
                true,
                false
            );
            LOGGER.info(
                "Placed player {} on spawn-island beach at {} {} {} ({} blocks from ocean)",
                player.getName().getString(),
                safeFeet.getX(),
                safeFeet.getY(),
                safeFeet.getZ(),
                generated.oceanDistance()
            );
            return true;
        }

        LOGGER.error(
            "Could not find an unobstructed sandy ocean spawn for player {}",
            player.getName().getString()
        );
        return false;
    }

    private BlockPos actualSafeBeachFeet(
        ServerLevel level,
        BlockPos predicted,
        int expectedOceanDistance
    ) {
        int x = predicted.getX();
        int z = predicted.getZ();
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        if (
            y <= level.getMinBuildHeight() ||
            y + 1 >= level.getMaxBuildHeight()
        ) return null;

        BlockPos feet = new BlockPos(x, y, z);
        BlockState ground = level.getBlockState(feet.below());
        if (!ground.is(Blocks.SAND)) return null;
        if (!level.getBlockState(feet).isAir()) return null;
        if (!level.getBlockState(feet.above()).isAir()) return null;
        return hasActualOceanAtDistance(
            level,
            x,
            z,
            expectedOceanDistance
        ) ? feet : null;
    }

    private boolean hasActualOceanAtDistance(
        ServerLevel level,
        int x,
        int z,
        int expectedDistance
    ) {
        for (int distance = 1; distance <= 3; distance++) {
            for (int dx = -distance; dx <= distance; dx++) {
                for (int dz = -distance; dz <= distance; dz++) {
                    if (
                        Math.max(Math.abs(dx), Math.abs(dz)) != distance
                    ) continue;
                    int sampleX = x + dx;
                    int sampleZ = z + dz;
                    level.getChunk(sampleX >> 4, sampleZ >> 4);
                    if (
                        level.getFluidState(
                            new BlockPos(sampleX, level.getSeaLevel(), sampleZ)
                        ).is(FluidTags.WATER)
                    ) {
                        return distance == expectedDistance;
                    }
                }
            }
        }
        return false;
    }

    @SubscribeEvent
    public void onCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(Commands.literal("island")
            .requires(source -> source.hasPermission(2))
            .executes(ctx -> {
                ServerPlayer player = ctx.getSource().getPlayerOrException();
                int px = player.getBlockX();
                int pz = player.getBlockZ();

                ServerLevel serverLevel = player.serverLevel();
                ChunkGenerator gen =
                    serverLevel.getChunkSource().getGenerator();

                int[] island;
                if (gen instanceof OceanChunkGenerator oceanGen) {
                    oceanGen.syncSeedFromLevel(serverLevel);
                    island = oceanGen.findNearestIslandCenter(px, pz);
                } else {
                    ctx.getSource().sendSuccess(
                        () -> Component.literal("Not an ocean world"),
                        false
                    );
                    return 0;
                }

                int fx = island[0];
                int fz = island[1];
                serverLevel.getChunk(fx >> 4, fz >> 4);
                int surfaceY = serverLevel.getHeight(
                    Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                    fx,
                    fz
                );
                player.teleportTo(fx + 0.5, surfaceY, fz + 0.5);
                ctx.getSource().sendSuccess(
                    () -> Component.literal(
                        "Island at " + fx + " " + fz + " y=" + surfaceY
                    ),
                    false
                );
                return 1;
            }));

        dispatcher.register(Commands.literal("boulder")
            .requires(source -> source.hasPermission(2))
            .executes(ctx -> {
                ServerPlayer player =
                    ctx.getSource().getPlayerOrException();

                ServerLevel serverLevel = player.serverLevel();
                ChunkGenerator gen =
                    serverLevel.getChunkSource().getGenerator();

                if (!(gen instanceof OceanChunkGenerator oceanGen)) {
                    ctx.getSource().sendSuccess(
                        () -> Component.literal("Not an ocean world"),
                        false
                    );
                    return 0;
                }

                oceanGen.syncSeedFromLevel(serverLevel);
                int[][] positions = oceanGen.getSpawnBoulderPositions();
                int total = positions.length;
                if (total == 0) {
                    ctx.getSource().sendFailure(Component.literal(
                        "No valid boulders were generated on the spawn island"
                    ));
                    return 0;
                }

                // Загрузить посещённые валуны из persistent data
                CompoundTag data = player.getPersistentData();
                CompoundTag visited =
                    data.getCompound(VISITED_BOULDERS_TAG);

                // Найти ближайший непосещённый валун
                int px = player.getBlockX();
                int pz = player.getBlockZ();
                double bestDist = Double.MAX_VALUE;
                int bestIdx = -1;
                for (int i = 0; i < total; i++) {
                    int bx = positions[i][0];
                    int bz = positions[i][1];
                    String key = bx + "," + bz;
                    if (visited.contains(key)) continue;
                    double d =
                        (bx - px) * (double) (bx - px) +
                        (bz - pz) * (double) (bz - pz);
                    if (d < bestDist) {
                        bestDist = d;
                        bestIdx = i;
                    }
                }

                if (bestIdx == -1) {
                    // Все посещены — сбросить и начать заново
                    visited = new CompoundTag();
                    data.put(VISITED_BOULDERS_TAG, visited);
                    ctx.getSource().sendSuccess(
                        () -> Component.literal(
                            "All " + total +
                            " boulders visited! Reset. " +
                            "Teleporting to nearest..."
                        ),
                        false
                    );

                    // Найти ближайший заново
                    bestDist = Double.MAX_VALUE;
                    for (int i = 0; i < total; i++) {
                        int bx = positions[i][0];
                        int bz = positions[i][1];
                        double d =
                            (bx - px) * (double) (bx - px) +
                            (bz - pz) * (double) (bz - pz);
                        if (d < bestDist) {
                            bestDist = d;
                            bestIdx = i;
                        }
                    }
                }

                int bx = positions[bestIdx][0];
                int bz = positions[bestIdx][1];
                serverLevel.getChunk(bx >> 4, bz >> 4);
                int surfaceY = serverLevel.getHeight(
                    Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                    bx,
                    bz
                );

                // Пометить как посещённый
                int visitedCount = visited.getAllKeys().size() + 1;
                visited.putBoolean(bx + "," + bz, true);
                data.put(VISITED_BOULDERS_TAG, visited);

                player.teleportTo(bx + 0.5, surfaceY, bz + 0.5);
                ctx.getSource().sendSuccess(
                    () -> Component.literal(
                        "Boulders on spawn: " + total +
                        " | Visited: " + visitedCount + "/" + total +
                        " | Teleported to " + bx + " " + bz +
                        " y=" + surfaceY
                    ),
                    false
                );
                return 1;
            }));
    }
}
