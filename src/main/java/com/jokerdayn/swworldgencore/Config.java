package com.jokerdayn.swworldgencore;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Common (server-side) configuration.
 *
 * <p>Values must not be read before the config has loaded; the earliest safe point is
 * {@code LevelEvent.Load}, which is where the generator's instrumentation switch is applied.</p>
 */
public final class Config {

    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.BooleanValue SPAWN_NEW_PLAYERS_IN_OCEAN = BUILDER
        .comment("Place new players on a safe beach in the ocean dimension.")
        .define("spawnNewPlayersInOcean", true);

    public static final ModConfigSpec.BooleanValue RESPAWN_IN_OCEAN = BUILDER
        .comment("Redirect fallback respawns to the safe ocean beach.")
        .define("respawnInOcean", true);

    public static final ModConfigSpec.BooleanValue GENERATOR_BENCHMARK = BUILDER
        .comment(
            "Collect world-generation timing and allocation statistics (/oceangen benchmark).",
            "Costs a few percent of generation throughput. Turn this off on a production",
            "server unless you are actively profiling; /oceangen benchmark enabled <bool>",
            "toggles it at runtime without a restart."
        )
        .define("generatorBenchmark", true);

    static final ModConfigSpec SPEC = BUILDER.build();

    private Config() {}
}
