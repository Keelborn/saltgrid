package com.jokerdayn.swworldgencore.command;

import com.jokerdayn.swworldgencore.event.PlayerDataKeys;
import com.jokerdayn.swworldgencore.worldgen.OceanChunkGenerator;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/** {@code /island} and {@code /boulder} — navigation aids for testing the generator. */
final class IslandCommands {

    private IslandCommands() {}

    static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("island")
            .requires(source -> source.hasPermission(CommandSupport.PERMISSION_LEVEL))
            .executes(IslandCommands::teleportToNearestIsland));

        dispatcher.register(Commands.literal("boulder")
            .requires(source -> source.hasPermission(CommandSupport.PERMISSION_LEVEL))
            .executes(IslandCommands::teleportToNextBoulder));
    }

    private static int teleportToNearestIsland(CommandContext<CommandSourceStack> context)
            throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        ServerLevel level = player.serverLevel();
        OceanChunkGenerator generator = CommandSupport.generatorFor(context.getSource(), level);
        if (generator == null) return 0;

        int[] island = generator.findNearestIslandCenter(player.getBlockX(), player.getBlockZ());
        BlockPos target = CommandSupport.surfaceAt(level, island[0], island[1]);
        player.teleportTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5);
        context.getSource().sendSuccess(
            () -> Component.literal(
                "Island at " + target.getX() + " " + target.getZ() + " y=" + target.getY()
            ),
            false
        );
        return 1;
    }

    /**
     * Hops to the nearest spawn-island boulder the player has not seen yet, remembering
     * visits in their persistent data and wrapping around once all have been visited.
     */
    private static int teleportToNextBoulder(CommandContext<CommandSourceStack> context)
            throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        ServerLevel level = player.serverLevel();
        OceanChunkGenerator generator = CommandSupport.generatorFor(context.getSource(), level);
        if (generator == null) return 0;

        int[][] positions = generator.getSpawnBoulderPositions();
        int total = positions.length;
        if (total == 0) {
            context.getSource().sendFailure(Component.literal(
                "No valid boulders were generated on the spawn island"
            ));
            return 0;
        }

        CompoundTag data = player.getPersistentData();
        CompoundTag visited = data.getCompound(PlayerDataKeys.VISITED_BOULDERS);
        int px = player.getBlockX();
        int pz = player.getBlockZ();

        int index = nearestIndex(positions, px, pz, visited);
        boolean wrapped = index < 0;
        if (wrapped) {
            visited = new CompoundTag();
            data.put(PlayerDataKeys.VISITED_BOULDERS, visited);
            index = nearestIndex(positions, px, pz, visited);
            context.getSource().sendSuccess(
                () -> Component.literal(
                    "All " + total + " boulders visited! Reset. Teleporting to nearest..."
                ),
                false
            );
        }

        int boulderX = positions[index][0];
        int boulderZ = positions[index][1];
        BlockPos target = CommandSupport.surfaceAt(level, boulderX, boulderZ);

        int visitedCount = visited.getAllKeys().size() + 1;
        visited.putBoolean(boulderX + "," + boulderZ, true);
        data.put(PlayerDataKeys.VISITED_BOULDERS, visited);

        player.teleportTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5);
        context.getSource().sendSuccess(
            () -> Component.literal(
                "Boulders on spawn: " + total
                    + " | Visited: " + visitedCount + "/" + total
                    + " | Teleported to " + target.getX() + " " + target.getZ()
                    + " y=" + target.getY()
            ),
            false
        );
        return 1;
    }

    /** @return index of the nearest unvisited boulder, or {@code -1} if all were visited */
    private static int nearestIndex(int[][] positions, int px, int pz, CompoundTag visited) {
        double bestDistanceSq = Double.MAX_VALUE;
        int best = -1;
        for (int i = 0; i < positions.length; i++) {
            int x = positions[i][0];
            int z = positions[i][1];
            if (visited.contains(x + "," + z)) continue;
            double distanceSq =
                (x - px) * (double) (x - px) + (z - pz) * (double) (z - pz);
            if (distanceSq < bestDistanceSq) {
                bestDistanceSq = distanceSq;
                best = i;
            }
        }
        return best;
    }
}
