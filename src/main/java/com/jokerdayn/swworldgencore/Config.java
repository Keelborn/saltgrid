package com.jokerdayn.swworldgencore;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class Config {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.BooleanValue SPAWN_NEW_PLAYERS_IN_OCEAN =
        BUILDER
            .comment(
                "Place new players on a safe beach in the ocean dimension."
            )
            .define("spawnNewPlayersInOcean", true);

    public static final ModConfigSpec.BooleanValue RESPAWN_IN_OCEAN =
        BUILDER
            .comment(
                "Redirect fallback respawns to the safe ocean beach."
            )
            .define("respawnInOcean", true);

    static final ModConfigSpec SPEC = BUILDER.build();

    private Config() {}
}
