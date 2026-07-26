package com.jokerdayn.swworldgencore.diagnostics;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/** Per-thread share of the generation work, used to spot an unbalanced worker pool. */
public final class ThreadStats {

    final LongAdder calls = new LongAdder();
    final LongAdder errors = new LongAdder();
    final LongAdder wallNs = new LongAdder();
    final LongAdder cpuNs = new LongAdder();
    final LongAdder allocatedBytes = new LongAdder();
    final AtomicLong maxNs = new AtomicLong();

    public long wallNanos() {
        return wallNs.sum();
    }
}
