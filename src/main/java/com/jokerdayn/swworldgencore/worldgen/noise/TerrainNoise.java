package com.jokerdayn.swworldgencore.worldgen.noise;

import net.minecraft.util.Mth;

/**
 * Immutable, seeded value/gradient noise used by every terrain field.
 *
 * <p>Instances are cheap to build and never mutated, so a seed change is
 * published by swapping in a whole new instance. That removes the previous
 * two-field ({@code seed} + {@code seedOffsets}) publication hazard: a reader can
 * never observe offsets from one seed together with the hash seed of another.</p>
 */
public final class TerrainNoise {

    /** Unit gradients for {@link #pnoise}, indexed by 3 hash bits. */
    private static final int[][] GRAD2 = {
        { 1, 0 },
        { -1, 0 },
        { 0, 1 },
        { 0, -1 },
        { 1, 1 },
        { -1, 1 },
        { 1, -1 },
        { -1, -1 },
    };

    private static final int SEED_OFFSET_COUNT = 256;

    private final long seed;
    private final double[] seedOffsets;

    public TerrainNoise(long seed) {
        this.seed = seed;
        this.seedOffsets = createSeedOffsets(seed);
    }

    public long seed() {
        return seed;
    }

    // -------------------------------------------------------------------------
    // Hashing
    // -------------------------------------------------------------------------

    /** Seeded 64-bit hash of a column. */
    public long rawHash(int x, int z) {
        long n = ((long) x * 73856093L) ^ ((long) z * 19349663L) ^ seed;
        n = (n ^ (n >> 13)) * 1274126177L;
        return n ^ (n >> 16);
    }

    /** Seeded hash of a column mapped to {@code [0,1]}. */
    public double hsh(int x, int z) {
        return (double) (rawHash(x, z) & 0x7FFFFFFFL) / (double) 0x7FFFFFFFL;
    }

    /** Seeded hash of a block position — used for ore scatter inside boulders. */
    public long hash3(int x, int y, int z) {
        return rawHash(x * 31 + y * 17, z * 13 - y * 7);
    }

    /**
     * Deterministic per-seed noise-domain offset in {@code [-scale, scale]}.
     * Decorrelates otherwise identical noise layers between seeds.
     */
    public double seedOff(int salt, double scale) {
        return seedOffsets[salt & (SEED_OFFSET_COUNT - 1)] * scale;
    }

    private int gradHash(int x, int z) {
        return (int) rawHash(x, z) & 7;
    }

    private static double[] createSeedOffsets(long sourceSeed) {
        double[] offsets = new double[SEED_OFFSET_COUNT];
        for (int i = 0; i < offsets.length; i++) {
            long n = ((long) i * 73856093L) ^ sourceSeed;
            n = (n ^ (n >> 13)) * 1274126177L;
            long h = n ^ (n >> 16);
            offsets[i] = ((h & 0xFFFF) / (double) 0xFFFF) * 2.0 - 1.0;
        }
        return offsets;
    }

    // -------------------------------------------------------------------------
    // Noise
    // -------------------------------------------------------------------------

    private static double dot2(int[] gradient, double x, double z) {
        return gradient[0] * x + gradient[1] * z;
    }

    /** Perlin-style gradient noise remapped to {@code [0,1]}. */
    public double pnoise(double x, double z) {
        int xi = (int) Math.floor(x);
        int zi = (int) Math.floor(z);
        double xf = x - xi;
        double zf = z - zi;
        double u = xf * xf * xf * (xf * (xf * 6 - 15) + 10);
        double v = zf * zf * zf * (zf * (zf * 6 - 15) + 10);
        int g00 = gradHash(xi, zi);
        int g10 = gradHash(xi + 1, zi);
        int g01 = gradHash(xi, zi + 1);
        int g11 = gradHash(xi + 1, zi + 1);
        double n00 = dot2(GRAD2[g00], xf, zf);
        double n10 = dot2(GRAD2[g10], xf - 1, zf);
        double n01 = dot2(GRAD2[g01], xf, zf - 1);
        double n11 = dot2(GRAD2[g11], xf - 1, zf - 1);
        double x0 = n00 + u * (n10 - n00);
        double x1 = n01 + u * (n11 - n01);
        return Mth.clamp((x0 + v * (x1 - x0)) * 0.7071 + 0.5, 0.0, 1.0);
    }

    /** Fractal Brownian motion over {@link #pnoise}, normalised to {@code [0,1]}. */
    public double fbm(double x, double z, int octaves, double lacunarity, double gain) {
        double value = 0.0;
        double amplitude = 1.0;
        double frequency = 1.0;
        double max = 0.0;
        for (int i = 0; i < octaves; i++) {
            value += amplitude * pnoise(x * frequency, z * frequency);
            max += amplitude;
            amplitude *= gain;
            frequency *= lacunarity;
        }
        return value / max;
    }

    /** Ridged multifractal noise — sharp crests for mountain spines. */
    public double ridgeNoise(double x, double z, int octaves, double lacunarity, double gain) {
        double value = 0.0;
        double amplitude = 1.0;
        double frequency = 1.0;
        double max = 0.0;
        double previous = 1.0;
        for (int i = 0; i < octaves; i++) {
            double n = 1.0 - Math.abs(pnoise(x * frequency, z * frequency) * 2 - 1);
            n = Mth.clamp(n * n * previous, 0.0, 1.0);
            previous = n;
            value += amplitude * n;
            max += amplitude;
            amplitude *= gain;
            frequency *= lacunarity;
        }
        return value / max;
    }

    /** Cubic smoothstep on an already-normalised value. */
    public static double smoothstep(double t) {
        return t * t * (3.0 - 2.0 * t);
    }

    /** {@link #smoothstep} of a value clamped into {@code [0,1]} first. */
    public static double smoothstepClamped(double t) {
        double clamped = Mth.clamp(t, 0.0, 1.0);
        return clamped * clamped * (3.0 - 2.0 * clamped);
    }
}
