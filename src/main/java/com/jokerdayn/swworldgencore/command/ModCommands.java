package com.jokerdayn.swworldgencore.command;

import com.jokerdayn.swworldgencore.SWWorldgenCore;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * Single registration point for every command the mod adds.
 *
 * <p>Previously split between the mod class and the chunk generator, which made it hard to
 * see what the mod actually adds to the command tree.</p>
 */
@EventBusSubscriber(modid = SWWorldgenCore.MODID)
public final class ModCommands {

    private ModCommands() {}

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        IslandCommands.register(event.getDispatcher());
        VolcanoCommand.register(event.getDispatcher());
        BenchmarkCommand.register(event.getDispatcher());
    }
}
