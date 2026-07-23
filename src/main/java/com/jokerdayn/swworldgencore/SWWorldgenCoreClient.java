package com.jokerdayn.swworldgencore;

import net.minecraft.client.renderer.BiomeColors;
import net.minecraft.core.BlockPos;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.RegisterColorHandlersEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;

@Mod(value = SWWorldgenCore.MODID, dist = Dist.CLIENT)
public class SWWorldgenCoreClient {
    private static final int PALM_FOLIAGE_COLOR = 0x399013;
    private static final int FALLBACK_GRASS_COLOR = 0x91BD59;

    public SWWorldgenCoreClient(
        IEventBus modEventBus,
        ModContainer container
    ) {
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
        modEventBus.addListener(this::onBlockColor);
    }

    private void onBlockColor(RegisterColorHandlersEvent.Block event) {
        event.register(
            (state, level, pos, tintIndex) -> PALM_FOLIAGE_COLOR,
            SWWorldgenCore.PALM_LEAF.get()
        );
        event.register(
            (state, level, pos, tintIndex) -> grassColor(level, pos),
            Blocks.SHORT_GRASS,
            Blocks.GRASS_BLOCK
        );
    }

    private static int grassColor(
        BlockAndTintGetter level,
        BlockPos pos
    ) {
        if (level == null || pos == null) return FALLBACK_GRASS_COLOR;
        if (
            level instanceof Level minecraftLevel &&
            minecraftLevel.dimension().equals(SWWorldgenCore.OCEAN_DIMENSION)
        ) {
            var biomeKey = minecraftLevel.getBiome(pos).unwrapKey().orElse(null);
            if (biomeKey != null) {
                return switch (biomeKey.location().getPath()) {
                    case "savanna" -> 0xA0B14A;
                    case "tropics" -> 0xA6A450;
                    default -> BiomeColors.getAverageGrassColor(level, pos);
                };
            }
        }
        return BiomeColors.getAverageGrassColor(level, pos);
    }
}
