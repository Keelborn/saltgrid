package com.jokerdayn.swworldgencore.diagnostics;

/**
 * A measured sub-step of a {@link Stage}.
 *
 * <p>{@link #requiredPerParent()} marks phases that must be recorded exactly once per
 * parent call. Phases that only fire when a feature is actually attempted (the tree
 * passes) are exempt, otherwise the sample-count invariant in the automatic diagnosis
 * would report a false positive on every quiet chunk.</p>
 */
public enum Phase {
    FILL_SAMPLE_TERRAIN(Stage.FILL_NOISE, 8.0),
    FILL_PUBLISH_CACHE(Stage.FILL_NOISE, 0.5),
    FILL_CLASSIFY_SURFACE(Stage.FILL_NOISE, 4.0),
    FILL_PREPARE_SECTIONS(Stage.FILL_NOISE, 0.5),
    FILL_WRITE_SECTIONS(Stage.FILL_NOISE, 8.0),
    DECOR_READ_CACHE(Stage.DECORATION, 0.25),
    DECOR_VOLCANIC_BIOME(Stage.DECORATION, 2.0),
    DECOR_VOLCANIC_ACTIVE(Stage.DECORATION, 2.0),
    DECOR_BOULDERS(Stage.DECORATION, 3.0),
    DECOR_SAVANNA_TREES(Stage.DECORATION, 2.0),
    DECOR_SMALL_FEATURES(Stage.DECORATION, 3.0),
    TREE_PALM_PREFLIGHT(Stage.DECORATION, 1.0, false),
    TREE_PALM_WRITE(Stage.DECORATION, 8.0, false),
    TREE_ACACIA_PREFLIGHT(Stage.DECORATION, 1.0, false),
    TREE_ACACIA_WRITE(Stage.DECORATION, 8.0, false);

    private final Stage parent;
    private final long budgetNs;
    private final boolean requiredPerParent;

    Phase(Stage parent, double budgetMs) {
        this(parent, budgetMs, true);
    }

    Phase(Stage parent, double budgetMs, boolean requiredPerParent) {
        this.parent = parent;
        this.budgetNs = (long) (budgetMs * 1_000_000.0);
        this.requiredPerParent = requiredPerParent;
    }

    public Stage parent() {
        return parent;
    }

    public long budgetNs() {
        return budgetNs;
    }

    public boolean requiredPerParent() {
        return requiredPerParent;
    }
}
