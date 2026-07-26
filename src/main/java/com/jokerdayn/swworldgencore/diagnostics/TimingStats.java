package com.jokerdayn.swworldgencore.diagnostics;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.DoubleAdder;
import java.util.concurrent.atomic.LongAdder;

/**
 * Lock-free timing accumulator for one {@link Stage} or {@link Phase}.
 *
 * <p>Percentiles come from a fixed logarithmic histogram rather than a reservoir, so
 * recording is O(1) with no allocation and no sampling bias, at the cost of bucket-width
 * resolution (~18% per bucket).</p>
 */
public final class TimingStats {

    public static final int HISTOGRAM_BUCKETS = 128;

    /** Inclusive upper bound of each bucket, in nanoseconds. */
    private static final long[] UPPER_NS = histogramBounds();

    final LongAdder calls = new LongAdder();
    final LongAdder errors = new LongAdder();
    final LongAdder overBudget = new LongAdder();
    final LongAdder totalNs = new LongAdder();
    final LongAdder totalCpuNs = new LongAdder();
    final LongAdder totalAllocatedBytes = new LongAdder();
    final LongAdder cpuSamples = new LongAdder();
    final LongAdder allocationSamples = new LongAdder();
    final DoubleAdder totalMsSquared = new DoubleAdder();
    final AtomicLong minNs = new AtomicLong(Long.MAX_VALUE);
    final AtomicLong maxNs = new AtomicLong();
    final AtomicLong maxCpuNs = new AtomicLong();
    final AtomicLong maxAllocatedBytes = new AtomicLong();
    final AtomicLongArray histogram = new AtomicLongArray(HISTOGRAM_BUCKETS);

    void record(long elapsedNs, long cpuNs, long allocatedBytes, long budgetNs, boolean error) {
        calls.increment();
        totalNs.add(elapsedNs);
        double elapsedMs = elapsedNs / 1_000_000.0;
        totalMsSquared.add(elapsedMs * elapsedMs);
        histogram.incrementAndGet(bucket(elapsedNs));
        minNs.accumulateAndGet(elapsedNs, Math::min);
        maxNs.accumulateAndGet(elapsedNs, Math::max);
        if (cpuNs >= 0L) {
            cpuSamples.increment();
            totalCpuNs.add(cpuNs);
            maxCpuNs.accumulateAndGet(cpuNs, Math::max);
        }
        if (allocatedBytes >= 0L) {
            allocationSamples.increment();
            totalAllocatedBytes.add(allocatedBytes);
            maxAllocatedBytes.accumulateAndGet(allocatedBytes, Math::max);
        }
        if (elapsedNs > budgetNs) overBudget.increment();
        if (error) errors.increment();
    }

    void reset() {
        calls.reset();
        errors.reset();
        overBudget.reset();
        totalNs.reset();
        totalCpuNs.reset();
        totalAllocatedBytes.reset();
        cpuSamples.reset();
        allocationSamples.reset();
        totalMsSquared.reset();
        minNs.set(Long.MAX_VALUE);
        maxNs.set(0L);
        maxCpuNs.set(0L);
        maxAllocatedBytes.set(0L);
        for (int i = 0; i < HISTOGRAM_BUCKETS; i++) histogram.set(i, 0L);
    }

    // -------------------------------------------------------------------------
    // Derived views
    // -------------------------------------------------------------------------

    public long callCount() {
        return calls.sum();
    }

    public long totalElapsedNs() {
        return totalNs.sum();
    }

    public long errorCount() {
        return errors.sum();
    }

    /** Smallest observed call, or {@code 0} when nothing has been recorded. */
    public long observedMinNs() {
        long value = minNs.get();
        return value == Long.MAX_VALUE ? 0L : value;
    }

    /** Histogram-derived percentile, capped at the largest actually observed call. */
    public long percentile(double quantile) {
        long count = calls.sum();
        if (count == 0L) return 0L;
        long target = Math.max(1L, (long) Math.ceil(count * quantile));
        long seen = 0L;
        for (int i = 0; i < HISTOGRAM_BUCKETS; i++) {
            seen += histogram.get(i);
            if (seen >= target) return Math.min(UPPER_NS[i], maxNs.get());
        }
        return maxNs.get();
    }

    public double stddevMs() {
        long count = calls.sum();
        if (count <= 1L) return 0.0;
        double averageMs = totalNs.sum() / count / 1_000_000.0;
        return Math.sqrt(Math.max(0.0, totalMsSquared.sum() / count - averageMs * averageMs));
    }

    public long bucketCount(int index) {
        return histogram.get(index);
    }

    public static long bucketUpperNs(int index) {
        return UPPER_NS[index];
    }

    // -------------------------------------------------------------------------
    // Histogram layout
    // -------------------------------------------------------------------------

    /** Geometric buckets from 1us up to the last, open-ended one. */
    private static long[] histogramBounds() {
        long[] bounds = new long[HISTOGRAM_BUCKETS];
        double value = 1_000.0;
        for (int i = 0; i < bounds.length; i++) {
            bounds[i] = Math.max(i == 0 ? 1L : bounds[i - 1] + 1L, (long) Math.ceil(value));
            value *= 1.18;
        }
        bounds[bounds.length - 1] = Long.MAX_VALUE;
        return bounds;
    }

    private static int bucket(long ns) {
        int low = 0;
        int high = UPPER_NS.length - 1;
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (ns <= UPPER_NS[middle]) high = middle;
            else low = middle + 1;
        }
        return low;
    }
}
