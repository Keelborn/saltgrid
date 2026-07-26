package com.jokerdayn.swworldgencore;

import com.jokerdayn.swworldgencore.registry.ModBlocks;
import com.jokerdayn.swworldgencore.registry.ModCreativeTabs;
import com.jokerdayn.swworldgencore.registry.ModItems;
import com.jokerdayn.swworldgencore.worldgen.OceanBiomeSource;
import com.jokerdayn.swworldgencore.worldgen.OceanChunkGenerator;
import com.mojang.logging.LogUtils;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.registries.RegisterEvent;
import org.slf4j.Logger;

/**
 * Mod entry point.
 *
 * <p>Deliberately thin. Content registration lives in {@code registry}, commands in
 * {@code command}, gameplay event handling in {@code event}, and world generation in
 * {@code worldgen} — all of which are wired up by annotations or by the calls below.</p>
 */
@Mod(SWWorldgenCore.MODID)
public class SWWorldgenCore {

    public static final String MODID = "swworldgencore";
    public static final Logger LOGGER = LogUtils.getLogger();

    /** The ocean dimension declared in {@code data/swworldgencore/dimension/ocean.json}. */
    public static final ResourceKey<Level> OCEAN_DIMENSION = ResourceKey.create(
        Registries.DIMENSION,
        ResourceLocation.fromNamespaceAndPath(MODID, "ocean")
    );

    public SWWorldgenCore(IEventBus modEventBus, ModContainer modContainer) {
        ModBlocks.register(modEventBus);
        ModItems.register(modEventBus);
        ModCreativeTabs.register(modEventBus);
        modEventBus.addListener(this::registerWorldgenCodecs);
        modEventBus.addListener(this::commonSetup);
        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
    }

    /**
     * Registers the generator and biome-source codecs so the dimension JSON can name them.
     *
     * <p>{@code RegisterEvent} fires once per registry; {@code event.register} is a no-op for
     * the registries it does not match, so both calls are made unconditionally.</p>
     */
    private void registerWorldgenCodecs(RegisterEvent event) {
        event.register(
            Registries.CHUNK_GENERATOR,
            ResourceLocation.fromNamespaceAndPath(MODID, "ocean"),
            () -> OceanChunkGenerator.CODEC
        );
        event.register(
            Registries.BIOME_SOURCE,
            ResourceLocation.fromNamespaceAndPath(MODID, "ocean_biomes"),
            () -> OceanBiomeSource.CODEC
        );
        if (event.getRegistryKey().equals(Registries.BIOME_SOURCE)) {
            LOGGER.info("Registered OceanChunkGenerator + OceanBiomeSource codecs");
        }
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        LOGGER.info("swworldgencore loaded");
    }
}
