package com.jokerdayn.swworldgencore.command;

import static com.jokerdayn.swworldgencore.worldgen.terrain.IslandSettings.CELL;

import com.jokerdayn.swworldgencore.worldgen.OceanChunkGenerator;
import com.jokerdayn.swworldgencore.worldgen.terrain.GridIslandField;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

/** {@code /volcano} — teleports to a safe viewpoint on the nearest volcano's caldera rim. */
final class VolcanoCommand {

    /** Blocks searched outward from the predicted rim point for somewhere safe to stand. */
    private static final int SAFE_SEARCH_RADIUS = 10;

    private VolcanoCommand() {}

    static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("volcano")
            .requires(source -> source.hasPermission(CommandSupport.PERMISSION_LEVEL))
            .executes(VolcanoCommand::teleportToNearestVolcano));
    }

    private static int teleportToNearestVolcano(CommandContext<CommandSourceStack> context)
            throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = source.getPlayerOrException();
        ServerLevel level = player.serverLevel();
        OceanChunkGenerator generator = CommandSupport.generatorFor(source, level);
        if (generator == null) return 0;

        int[] volcano = generator.findNearestVolcano(
            player.getBlockX(), player.getBlockZ(), GridIslandField.MAX_CELL_SEARCH_RADIUS
        );
        if (volcano == null) {
            source.sendFailure(Component.literal(
                "No volcano found within "
                    + (GridIslandField.MAX_CELL_SEARCH_RADIUS * CELL) + " blocks."
            ));
            return 0;
        }

        int targetX = volcano[0];
        int targetZ = volcano[1];
        level.getChunk(targetX >> 4, targetZ >> 4);
        BlockPos safe = findSafeGround(level, targetX, targetZ);
        if (safe == null) {
            source.sendFailure(Component.literal(
                "Volcano found, but no safe teleport spot could be determined."
            ));
            return 0;
        }

        player.teleportTo(
            level, safe.getX() + 0.5, safe.getY(), safe.getZ() + 0.5,
            player.getYRot(), player.getXRot()
        );
        source.sendSuccess(
            () -> Component.literal(
                "Volcano found at X=" + volcano[2] + ", Z=" + volcano[3]
                    + ". Teleporting to the safe rim."
            ),
            true
        );
        return 1;
    }

    /** Expanding ring search for solid, non-burning ground with two blocks of headroom. */
    private static BlockPos findSafeGround(ServerLevel level, int originX, int originZ) {
        for (int radius = 0; radius <= SAFE_SEARCH_RADIUS; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (radius > 0 && Math.abs(dx) != radius && Math.abs(dz) != radius) continue;

                    int x = originX + dx;
                    int z = originZ + dz;
                    int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                    BlockPos feet = new BlockPos(x, y, z);
                    if (!isSafeGround(level.getBlockState(feet.below()))) continue;
                    if (!level.getBlockState(feet).isAir()) continue;
                    if (!level.getBlockState(feet.above()).isAir()) continue;
                    return feet;
                }
            }
        }
        return null;
    }

    private static boolean isSafeGround(BlockState state) {
        return !state.isAir()
            && state.getFluidState().isEmpty()
            && !state.is(Blocks.MAGMA_BLOCK)
            && !state.is(Blocks.FIRE)
            && !state.is(Blocks.SOUL_FIRE)
            && !state.is(Blocks.CAMPFIRE)
            && !state.is(Blocks.SOUL_CAMPFIRE);
    }
}
