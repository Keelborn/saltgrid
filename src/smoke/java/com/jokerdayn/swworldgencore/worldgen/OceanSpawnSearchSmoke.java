package com.jokerdayn.swworldgencore.worldgen;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.neoforged.fml.loading.LoadingModList;

public final class OceanSpawnSearchSmoke {
    private static final long[] SEEDS = {
        1L,
        -7_493_821_045L,
        5_916_308_533_714_060_029L,
        Long.MAX_VALUE,
    };

    private OceanSpawnSearchSmoke() {}

    public static void main(String[] args) throws Exception {
        LoadingModList.of(
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            Map.of()
        );
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        Method nearestOceanDistance = OceanChunkGenerator.class
            .getDeclaredMethod("nearestOceanDistance", int.class, int.class, int.class);
        nearestOceanDistance.setAccessible(true);
        Method isPredictedSpawnSand = OceanChunkGenerator.class
            .getDeclaredMethod("isPredictedSpawnSand", int.class, int.class);
        isPredictedSpawnSand.setAccessible(true);

        int checks = 0;
        for (long seed : SEEDS) {
            OceanBiomeSource biomes = new OceanBiomeSource(
                null,
                null,
                null,
                null,
                null,
                null
            );
            OceanChunkGenerator generator =
                new OceanChunkGenerator(biomes, seed, 63);
            for (int distance = 1; distance <= 3; distance++) {
                for (long salt = 0; salt < 4; salt++) {
                    OceanChunkGenerator.SpawnBeachPosition spawn =
                        generator.findSpawnBeachPosition(distance, salt);
                    require(spawn != null, seed, distance, salt, "no result");
                    require(
                        spawn.oceanDistance() == distance,
                        seed,
                        distance,
                        salt,
                        "wrong reported distance"
                    );
                    int measuredDistance = (int) nearestOceanDistance.invoke(
                        generator,
                        spawn.feet().getX(),
                        spawn.feet().getZ(),
                        3
                    );
                    require(
                        measuredDistance == distance,
                        seed,
                        distance,
                        salt,
                        "wrong measured distance: " + measuredDistance
                    );
                    boolean onSand = (boolean) isPredictedSpawnSand.invoke(
                        generator,
                        spawn.feet().getX(),
                        spawn.feet().getZ()
                    );
                    require(
                        onSand,
                        seed,
                        distance,
                        salt,
                        "result is not predicted sand"
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
        if (!condition) {
            throw new IllegalStateException(
                message +
                " [seed=" + seed +
                ", distance=" + distance +
                ", salt=" + salt +
                ']'
            );
        }
    }
}
