package com.jokerdayn.swworldgencore;

import com.mojang.logging.LogUtils;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.LongAdder;
import org.slf4j.Logger;

/**
 * Bounded, lock-free-on-the-hot-path diagnostics for OceanChunkGenerator.
 * Counters are approximate while generation is concurrent. No terrain RNG is touched.
 */
public final class OceanGeneratorDiagnostics {
    private static final Logger LOG = LogUtils.getLogger();
    private static final long REPORT_INTERVAL_MS = 30_000L;
    private static final long WARNING_INTERVAL_MS = 10_000L;
    private static final long SLOW_STAGE_NS = 50_000_000L;
    private static final int HISTOGRAM_BUCKETS = 40;
    private static final int MAX_ACTIVE = 4096;
    private static final int MAX_THREADS = 128;
    private static final int MAX_SLOW_SAMPLES = 32;
    private static final int REPORTED_SLOW_SAMPLES = 12;

    public enum Stage { FILL_NOISE, DECORATION, BASE_HEIGHT, BASE_COLUMN }
    public enum Cache { FLOOR, BIOME, BEACH, DECOR }

    public record Token(Stage stage, long chunkKey, long seed, long startedNs,
                        String threadName, boolean trackedActive, long epoch) {}
    public record SlowSample(Stage stage, long chunkKey, long elapsedNs,
                             String threadName, long capturedMs) {}

    private static final class StageStats {
        final LongAdder calls = new LongAdder();
        final LongAdder errors = new LongAdder();
        final LongAdder totalNs = new LongAdder();
        final AtomicLong maxNs = new AtomicLong();
        final AtomicLongArray histogram = new AtomicLongArray(HISTOGRAM_BUCKETS);
    }

    private static final class CacheStats {
        final LongAdder hits = new LongAdder();
        final LongAdder misses = new LongAdder();
        final LongAdder trims = new LongAdder();
        final LongAdder removed = new LongAdder();
        final AtomicInteger size = new AtomicInteger();
        final AtomicInteger limit = new AtomicInteger();
    }

    private record RuntimeSample(long wallMs, long heapUsed, long heapCommitted,
                                 long nonHeapUsed, long gcCount, long gcTimeMs,
                                 long processCpuNs, int liveThreads, int peakThreads) {}

    private final StageStats[] stages = new StageStats[Stage.values().length];
    private final CacheStats[] caches = new CacheStats[Cache.values().length];
    private final ConcurrentHashMap<Long, Token> active = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, LongAdder> threadCalls = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<SlowSample> slowSamples = new ConcurrentLinkedQueue<>();
    private final AtomicInteger slowSampleSize = new AtomicInteger();
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicInteger peakInFlight = new AtomicInteger();
    private final LongAdder duplicateEntries = new LongAdder();
    private final LongAdder seedRaces = new LongAdder();
    private final LongAdder invalidEnds = new LongAdder();
    private final LongAdder droppedTracking = new LongAdder();
    private final LongAdder slowStages = new LongAdder();
    private final LongAdder seedResets = new LongAdder();
    private final AtomicLong epoch = new AtomicLong(1L);
    private final AtomicLong startedMs = new AtomicLong(System.currentTimeMillis());
    private final AtomicLong lastReportMs = new AtomicLong();
    private final AtomicLong lastWarningMs = new AtomicLong();
    private final AtomicBoolean verbose = new AtomicBoolean(false);
    private final MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
    private final ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
    private volatile RuntimeSample baseline = runtimeSample();

    public OceanGeneratorDiagnostics() {
        for (int i = 0; i < stages.length; i++) stages[i] = new StageStats();
        for (int i = 0; i < caches.length; i++) caches[i] = new CacheStats();
    }

    public Token begin(Stage stage, long chunkKey, long seed) {
        long now = System.nanoTime();
        String thread = Thread.currentThread().getName();
        String threadKey = threadCalls.containsKey(thread) || threadCalls.size() < MAX_THREADS
            ? thread : "<other>";
        threadCalls.computeIfAbsent(threadKey, ignored -> new LongAdder()).increment();
        int current = inFlight.incrementAndGet();
        peakInFlight.accumulateAndGet(current, Math::max);
        long currentEpoch = epoch.get();
        Token provisional = new Token(stage, chunkKey, seed, now, thread, false, currentEpoch);
        boolean tracked = false;
        if (chunkKey != Long.MIN_VALUE) {
            if (active.size() < MAX_ACTIVE) {
                Token previous = active.putIfAbsent(activeKey(stage, chunkKey), provisional);
                tracked = previous == null;
                if (!tracked) {
                    duplicateEntries.increment();
                    warnRateLimited("duplicate " + stage + " at " + chunk(chunkKey));
                }
            } else {
                droppedTracking.increment();
            }
        }
        Token token = new Token(stage, chunkKey, seed, now, thread, tracked, currentEpoch);
        if (tracked) active.replace(activeKey(stage, chunkKey), provisional, token);
        return token;
    }

