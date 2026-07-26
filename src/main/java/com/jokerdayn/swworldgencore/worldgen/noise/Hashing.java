package com.jokerdayn.swworldgencore.worldgen.noise;

/** Seed-independent bit mixing shared by the terrain and decoration passes. */
public final class Hashing {

    private Hashing() {}

    /** Packs a column into a single cache key. */
    public static long columnKey(int x, int z) {
        return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
    }

    /**
     * Fractional part of a hash in {@code [0,1)}.
     *
     * <p><b>Only the low 24 bits are consumed.</b> Callers that pre-shift a hash
     * must therefore keep {@code shift <= 40}, otherwise sign extension from an
     * arithmetic {@code >>} fills the upper part of the 24-bit window and the
     * result collapses to a bimodal "almost 0 or almost 1" distribution. See
     * {@link #fracLogical(long, int)} for the unbiased variant.</p>
     */
    public static double frac(long h) {
        return (h & 0xFFFFFFL) / (double) 0x1000000;
    }

    /**
     * Unbiased {@link #frac} of a shifted hash — uses a logical shift, so every
     * shift in {@code 0..63} yields 24 real bits where they exist.
     *
     * <p>Not interchangeable with {@code frac(h >> shift)}: switching a call site
     * over changes generated terrain for a given seed.</p>
     */
    public static double fracLogical(long h, int shift) {
        return frac(h >>> shift);
    }

    /** SplitMix64 finaliser. */
    public static long mix64(long value) {
        value = (value ^ (value >>> 30)) * 0xBF58476D1CE4E5B9L;
        value = (value ^ (value >>> 27)) * 0x94D049BB133111EBL;
        return value ^ (value >>> 31);
    }
}
