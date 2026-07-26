package com.jokerdayn.swworldgencore.registry;

import com.jokerdayn.swworldgencore.SWWorldgenCore;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Item registrations: one {@link BlockItem} per placeable block, plus the bronze chain. */
public final class ModItems {

    public static final DeferredRegister.Items ITEMS =
        DeferredRegister.createItems(SWWorldgenCore.MODID);

    public static final DeferredHolder<Item, BlockItem> SHELL =
        ITEMS.register("shell", () -> new BlockItem(ModBlocks.SHELL.get(), new Item.Properties()));

    public static final DeferredHolder<Item, BlockItem> GROUND_DECORATION = ITEMS.register(
        "ground_decoration",
        () -> new BlockItem(ModBlocks.GROUND_DECORATION.get(), new Item.Properties())
    );

    public static final DeferredHolder<Item, BlockItem> PALM_SAPLING = ITEMS.register(
        "palm_sapling",
        () -> new BlockItem(ModBlocks.PALM_SAPLING.get(), new Item.Properties())
    );

    public static final DeferredHolder<Item, BlockItem> PALM_LEAF = ITEMS.register(
        "palm_leaf",
        () -> new BlockItem(ModBlocks.PALM_LEAF.get(), new Item.Properties())
    );

    public static final DeferredHolder<Item, BlockItem> BRONZE_ORE = ITEMS.register(
        "bronze_ore",
        () -> new BlockItem(ModBlocks.BRONZE_ORE.get(), new Item.Properties())
    );

    /** Raw bronze, dropped by {@link ModBlocks#BRONZE_ORE}. */
    public static final DeferredHolder<Item, Item> BRONZE_RAW =
        ITEMS.register("bronze_q", () -> new Item(new Item.Properties()));

    public static final DeferredHolder<Item, Item> BRONZE_INGOT =
        ITEMS.register("bronze_ingot", () -> new Item(new Item.Properties()));

    private ModItems() {}

    public static void register(IEventBus modEventBus) {
        ITEMS.register(modEventBus);
    }
}
