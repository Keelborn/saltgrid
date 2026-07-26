package com.jokerdayn.swworldgencore.registry;

import com.jokerdayn.swworldgencore.SWWorldgenCore;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/** The mod's single creative tab. */
public final class ModCreativeTabs {

    public static final DeferredRegister<CreativeModeTab> TABS =
        DeferredRegister.create(Registries.CREATIVE_MODE_TAB, SWWorldgenCore.MODID);

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> MAIN = TABS.register(
        "main",
        () -> CreativeModeTab.builder()
            .title(Component.translatable("itemGroup." + SWWorldgenCore.MODID))
            .icon(() -> new ItemStack(ModItems.PALM_SAPLING.get()))
            .displayItems((parameters, output) -> {
                output.accept(ModItems.SHELL.get());
                output.accept(ModItems.GROUND_DECORATION.get());
                output.accept(ModItems.PALM_SAPLING.get());
                output.accept(ModItems.PALM_LEAF.get());
                output.accept(ModItems.BRONZE_ORE.get());
                output.accept(ModItems.BRONZE_RAW.get());
                output.accept(ModItems.BRONZE_INGOT.get());
            })
            .build()
    );

    private ModCreativeTabs() {}

    public static void register(IEventBus modEventBus) {
        TABS.register(modEventBus);
    }
}
