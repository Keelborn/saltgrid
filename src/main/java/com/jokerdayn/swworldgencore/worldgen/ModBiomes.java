package com.jokerdayn.swworldgencore.worldgen;

import com.jokerdayn.swworldgencore.SWWorldgenCore;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.Biome;

public class ModBiomes {
    public static final ResourceKey<Biome> OCEAN      = key("ocean");
    public static final ResourceKey<Biome> DEEP_OCEAN = key("deep_ocean");
    public static final ResourceKey<Biome> BEACH      = key("beach");
    public static final ResourceKey<Biome> TROPICS    = key("tropics");
    public static final ResourceKey<Biome> SAVANNA    = key("savanna");
    public static final ResourceKey<Biome> VOLCANO    = key("volcano");

    private static ResourceKey<Biome> key(String name) {
        return ResourceKey.create(Registries.BIOME,
                ResourceLocation.fromNamespaceAndPath(SWWorldgenCore.MODID, name));
    }
}
