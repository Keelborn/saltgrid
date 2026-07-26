package com.jokerdayn.swworldgencore.worldgen;

import com.jokerdayn.swworldgencore.diagnostics.GeneratorDiagnostics;
import com.jokerdayn.swworldgencore.worldgen.spawn.SpawnBeachFinder;
import com.jokerdayn.swworldgencore.worldgen.terrain.TerrainContext;
import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.neoforged.fml.loading.LoadingModList;

/**
 * Headless check that the spawn-beach search finds a valid, self-consistent beach for
 * representative seeds.
 *
 * <p>Run with {@code ./gradlew spawnSearchSmokeTest}. It exercises only the deterministic
 * terrain field, so it needs no world and no chunks — that is exactly the property the
 * generator relies on when it places a player before any chunk has loaded.</p>
 *
 * <p>Drives the real API rather than reflecting into private methods, so a refactor that
 * breaks the contract fails at compile time instead of at run time.</p>
 */
public final class OceanSpawnSearchSmoke {

    /** 1 and {@code Long.MAX_VALUE} probe the edges of the seed mixing. */
    private static final long[] SEEDS = {
        1L,
        -7_493_821_045L,
        5_916_308_533_714_060_029L,
        Long.MAX_VALUE,
    };

    /** Matches {@code data/swworldgencore/dimension/ocean.json}. */
    private static final int SEA_LEVEL = 63;

    private static final int SALTS_PER_DISTANCE = 4;
    private static final int MAX_OCEAN_DISTANCE = 3;

    private OceanSpawnSearchSmoke() {}

    public static void main(String[] args) {
        LoadingModList.of(List.of(), List.of(), List.of(), List.of(), Map.of());
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();

        GeneratorDiagnostics diagnostics = new GeneratorDiagnostics();
        // Instrumentation would only add noise and MXBean cost to a headless check.
        diagnostics.setEnabled(false);

        int checks = 0;
        for (long seed : SEEDS) {
            TerrainContext terrain = new TerrainContext(seed, SEA_LEVEL, diagnostics);
            SpawnBeachFinder finder = new SpawnBeachFinder(terrain);

            for (int distance = 1; distance <= MAX_OCEAN_DISTANCE; distance++) {
                for (long salt = 0; salt < SALTS_PER_DISTANCE; salt++) {
                    SpawnBeachFinder.SpawnBeachPosition spawn = finder.find(distance, salt);
                    require(spawn != null, seed, distance, salt, "no result");

                    require(
                        spawn.oceanDistance() == distance,
                        seed, distance, salt, "wrong reported distance"
                    );

                    int x = spawn.feet().getX();
                    int z = spawn.feet().getZ();

                    // The reported distance must match an independent measurement of the
                    // same field, not just the value the search was asked for.
                    int measured =
                        terrain.columns.nearestOceanDistance(x, z, MAX_OCEAN_DISTANCE);
                    require(
                        measured == distance,
                        seed, distance, salt, "wrong measured distance: " + measured
                    );

                    require(
                        finder.isPredictedSand(x, z),
                        seed, distance, salt, "result is not predicted sand"
                    );

                    // Feet must sit exactly one block above the solid floor.
                    int floor = terrain.columns.floorAt(x, z);
                    require(
                        spawn.feet().getY() == floor + 1,
                        seed, distance, salt,
                        "feet at y=" + spawn.feet().getY() + " but floor is " + floor
                    );
                    checks++;
                }
            }
        }
        System.out.println("Ocean spawn search smoke test passed: " + checks + " checks");
    }

    private static void require(
        boolean condition,
        long seed,
        int distance,
        long salt,
        String message
    ) {
        if (condition) return;
        throw new IllegalStateException(
            message + " [seed=" + seed + ", distance=" + distance + ", salt=" + salt + ']'
        );
    }
}
