package com.jokerdayn.swworldgencore;

import org.slf4j.Logger;
import com.mojang.logging.LogUtils;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.registries.RegisterEvent;
import com.jokerdayn.swworldgencore.worldgen.OceanChunkGenerator;

@Mod(SWWorldgenCore.MODID)
public class SWWorldgenCore {
    public static final String MODID = "swworldgencore";
    public static final Logger LOGGER = LogUtils.getLogger();

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
}
