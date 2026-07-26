package com.jokerdayn.swworldgencore.event;

import com.jokerdayn.swworldgencore.Config;
import com.jokerdayn.swworldgencore.SWWorldgenCore;
import com.jokerdayn.swworldgencore.worldgen.OceanChunkGenerator;
import com.jokerdayn.swworldgencore.worldgen.spawn.SpawnBeachFinder;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.LevelEvent;

/**
 * Places players on the ocean dimension's spawn beach.
 *
 * <p>The generator predicts a beach from its terrain field without loading anything; this
 * handler then loads the chunk and confirms the prediction against the real blocks, because
 * decoration or an earlier player could have obstructed the spot. Prediction and
 * verification are separate on purpose — the terrain field is cheap and exact about
 * heights, but knows nothing about what was later built on top.</p>
 */
@EventBusSubscriber(modid = SWWorldgenCore.MODID)
public final class OceanSpawnHandler {

    /** Independent beach candidates tried before giving up. */
    private static final int SEARCH_ATTEMPTS = 24;

    /** Golden-ratio stride, so successive attempts explore unrelated directions. */
    private static final long ATTEMPT_SALT = 0x9E3779B97F4A7C15L;

    /** How far from open water the player may end up standing. */
    private static final int MAX_OCEAN_DISTANCE = 3;

    private OceanSpawnHandler() {}

