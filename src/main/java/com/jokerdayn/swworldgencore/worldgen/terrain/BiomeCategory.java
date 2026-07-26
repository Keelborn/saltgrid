package com.jokerdayn.swworldgencore.worldgen.terrain;

/**
 * The generator's own biome classification.
 *
 * <p>Drives both the biome shown on the F3 screen (through
 * {@code OceanBiomeSource}) and several decoration decisions, so it must agree with what
 * the terrain pass actually wrote.</p>
 */
public enum BiomeCategory {
    OCEAN,
    DEEP_OCEAN,
    BEACH,
    TROPICS,
    SAVANNA,
    VOLCANO
}
