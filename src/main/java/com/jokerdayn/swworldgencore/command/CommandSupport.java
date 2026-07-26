package com.jokerdayn.swworldgencore.command;

import com.jokerdayn.swworldgencore.worldgen.OceanChunkGenerator;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.Heightmap;

/** Shared plumbing for the generator's operator commands. */
final class CommandSupport {

    /** Operator level required by every command in this package. */
    static final int PERMISSION_LEVEL = 2;

    private CommandSupport() {}

    /**
     * Resolves the ocean generator of the level a command is running in, reporting failure to
     * the source when the dimension uses a different generator.
     *
     * @return the generator, already bound to the world seed, or {@code null}
     */
    static OceanChunkGenerator generatorFor(CommandSourceStack source, ServerLevel level) {
        if (level.getChunkSource().getGenerator() instanceof OceanChunkGenerator generator) {
            generator.syncSeedFromLevel(level);
            return generator;
        }
        source.sendFailure(Component.literal(
            "This command only works in a dimension that uses OceanChunkGenerator."
        ));
        return null;
    }

    /**
     * Loads the chunk containing the position and returns a standing spot on its surface.
     *
     * <p>Loading is synchronous and may generate the chunk, which is acceptable for an
     * operator command but never for a hot path.</p>
     */
    static BlockPos surfaceAt(ServerLevel level, int x, int z) {
        level.getChunk(x >> 4, z >> 4);
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        return new BlockPos(x, y, z);
    }
}