    /**
     * Binds the generator to the world seed before anything can generate.
     *
     * <p>This is the path that matters: the defensive {@code syncSeedFromLevel} calls inside
     * the generation entry points exist only as a backstop.</p>
     */
    @SubscribeEvent
    public static void onLevelLoad(LevelEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel serverLevel)) return;
        if (serverLevel.getChunkSource().getGenerator() instanceof OceanChunkGenerator generator) {
            generator.syncSeedFromLevel(serverLevel);
            // Earliest point at which the config is guaranteed to be loaded, and still before
            // the dimension generates anything.
            generator.diagnostics().setEnabled(Config.GENERATOR_BENCHMARK.get());
        }
    }

    @SubscribeEvent
    public static void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (!Config.SPAWN_NEW_PLAYERS_IN_OCEAN.get()) return;

        boolean hasOceanSpawn = SWWorldgenCore.OCEAN_DIMENSION.equals(player.getRespawnDimension())
            && player.getRespawnPosition() != null;
        // Re-place the player if the flag is set but the respawn point was lost, otherwise
        // they would wake up in the overworld with no way back.
        if (player.getPersistentData().getBoolean(PlayerDataKeys.OCEAN_SPAWNED)
            && hasOceanSpawn) {
            return;
        }

        if (teleportToOceanSpawn(player)) {
            player.getPersistentData().putBoolean(PlayerDataKeys.OCEAN_SPAWNED, true);
        }
    }

    @SubscribeEvent
    public static void onPlayerRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (!Config.RESPAWN_IN_OCEAN.get()) return;
        if (player.level().dimension().equals(SWWorldgenCore.OCEAN_DIMENSION)) return;
        if (teleportToOceanSpawn(player)) {
            player.getPersistentData().putBoolean(PlayerDataKeys.OCEAN_SPAWNED, true);
        }
    }

    /** Persistent data is not carried across a respawn automatically. */
    @SubscribeEvent
    public static void onPlayerClone(PlayerEvent.Clone event) {
        CompoundTag original = event.getOriginal().getPersistentData();
        CompoundTag replacement = event.getEntity().getPersistentData();
        if (original.contains(PlayerDataKeys.OCEAN_SPAWNED)) {
            replacement.putBoolean(
                PlayerDataKeys.OCEAN_SPAWNED,
                original.getBoolean(PlayerDataKeys.OCEAN_SPAWNED)
            );
        }
        if (original.contains(PlayerDataKeys.VISITED_BOULDERS)) {
            replacement.put(
                PlayerDataKeys.VISITED_BOULDERS,
                original.getCompound(PlayerDataKeys.VISITED_BOULDERS).copy()
            );
        }
    }

    private static boolean teleportToOceanSpawn(ServerPlayer player) {
        if (player.getServer() == null) return false;
        ServerLevel ocean = player.getServer().getLevel(SWWorldgenCore.OCEAN_DIMENSION);
        if (ocean == null) {
            SWWorldgenCore.LOGGER.error(
                "Ocean dimension is unavailable; player {} was not teleported",
                player.getName().getString()
            );
            return false;
        }

        ChunkGenerator generator = ocean.getChunkSource().getGenerator();
        if (!(generator instanceof OceanChunkGenerator oceanGenerator)) {
            SWWorldgenCore.LOGGER.error(
                "Dimension {} does not use OceanChunkGenerator",
                SWWorldgenCore.OCEAN_DIMENSION.location()
            );
            return false;
        }
        oceanGenerator.syncSeedFromLevel(ocean);

        int oceanDistance = 1 + player.getRandom().nextInt(MAX_OCEAN_DISTANCE);
        long searchSalt = player.getUUID().getMostSignificantBits()
            ^ player.getUUID().getLeastSignificantBits()
            ^ player.getRandom().nextLong();

        for (int attempt = 0; attempt < SEARCH_ATTEMPTS; attempt++) {
            SpawnBeachFinder.SpawnBeachPosition predicted =
                oceanGenerator.findSpawnBeachPosition(
                    oceanDistance, searchSalt + ATTEMPT_SALT * attempt
                );
            // No candidate at all means this seed has no matching coast; retrying with a
            // different salt cannot help.
            if (predicted == null) break;

            BlockPos feet = predicted.feet();
            ocean.getChunk(feet.getX() >> 4, feet.getZ() >> 4);
            BlockPos safeFeet = verifiedBeachFeet(
                ocean, oceanGenerator, feet, predicted.oceanDistance()
            );
            if (safeFeet == null) continue;

            float yaw = player.getYRot();
            player.teleportTo(
                ocean, safeFeet.getX() + 0.5, safeFeet.getY(), safeFeet.getZ() + 0.5, yaw, 0.0F
            );
            player.setRespawnPosition(SWWorldgenCore.OCEAN_DIMENSION, safeFeet, yaw, true, false);
            SWWorldgenCore.LOGGER.info(
                "Placed player {} on spawn-island beach at {} {} {} ({} blocks from ocean)",
                player.getName().getString(),
                safeFeet.getX(), safeFeet.getY(), safeFeet.getZ(),
                predicted.oceanDistance()
            );
            return true;
        }

        SWWorldgenCore.LOGGER.error(
            "Could not find an unobstructed sandy ocean spawn for player {}",
            player.getName().getString()
        );
        return false;
    }

    /** Confirms the predicted spot against the generated blocks. */
    private static BlockPos verifiedBeachFeet(
        ServerLevel level,
        OceanChunkGenerator generator,
        BlockPos predicted,
        int expectedOceanDistance
    ) {
        int x = predicted.getX();
        int z = predicted.getZ();
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        if (y <= level.getMinBuildHeight() || y + 1 >= level.getMaxBuildHeight()) return null;

        BlockPos feet = new BlockPos(x, y, z);
        if (!level.getBlockState(feet.below()).is(Blocks.SAND)) return null;
        if (!level.getBlockState(feet).isAir()) return null;
        if (!level.getBlockState(feet.above()).isAir()) return null;
        return actualOceanDistance(level, generator, x, z) == expectedOceanDistance ? feet : null;
    }

    /**
     * Chebyshev distance to real water at the generator's sea level.
     *
     * <p>Uses {@link ChunkGenerator#getSeaLevel()} rather than {@code Level#getSeaLevel()}:
     * the latter is hard-coded to 63 in vanilla and would probe the wrong layer as soon as
     * the dimension is configured with a different {@code sea_level}.</p>
     */
    private static int actualOceanDistance(
        ServerLevel level,
        OceanChunkGenerator generator,
        int x,
        int z
    ) {
        int waterY = generator.getSeaLevel();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int distance = 1; distance <= MAX_OCEAN_DISTANCE; distance++) {
            for (int dx = -distance; dx <= distance; dx++) {
                for (int dz = -distance; dz <= distance; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != distance) continue;
                    int sampleX = x + dx;
                    int sampleZ = z + dz;
                    level.getChunk(sampleX >> 4, sampleZ >> 4);
                    cursor.set(sampleX, waterY, sampleZ);
                    if (level.getFluidState(cursor).is(FluidTags.WATER)) return distance;
                }
            }
        }
        return -1;
    }
}
