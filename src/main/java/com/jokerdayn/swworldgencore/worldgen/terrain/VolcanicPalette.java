package com.jokerdayn.swworldgencore.worldgen.terrain;

import com.jokerdayn.swworldgencore.worldgen.noise.TerrainNoise;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Materials of a volcanic island.
 *
 * <p>A single Hawaiian palette with altitude zonation, as on a real volcanic island:
 * black beach -> green living foot -> ash fields of tuff -> dark basalt cone -> glowing
 * caldera. Frozen black lava flows cut through every band down to the ocean.</p>
 */
public final class VolcanicPalette {

    private final TerrainNoise noise;
    private final int seaLevel;

    public VolcanicPalette(TerrainNoise noise, int seaLevel) {
        this.noise = noise;
        this.seaLevel = seaLevel;
    }

    /**
     * Ridged noise that opens magma fissures in exposed cliff faces.
     * Constant per column, so the caller may hoist it out of its y loop.
     */
    public double fissureAt(int x, int z) {
        return noise.ridgeNoise(x * 0.055 + 811.0, z * 0.055 - 419.0, 2, 2.0, 0.5);
    }

    /** Per-chunk-region offset of the ash strata, so layers do not line up globally. */
    public int strataShiftAt(int x, int z) {
        return (int) (noise.hsh(x >> 4, z >> 4) * 5);
    }

    /** Top block of a volcanic column. */
    public BlockState surface(
        int x,
        int z,
        int floor,
        double islandT,
        boolean crater,
        int lavaLevel,
        double centerX,
        double centerZ
    ) {
        double broad = noise.fbm(x * 0.012 + 503.0, z * 0.012 - 277.0, 3, 2.0, 0.52);
        double grain = noise.hsh(x * 43 + floor * 7, z * 47 - floor * 11);
        long volcanoHash = VolcanoGeometry.volcanoHash(noise, centerX, centerZ);
        double flowDistance = VolcanoGeometry.flowDistance(
            x, z, islandT, centerX, centerZ, volcanoHash, null
        );

        double flowWidth = 0.040 + Mth.clamp((islandT - 0.20) / 0.70, 0.0, 1.0) * 0.070;
        double flowEdge = flowDistance / flowWidth;
        boolean frozenFlow = islandT > 0.21 && islandT < 0.97 && flowEdge < 1.0;

        // Black volcanic beach: dark gravel "sand" with basalt outcrops, as on the coasts
        // of Iceland and Hawaii.
        double beachEdge = 0.91 + (broad - 0.5) * 0.045;
        if (islandT > beachEdge || floor <= seaLevel + 2) {
            if (grain < 0.66) return TerrainBlocks.GRAVEL;
            return grain < 0.86 ? TerrainBlocks.BASALT : TerrainBlocks.BLACKSTONE;
        }

        if (crater && floor <= lavaLevel + 1 && grain < 0.16) return TerrainBlocks.MAGMA_BLOCK;

        if (frozenFlow) {
            // Blocks inside a tongue are finely mixed, but its edges stay legible.
            double flowMix = grain + (broad - 0.5) * 0.24;
            if (flowEdge < 0.34 && flowMix < 0.58) return TerrainBlocks.SMOOTH_BASALT;
            return flowMix < 0.52 ? TerrainBlocks.BASALT : TerrainBlocks.BLACKSTONE;
        }

        // Green belt around the foot: the dense vegetated hem the flows read as scars
        // against. That contrast is what makes the island feel alive, like the young
        // slopes of Kilauea.
        double greenEdge = 0.665 + (broad - 0.5) * 0.06;
        if (islandT > greenEdge) {
            double soil = grain + (broad - 0.5) * 0.20;
            if (soil < 0.80) return TerrainBlocks.GRASS_BLOCK;
            return soil < 0.92 ? TerrainBlocks.COARSE_DIRT : TerrainBlocks.BLACKSTONE;
        }

        // Mid-flank ash fields: tuff (compacted volcanic ash) speckled with cinder gravel
        // and basalt outcrops.
        double ashEdge = 0.36 + (broad - 0.5) * 0.05;
        if (islandT > ashEdge) {
            double ashBlend = Mth.clamp(
                (islandT - ashEdge) / Math.max(0.01, greenEdge - ashEdge),
                0.0,
                1.0
            );
            double ashMix = grain + (broad - 0.5) * 0.26;
            // Closer to the green belt means more ash and cinder; higher up, bare dark
            // rock breaks through more often.
            if (ashMix < 0.30 + ashBlend * 0.28) return TerrainBlocks.TUFF;
            if (ashMix < 0.46 + ashBlend * 0.28) return TerrainBlocks.GRAVEL;
            return ashMix < 0.78 ? TerrainBlocks.BASALT : TerrainBlocks.BLACKSTONE;
        }

        // Upper cone: dark sintered rock. Blackstone and basalt mix at block scale, with
        // more smooth basalt right at the caldera — freshly cooled effusions.
        double slopeMix = grain + (broad - 0.5) * 0.30;
        double summitness = Mth.clamp(1.0 - islandT / Math.max(0.01, ashEdge), 0.0, 1.0);
        if (slopeMix < 0.14 + summitness * 0.18) return TerrainBlocks.SMOOTH_BASALT;
        return slopeMix < 0.56 + summitness * 0.10
            ? TerrainBlocks.BLACKSTONE
            : TerrainBlocks.BASALT;
    }

    /**
     * Layered rock under the cone. Each skin block picks its material from its own y, so
     * cliff walls look like a real stratovolcano instead of a vertical copy of the
     * surface.
     */
    public BlockState subsurface(
        int x,
        int y,
        int z,
        double islandT,
        boolean crater,
        int lavaLevel,
        double fissure,
        int strataShift
    ) {
        double rock = noise.hsh(x * 43 + y, z * 47 - y);
        boolean ashStrata = Math.floorMod(y + strataShift, 6) < 2;
        if (crater && y <= lavaLevel + 1 && rock < 0.18) return TerrainBlocks.MAGMA_BLOCK;
        if (crater && y <= lavaLevel && rock < 0.30) return TerrainBlocks.OBSIDIAN;
        if (fissure > 0.91 && rock < 0.22) return TerrainBlocks.MAGMA_BLOCK;
        if (rock < 0.16) return TerrainBlocks.BLACKSTONE;
        if (ashStrata && rock < 0.52) return TerrainBlocks.TUFF;
        if (rock < 0.34) return TerrainBlocks.SMOOTH_BASALT;
        return islandT > 0.58 && rock < 0.62
            ? TerrainBlocks.BLACKSTONE
            : TerrainBlocks.BASALT;
    }

    /**
     * Subsurface on the outer green shelf: a thin fertile layer of young soil grown over
     * the frozen lava underneath.
     */
    public BlockState shelfSubsurface(int x, int z) {
        return noise.hsh(x * 181, z * 191) < 0.72
            ? TerrainBlocks.DIRT
            : TerrainBlocks.BASALT;
    }
}
