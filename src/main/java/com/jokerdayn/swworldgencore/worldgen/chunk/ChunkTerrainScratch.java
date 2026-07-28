package com.jokerdayn.swworldgencore.worldgen.chunk;

import static com.jokerdayn.swworldgencore.worldgen.GenSettings.LOCAL_SAMPLE_AREA;

import com.jokerdayn.swworldgencore.worldgen.terrain.GridIslandSample;
import com.jokerdayn.swworldgencore.worldgen.terrain.IslandLakeSample;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Reusable per-thread working set for one chunk of terrain.
 *
 * <p>Filling a chunk used to allocate sixteen arrays plus a sample struct — roughly 27 KB
 * of short-lived garbage per chunk, which at a few hundred chunks a second is tens of
 * megabytes a second of pure churn. One instance per generation thread removes all of it.</p>
 *
 * <p><b>Contract:</b> every array is fully rewritten each chunk. The classification pass
 * assigns all 256 entries of every column plan, including the "not applicable" cases, so
 * that no value can survive from the previous chunk. Flat arrays are also what let the
 * padded 18x18 grid be indexed without bounds juggling.</p>
 */
public final class ChunkTerrainScratch {

    // --- padded 18x18 grid: the chunk plus one ring of neighbours ---------------
    public final int[] floors = new int[LOCAL_SAMPLE_AREA];
    public final double[] spawnDistances = new double[LOCAL_SAMPLE_AREA];
    public final double[] spawnHeights = new double[LOCAL_SAMPLE_AREA];
    public final double[] gridHeights = new double[LOCAL_SAMPLE_AREA];
    public final double[] gridDistances = new double[LOCAL_SAMPLE_AREA];
    public final double[] volcanoCenterX = new double[LOCAL_SAMPLE_AREA];
    public final double[] volcanoCenterZ = new double[LOCAL_SAMPLE_AREA];
    public final int[] lavaLevels = new int[LOCAL_SAMPLE_AREA];
    public final int[] lakeWaterLevels = new int[LOCAL_SAMPLE_AREA];
    public final byte[] terrainFlags = new byte[LOCAL_SAMPLE_AREA];

    // --- 16x16 column plan -----------------------------------------------------
    public final int[] dirtLayers = new int[256];
    public final int[] strataShifts = new int[256];
    public final double[] fissures = new double[256];
    /** {@code 0} none, {@code 1} seagrass, {@code 2} tall seagrass. */
    public final byte[] seagrass = new byte[256];
    public final boolean[] underwaterSlabs = new boolean[256];
    public final BlockState[] surfaces = new BlockState[256];
    /** {@code null} when the column uses the per-y volcanic strata path instead. */
    public final BlockState[] subsurfaces = new BlockState[256];

    /** Island sample, reused across all 324 grid probes. */
    public final GridIslandSample islandSample = new GridIslandSample();
    /** Freshwater sample, reused across all 324 grid probes. */
    public final IslandLakeSample lakeSample = new IslandLakeSample();
}
