package com.jokerdayn.swworldgencore.worldgen.decor;

import com.jokerdayn.swworldgencore.diagnostics.Counter;
import com.jokerdayn.swworldgencore.diagnostics.GeneratorDiagnostics;
import com.jokerdayn.swworldgencore.diagnostics.Phase;
import com.jokerdayn.swworldgencore.diagnostics.Token;
import com.jokerdayn.swworldgencore.worldgen.AcaciaGenerator;
import com.jokerdayn.swworldgencore.worldgen.PalmGenerator;

/**
 * Folds a tree generator's placement result into the diagnostics.
 *
 * <p>Both tree generators split their work into a collision preflight and a write pass and
 * report them separately, so that a low placement rate (lots of preflight, no writes) is
 * distinguishable from expensive writes.</p>
 */
final class TreePlacements {

    private TreePlacements() {}

    static void recordPalm(
        GeneratorDiagnostics diagnostics,
        Token benchmark,
        PalmGenerator.PlacementResult result
    ) {
        diagnostics.phase(benchmark, Phase.TREE_PALM_PREFLIGHT, result.preflightNs());
        if (result.writeNs() > 0L) {
            diagnostics.phase(benchmark, Phase.TREE_PALM_WRITE, result.writeNs());
        }
        diagnostics.add(benchmark, Counter.PALM_BLOCK_WRITES, result.blocksWritten());
        if (!result.placed()) {
            diagnostics.add(benchmark, Counter.TREE_PREFLIGHT_FAILURES, 1L);
        }
    }

    static void recordAcacia(
        GeneratorDiagnostics diagnostics,
        Token benchmark,
        AcaciaGenerator.PlacementResult result
    ) {
        diagnostics.phase(benchmark, Phase.TREE_ACACIA_PREFLIGHT, result.preflightNs());
        if (result.writeNs() > 0L) {
            diagnostics.phase(benchmark, Phase.TREE_ACACIA_WRITE, result.writeNs());
        }
        diagnostics.add(benchmark, Counter.ACACIA_BLOCK_WRITES, result.blocksWritten());
        if (!result.placed()) {
            diagnostics.add(benchmark, Counter.TREE_PREFLIGHT_FAILURES, 1L);
        }
    }
}