    public void end(Token token, long currentSeed, Throwable error) {
        if (token == null) {
            invalidEnds.increment();
            return;
        }
        long elapsed = Math.max(0L, System.nanoTime() - token.startedNs());
        if (token.epoch() == epoch.get()) {
            StageStats stats = stages[token.stage().ordinal()];
            stats.calls.increment();
            stats.totalNs.add(elapsed);
            stats.histogram.incrementAndGet(bucket(elapsed));
            stats.maxNs.accumulateAndGet(elapsed, Math::max);
            if (error != null) stats.errors.increment();
            if (token.seed() != currentSeed) seedRaces.increment();
            if (elapsed >= SLOW_STAGE_NS) recordSlow(token, elapsed);
        }
        if (token.trackedActive() && !active.remove(activeKey(token.stage(), token.chunkKey()), token)) {
            invalidEnds.increment();
        }
        int remaining = inFlight.decrementAndGet();
        if (remaining < 0) {
            invalidEnds.increment();
            inFlight.compareAndSet(remaining, 0);
        }
        maybeReport(currentSeed);
    }

    public void cacheAccess(Cache cache, boolean hit) {
        if (hit) caches[cache.ordinal()].hits.increment();
        else caches[cache.ordinal()].misses.increment();
    }

    public void cacheState(Cache cache, int size, int limit) {
        CacheStats stats = caches[cache.ordinal()];
        stats.size.set(Math.max(0, size));
        stats.limit.set(Math.max(0, limit));
    }

    public void cacheTrim(Cache cache, int removed) {
        CacheStats stats = caches[cache.ordinal()];
        stats.trims.increment();
        stats.removed.add(Math.max(0, removed));
    }

    public void seedReset() { seedResets.increment(); }
    public boolean verbose() { return verbose.get(); }
    public void setVerbose(boolean enabled) { verbose.set(enabled); }

    /** Starts a new measurement epoch without corrupting tokens already in flight. */
    public void reset() {
        epoch.incrementAndGet();
        for (StageStats s : stages) {
            s.calls.reset(); s.errors.reset(); s.totalNs.reset(); s.maxNs.set(0L);
            for (int i = 0; i < HISTOGRAM_BUCKETS; i++) s.histogram.set(i, 0L);
        }
        for (CacheStats c : caches) {
            c.hits.reset(); c.misses.reset(); c.trims.reset(); c.removed.reset();
        }
        duplicateEntries.reset(); seedRaces.reset(); invalidEnds.reset();
        droppedTracking.reset(); slowStages.reset(); seedResets.reset();
        slowSamples.clear(); slowSampleSize.set(0); threadCalls.clear();
        peakInFlight.set(inFlight.get());
        startedMs.set(System.currentTimeMillis());
        baseline = runtimeSample();
    }

    public String compactStatus(long seed) {
        StageStats fill = stages[Stage.FILL_NOISE.ordinal()];
        long calls = fill.calls.sum();
        double seconds = elapsedSeconds();
        return "OceanGen seed=" + seed + " window=" + fmt(seconds) + "s chunks=" + calls
            + " rate=" + fmt(calls / seconds) + "/s avg=" + ms(fill.totalNs.sum(), calls)
            + "ms p95=" + ms(percentile(fill, .95)) + "ms p99=" + ms(percentile(fill, .99))
            + "ms inFlight=" + inFlight.get() + " peak=" + peakInFlight.get()
            + " errors=" + allErrors();
    }

    public String threadStatus() {
        StringBuilder out = new StringBuilder("OceanGen concurrency current=")
            .append(inFlight.get()).append(" peak=").append(peakInFlight.get())
            .append(" active=").append(active.size()).append(" trackedThreads=").append(threadCalls.size());
        threadCalls.entrySet().stream()
            .sorted((a, b) -> Long.compare(b.getValue().sum(), a.getValue().sum()))
            .limit(16).forEach(e -> out.append("\n  ").append(e.getKey()).append('=').append(e.getValue().sum()));
        return out.toString();
    }

    public String slowStatus() {
        List<SlowSample> copy = new ArrayList<>(slowSamples);
        copy.sort(Comparator.comparingLong(SlowSample::elapsedNs).reversed());
        StringBuilder out = new StringBuilder("OceanGen slow stages >= ")
            .append(ms(SLOW_STAGE_NS)).append("ms total=").append(slowStages.sum());
        copy.stream().limit(REPORTED_SLOW_SAMPLES).forEach(s -> out.append("\n  ")
            .append(s.stage()).append(' ').append(chunk(s.chunkKey())).append(' ')
            .append(ms(s.elapsedNs())).append("ms thread=").append(s.threadName()));
        if (copy.isEmpty()) out.append("\n  none");
        return out.toString();
    }

