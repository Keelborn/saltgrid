package com.jokerdayn.swworldgencore.diagnostics;

/** One retained over-budget call, with whatever breakdown the caller supplied. */
public record SlowSample(
    Stage stage,
    long chunkKey,
    long elapsedNs,
    long cpuNs,
    long allocatedBytes,
    String threadName,
    long capturedMs,
    String detail
) {}
