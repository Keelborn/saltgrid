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
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.registries.RegisterEvent;
import com.jokerdayn.swworldgencore.worldgen.OceanChunkGenerator;

@Mod(SWWorldgenCore.MODID)
public class SWWorldgenCore {
    public static final String MODID = "swworldgencore";
    public static final Logger LOGGER = LogUtils.getLogger();

    // хеш тот же что в OceanChunkGenerator — дублируем для команды
    private static long cmdSeed = 0;

    public static void setSeed(long s) { cmdSeed = s; }

    public SWWorldgenCore(IEventBus modEventBus, ModContainer modContainer) {
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
        LOGGER.info("HELLO FROM COMMON SETUP");
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        LOGGER.info("HELLO from server starting");
    }

    @SubscribeEvent
    public void onCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(Commands.literal("island").executes(ctx -> {
            ServerPlayer player = ctx.getSource().getPlayerOrException();
            int px = (int) player.getX(), pz = (int) player.getZ();

            int[] best = {0, 0};
            double bestDist = Double.MAX_VALUE;

            int cellX = Math.floorDiv(px, 2048), cellZ = Math.floorDiv(pz, 2048);

            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    int cx = cellX + dx, cz = cellZ + dz;
                    if (hash(cx * 11, cz * 13) > 0.6) continue;

                    int ix = cx * 2048 + 768 + (int)(hash(cx * 2, cz * 2) * 512);
                    int iz = cz * 2048 + 768 + (int)(hash(cx * 2 + 1, cz * 2 + 1) * 512);

                    double dist = Math.pow(ix - px, 2) + Math.pow(iz - pz, 2);
                    if (dist < bestDist) {
                        bestDist = dist;
                        best[0] = ix;
                        best[1] = iz;
                    }
                }
            }

            int fx = best[0], fz = best[1];
            player.teleportTo(fx + 0.5, 70, fz + 0.5);
            ctx.getSource().sendSuccess(() -> Component.literal("Island at " + fx + " " + fz), false);
            return 1;
        }));
    }

    private static double hash(int x, int z) {
        long n = (long) x * 73856093L ^ (long) z * 19349663L ^ cmdSeed;
        n = (n ^ (n >> 13)) * 1274126177L;
        n = n ^ (n >> 16);
        return (double) (n & 0x7FFFFFFFL) / (double) 0x7FFFFFFFL;
    }
}