    public String fullReport(long seed) {
        RuntimeSample now = runtimeSample();
        RuntimeSample base = baseline;
        double seconds = elapsedSeconds();
        StringBuilder out = new StringBuilder(2048);
        out.append("\n========== OceanGen benchmark ==========")
            .append("\nwindowSec=").append(fmt(seconds)).append(" seed=").append(seed)
            .append(" epoch=").append(epoch.get()).append(" counters=approximate-under-concurrency")
            .append("\nheapUsedMiB=").append(mib(now.heapUsed())).append(" heapCommittedMiB=").append(mib(now.heapCommitted()))
            .append(" nonHeapMiB=").append(mib(now.nonHeapUsed()))
            .append(" gcCountDelta=").append(delta(now.gcCount(), base.gcCount()))
            .append(" gcTimeMsDelta=").append(delta(now.gcTimeMs(), base.gcTimeMs()))
            .append(" processCpuMsDelta=").append(delta(now.processCpuNs(), base.processCpuNs()) / 1_000_000L)
            .append(" liveThreads=").append(now.liveThreads()).append(" jvmPeakThreads=").append(now.peakThreads())
            .append("\nconcurrency current=").append(inFlight.get()).append(" peak=").append(peakInFlight.get())
            .append(" activeTracked=").append(active.size()).append(" workerNames=").append(threadCalls.size());
        for (Stage stage : Stage.values()) appendStage(out, stage, stages[stage.ordinal()], seconds);
        out.append("\nsafety errors=").append(allErrors()).append(" seedRaces=").append(seedRaces.sum())
            .append(" duplicates=").append(duplicateEntries.sum()).append(" invalidEnds=").append(invalidEnds.sum())
            .append(" trackingDropped=").append(droppedTracking.sum()).append(" seedResets=").append(seedResets.sum());
        for (Cache cache : Cache.values()) appendCache(out, cache, caches[cache.ordinal()]);
        out.append('\n').append(slowStatus()).append("\n========================================");
        return out.toString();
    }

    public String csv(long seed) {
        RuntimeSample now = runtimeSample();
        StringBuilder out = new StringBuilder("kind,name,seed,window_s,calls,errors,rate_s,avg_ms,p50_ms,p90_ms,p95_ms,p99_ms,p999_ms,max_ms\n");
        double seconds = elapsedSeconds();
        for (Stage stage : Stage.values()) {
            StageStats s = stages[stage.ordinal()]; long calls = s.calls.sum();
            out.append("stage,").append(stage).append(',').append(seed).append(',').append(fmt(seconds)).append(',')
                .append(calls).append(',').append(s.errors.sum()).append(',').append(fmt(calls / seconds)).append(',')
                .append(ms(s.totalNs.sum(), calls)).append(',').append(ms(percentile(s,.50))).append(',')
                .append(ms(percentile(s,.90))).append(',').append(ms(percentile(s,.95))).append(',')
                .append(ms(percentile(s,.99))).append(',').append(ms(percentile(s,.999))).append(',').append(ms(s.maxNs.get())).append('\n');
        }
        for (Cache cache : Cache.values()) {
            CacheStats c = caches[cache.ordinal()]; long h = c.hits.sum(), m = c.misses.sum();
            out.append("cache,").append(cache).append(',').append(seed).append(',').append(fmt(seconds))
                .append(",hits=").append(h).append(",misses=").append(m).append(",hit_rate=").append(percent(h, h + m))
                .append(",size=").append(c.size.get()).append(",limit=").append(c.limit.get())
                .append(",trims=").append(c.trims.sum()).append(",removed=").append(c.removed.sum()).append('\n');
        }
        out.append("runtime,JVM,").append(seed).append(',').append(fmt(seconds)).append(",heap_mib=").append(mib(now.heapUsed()))
            .append(",gc_count_delta=").append(delta(now.gcCount(), baseline.gcCount()))
            .append(",gc_ms_delta=").append(delta(now.gcTimeMs(), baseline.gcTimeMs()))
            .append(",cpu_ms_delta=").append(delta(now.processCpuNs(), baseline.processCpuNs()) / 1_000_000L);
        return out.toString();
    }

    public void forceReport(long seed) { LOG.info(fullReport(seed)); lastReportMs.set(System.currentTimeMillis()); }
    public void exportCsv(long seed) { LOG.info("\n{}", csv(seed)); }

