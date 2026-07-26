package com.jokerdayn.swworldgencore.diagnostics;

/**
 * Handle returned by {@link GeneratorDiagnostics#begin} and handed back to
 * {@link GeneratorDiagnostics#end}.
 *
 * @param activeKey     identity in the in-flight map, or {@code null} when this stage is
 *                      not chunk-scoped
 * @param trackedActive whether this token actually owns its slot in the in-flight map;
 *                      a duplicate entry or an overflow leaves it {@code false} so
 *                      {@code end} does not remove someone else's entry
 * @param epoch         the measurement window this token belongs to; samples from an
 *                      older window are discarded instead of corrupting a fresh one
 */
public record Token(
    Stage stage,
    long chunkKey,
    long seed,
    long startedNs,
    long startedCpuNs,
    long startedAllocatedBytes,
    String threadName,
    String threadKey,
    ActiveKey activeKey,
    boolean trackedActive,
    long epoch
) {
    /** Identity of one in-flight chunk-scoped operation. */
    public record ActiveKey(Stage stage, long chunkKey) {}
}
