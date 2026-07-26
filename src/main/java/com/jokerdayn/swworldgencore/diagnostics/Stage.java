package com.jokerdayn.swworldgencore.diagnostics;

/**
 * A top-level generator entry point that is timed as a whole.
 *
 * <p>{@link #detailed()} gates the per-call {@code ThreadMXBean} probes. Those probes
 * cost roughly a microsecond each and are only affordable on the coarse, once-per-chunk
 * stages — {@link #BASE_HEIGHT} and {@link #BASE_COLUMN} are called orders of magnitude
 * more often (structure placement, spawn searches, heightmap queries), so measuring CPU
 * time and allocation there would cost more than the work being measured.</p>
 */
public enum Stage {
    FILL_NOISE(16.0, true),
    DECORATION(8.0, true),
    BASE_HEIGHT(2.0, false),
    BASE_COLUMN(4.0, false);

    private final long budgetNs;
    private final boolean detailed;

    Stage(double budgetMs, boolean detailed) {
        this.budgetNs = (long) (budgetMs * 1_000_000.0);
        this.detailed = detailed;
    }

    public long budgetNs() {
        return budgetNs;
    }

    /** Whether CPU-time and allocation deltas are sampled for this stage. */
    public boolean detailed() {
        return detailed;
    }
}