    private void appendStage(StringBuilder out, Stage stage, StageStats s, double seconds) {
        long calls = s.calls.sum();
        out.append("\nstage ").append(stage).append(" calls=").append(calls).append(" rate=").append(fmt(calls / seconds)).append("/s")
            .append(" avg=").append(ms(s.totalNs.sum(), calls)).append("ms p50=").append(ms(percentile(s,.50)))
            .append("ms p90=").append(ms(percentile(s,.90))).append("ms p95=").append(ms(percentile(s,.95)))
            .append("ms p99=").append(ms(percentile(s,.99))).append("ms p99.9=").append(ms(percentile(s,.999)))
            .append("ms max=").append(ms(s.maxNs.get())).append("ms errors=").append(s.errors.sum());
    }

    private static void appendCache(StringBuilder out, Cache cache, CacheStats c) {
        long hits = c.hits.sum(), misses = c.misses.sum();
        out.append("\ncache ").append(cache).append(" hitRate=").append(percent(hits, hits + misses))
            .append(" hits=").append(hits).append(" misses=").append(misses).append(" size=").append(c.size.get())
            .append('/').append(c.limit.get()).append(" trims=").append(c.trims.sum()).append(" removed=").append(c.removed.sum());
    }

    private void recordSlow(Token token, long elapsed) {
        slowStages.increment();
        slowSamples.add(new SlowSample(token.stage(), token.chunkKey(), elapsed, token.threadName(), System.currentTimeMillis()));
        int size = slowSampleSize.incrementAndGet();
        while (size > MAX_SLOW_SAMPLES) {
            if (slowSamples.poll() != null) size = slowSampleSize.decrementAndGet(); else break;
        }
    }

    private void maybeReport(long seed) {
        long now = System.currentTimeMillis(), last = lastReportMs.get();
        if (now - last >= REPORT_INTERVAL_MS && lastReportMs.compareAndSet(last, now))
            LOG.info(verbose.get() ? fullReport(seed) : compactStatus(seed));
    }

    private void warnRateLimited(String message) {
        if (!verbose.get()) return;
        long now = System.currentTimeMillis(), last = lastWarningMs.get();
        if (now - last >= WARNING_INTERVAL_MS && lastWarningMs.compareAndSet(last, now)) LOG.warn("[OceanGen] {}", message);
    }

    private RuntimeSample runtimeSample() {
        MemoryUsage heap = memoryBean.getHeapMemoryUsage(), nonHeap = memoryBean.getNonHeapMemoryUsage();
        long gcCount = 0L, gcTime = 0L;
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            if (gc.getCollectionCount() >= 0) gcCount += gc.getCollectionCount();
            if (gc.getCollectionTime() >= 0) gcTime += gc.getCollectionTime();
        }
        long cpu = -1L;
        if (ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean os) cpu = os.getProcessCpuTime();
        return new RuntimeSample(System.currentTimeMillis(), heap.getUsed(), heap.getCommitted(), nonHeap.getUsed(),
            gcCount, gcTime, cpu, threadBean.getThreadCount(), threadBean.getPeakThreadCount());
    }

    private double elapsedSeconds() { return Math.max(.001, (System.currentTimeMillis() - startedMs.get()) / 1000.0); }
    private long allErrors() { long n = 0L; for (StageStats s : stages) n += s.errors.sum(); return n; }
    private static long activeKey(Stage stage, long key) { return key ^ (0x9E3779B97F4A7C15L * (stage.ordinal() + 1L)); }
    private static int bucket(long ns) { long us = Math.max(1L, ns / 1_000L); return Math.min(HISTOGRAM_BUCKETS - 1, 63 - Long.numberOfLeadingZeros(us)); }
    private static long percentile(StageStats s, double p) {
        long count = s.calls.sum(); if (count == 0) return 0L;
        long target = Math.max(1L, (long)Math.ceil(count * p)), seen = 0L;
        for (int i = 0; i < HISTOGRAM_BUCKETS; i++) { seen += s.histogram.get(i); if (seen >= target) return (1L << i) * 1_000L; }
        return s.maxNs.get();
    }
    private static String chunk(long key) { return key == Long.MIN_VALUE ? "n/a" : "[" + (int)key + "," + (int)(key >>> 32) + "]"; }
    private static String ms(long ns) { return fmt(ns / 1_000_000.0); }
    private static String ms(long ns, long calls) { return ms(calls == 0 ? 0L : ns / calls); }
    private static String fmt(double value) { return String.format(Locale.ROOT, "%.3f", value); }
    private static String percent(long part, long total) { return total == 0 ? "n/a" : fmt(part * 100.0 / total) + '%'; }
    private static long mib(long bytes) { return bytes < 0 ? -1 : bytes >> 20; }
    private static long delta(long value, long base) { return value < 0 || base < 0 ? -1L : Math.max(0L, value - base); }
}
