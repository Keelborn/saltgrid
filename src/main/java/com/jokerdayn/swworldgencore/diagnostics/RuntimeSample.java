package com.jokerdayn.swworldgencore.diagnostics;

import java.util.Map;

/**
 * Point-in-time JVM snapshot. Counters that the platform does not expose are
 * reported as {@code -1} and rendered as {@code n/a} rather than silently as zero.
 */
public record RuntimeSample(
    long wallMs,
    long heapUsed,
    long heapCommitted,
    long heapMax,
    long nonHeapUsed,
    long directUsed,
    long directCapacity,
    long mappedUsed,
    long gcCount,
    long gcTimeMs,
    long processCpuNs,
    double processCpuLoad,
    double systemCpuLoad,
    long physicalTotal,
    long physicalFree,
    long committedVirtual,
    int liveThreads,
    int daemonThreads,
    int peakThreads,
    int loadedClasses,
    long unloadedClasses,
    long compilationTimeMs,
    Map<String, CollectorSample> collectors
) {
    /** Cumulative activity of one garbage collector. */
    public record CollectorSample(long collections, long timeMs) {}
}
