package com.jokerdayn.swworldgencore.event;

import com.jokerdayn.swworldgencore.SWWorldgenCore;

/**
 * Keys the mod stores in a player's NBT persistent data.
 *
 * <p>Namespaced, because {@code getPersistentData()} is a shared bag every mod writes into.
 * These must be copied on {@code PlayerEvent.Clone} or they are lost on death and on the
 * end-portal return trip.</p>
 */
public final class PlayerDataKeys {

    /** Set once the player has been placed on the ocean-dimension spawn beach. */
    public static final String OCEAN_SPAWNED = SWWorldgenCore.MODID + ":spawned_in_ocean";

    /** Compound of {@code "x,z" -> true} for boulders already visited via {@code /boulder}. */
    public static final String VISITED_BOULDERS = SWWorldgenCore.MODID + ":visited_boulders";

    private PlayerDataKeys() {}
}
