package com.jokerdayn.swworldgencore.worldgen.decor;

import static com.jokerdayn.swworldgencore.worldgen.decor.DecorSettings.BOULDER_MAX_COUNT;

/**
 * The immutable boulder arrangement of one island, shared by all of its chunks.
 *
 * <p>Boulders are larger than a chunk, so each chunk draws only the slice of each boulder
 * that falls inside it. Every chunk therefore has to agree on the exact same list, which is
 * why the layout is computed once per island and cached rather than derived per chunk.</p>
 */
public final class BoulderLayout {

    final int[] x = new int[BOULDER_MAX_COUNT];
    final int[] z = new int[BOULDER_MAX_COUNT];
    final double[] radius = new double[BOULDER_MAX_COUNT];
    final long[] hash = new long[BOULDER_MAX_COUNT];
    int count;

    public int count() {
        return count;
    }

    public int x(int index) {
        return x[index];
    }

    public int z(int index) {
        return z[index];
    }

    public double radius(int index) {
        return radius[index];
    }

    public long hash(int index) {
        return hash[index];
    }

    void add(int blockX, int blockZ, double blockRadius, long boulderHash) {
        int index = count++;
        x[index] = blockX;
        z[index] = blockZ;
        radius[index] = blockRadius;
        hash[index] = boulderHash;
    }

    /** Whether a candidate at this position would overlap an already accepted boulder. */
    boolean overlaps(int candidateX, int candidateZ, double candidateRadius, double separation) {
        for (int i = 0; i < count; i++) {
            double dx = candidateX - x[i];
            double dz = candidateZ - z[i];
            double minDistance = (candidateRadius + radius[i]) * separation;
            if (dx * dx + dz * dz < minDistance * minDistance) return true;
        }
        return false;
    }
}
