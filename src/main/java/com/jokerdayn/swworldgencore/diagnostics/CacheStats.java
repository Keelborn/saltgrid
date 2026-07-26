package com.jokerdayn.swworldgencore.diagnostics;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/** Hit rate, load factor and eviction cost of one {@link CacheId}. */
public final class CacheStats {

    final LongAdder hits = new LongAdder();
    final LongAdder misses = new LongAdder();
    final LongAdder trims = new LongAdder();
    final LongAdder removed = new LongAdder();
    final LongAdder trimNs = new LongAdder();
    final LongAdder timedTrims = new LongAdder();
    final AtomicLong maxTrimNs = new AtomicLong();
    final AtomicInteger size = new AtomicInteger();
    final AtomicInteger limit = new AtomicInteger();

    void reset() {
        hits.reset();
        misses.reset();
        trims.reset();
        removed.reset();
        trimNs.reset();
        timedTrims.reset();
        maxTrimNs.set(0L);
    }

    public long hitCount() {
        return hits.sum();
    }

    public long missCount() {
        return misses.sum();
    }

    public int currentSize() {
        return size.get();
    }

    public int sizeLimit() {
        return limit.get();
    }

    public long maxTrimNanos() {
        return maxTrimNs.get();
    }

    public long totalTrimNanos() {
        return trimNs.sum();
    }
}
