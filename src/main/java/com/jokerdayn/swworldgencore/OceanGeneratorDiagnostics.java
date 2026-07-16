package com.jokerdayn.swworldgencore;

import com.mojang.logging.LogUtils;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.LongAdder;
import org.slf4j.Logger;

/**
 * Lightweight, bounded diagnostics for OceanChunkGenerator.
 * Hot-path recording is lock-free and never affects terrain randomness.
 */
public final class OceanGeneratorDiagnostics {
    private static final Logger LOG = LogUtils.getLogger();
    private static final long REPORT_INTERVAL_MS = 30_000L;
    private static final long WARNING_INTERVAL_MS = 10_000L;
    private static final long SLOW_CHUNK_NS = 100_000_000L;
    private static final int HISTOGRAM_BUCKETS = 32;
    private static final int MAX_ACTIVE = 4096;
    private static final int MAX_THREADS = 128;

    public enum Stage {
        FILL_NOISE, DECORATION, BASE_HEIGHT, BASE_COLUMN
    }

    public enum Cache {
        FLOOR, BIOME, BEACH, DECOR
    }

    public record Token(
        Stage stage,
        long chunkKey,
        long seed,
        long startedNs,
        String threadName,
        boolean trackedActive
    ) {}

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

    private final StageStats[] stages = new StageStats[Stage.values().length];
    private final CacheStats[] caches = new CacheStats[Cache.values().length];
    private final ConcurrentHashMap<Long, Token> activeChunks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, LongAdder> threadCalls = new ConcurrentHashMap<>();
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicInteger peakInFlight = new AtomicInteger();
    private final LongAdder duplicateEntries = new LongAdder();
    private final LongAdder seedRaces = new LongAdder();
    private final LongAdder invalidEnds = new LongAdder();
    private final LongAdder droppedTracking = new LongAdder();
    private final LongAdder slowChunks = new LongAdder();
    private final LongAdder seedResets = new LongAdder();
    private final AtomicLong worstNs = new AtomicLong();
    private final AtomicLong worstChunk = new AtomicLong();
    private final AtomicLong startedMs = new AtomicLong(System.currentTimeMillis());
    private final AtomicLong lastReportMs = new AtomicLong();
    private final AtomicLong lastWarningMs = new AtomicLong();
    private final AtomicBoolean verbose = new AtomicBoolean(true);

    public OceanGeneratorDiagnostics() {
        for (int i = 0; i < stages.length; i++) stages[i] = new StageStats();
        for (int i = 0; i < caches.length; i++) caches[i] = new CacheStats();
    }

    public Token begin(Stage stage, long chunkKey, long seed) {
        long now = System.nanoTime();
        String thread = Thread.currentThread().getName();
        threadCalls.computeIfAbsent(
            threadCalls.size() < MAX_THREADS ? thread : "<other>",
            ignored -> new LongAdder()
        ).increment();

        int current = inFlight.incrementAndGet();
        peakInFlight.accumulateAndGet(current, Math::max);
        boolean tracked = false;
        Token token = new Token(stage, chunkKey, seed, now, thread, false);
        if (chunkKey != Long.MIN_VALUE) {
            if (activeChunks.size() < MAX_ACTIVE) {
                Token previous = activeChunks.putIfAbsent(activeKey(stage, chunkKey), token);
                tracked = previous == null;
                if (!tracked) {
                    duplicateEntries.increment();
                    warnRateLimited("Concurrent duplicate stage=" + stage + " chunk=" + chunk(chunkKey) +
                        " firstThread=" + previous.threadName() + " secondThread=" + thread);
                }
            } else {
                droppedTracking.increment();
            }
        }
        return new Token(stage, chunkKey, seed, now, thread, tracked);
    }

    public void end(Token token, long currentSeed, Throwable error) {
        long elapsed = Math.max(0L, System.nanoTime() - token.startedNs());
        StageStats stats = stages[token.stage().ordinal()];
        stats.calls.increment();
        stats.totalNs.add(elapsed);
        stats.histogram.incrementAndGet(bucket(elapsed));
        stats.maxNs.accumulateAndGet(elapsed, Math::max);
        if (error != null) stats.errors.increment();
        if (token.seed() != currentSeed) {
            seedRaces.increment();
            warnRateLimited("Seed changed during " + token.stage() + ": " + token.seed() + " -> " + currentSeed);
        }
        if (token.trackedActive()) {
            if (!activeChunks.remove(activeKey(token.stage(), token.chunkKey()), token)) invalidEnds.increment();
        }
        int remaining = inFlight.decrementAndGet();
        if (remaining < 0) {
            invalidEnds.increment();
            inFlight.compareAndSet(remaining, 0);
        }
        if (elapsed >= SLOW_CHUNK_NS && token.stage() == Stage.FILL_NOISE) {
            slowChunks.increment();
            long old = worstNs.get();
            while (elapsed > old && !worstNs.compareAndSet(old, elapsed)) old = worstNs.get();
            if (elapsed >= worstNs.get()) worstChunk.set(token.chunkKey());
        }
        maybeReport(currentSeed);
    }

    public void cacheAccess(Cache cache, boolean hit) {
        if (hit) caches[cache.ordinal()].hits.increment();
        else caches[cache.ordinal()].misses.increment();
    }

    public void cacheState(Cache cache, int size, int limit) {
        CacheStats stats = caches[cache.ordinal()];
        stats.size.set(size);
        stats.limit.set(limit);
    }

    public void cacheTrim(Cache cache, int removed) {
        CacheStats stats = caches[cache.ordinal()];
        stats.trims.increment();
        stats.removed.add(Math.max(0, removed));
    }

    public void seedReset() {
        seedResets.increment();
    }

    public boolean verbose() {
        return verbose.get();
    }

    public void setVerbose(boolean enabled) {
        verbose.set(enabled);
    }

    public void reset() {
        for (StageStats s : stages) {
            s.calls.reset(); s.errors.reset(); s.totalNs.reset(); s.maxNs.set(0L);
            for (int i = 0; i < HISTOGRAM_BUCKETS; i++) s.histogram.set(i, 0L);
        }
        for (CacheStats c : caches) {
            c.hits.reset(); c.misses.reset(); c.trims.reset(); c.removed.reset();
        }
        duplicateEntries.reset(); seedRaces.reset(); invalidEnds.reset();
        droppedTracking.reset(); slowChunks.reset(); seedResets.reset();
        peakInFlight.set(inFlight.get()); worstNs.set(0L); worstChunk.set(0L);
        startedMs.set(System.currentTimeMillis());
    }

    public String compactStatus(long seed) {
        StageStats fill = stages[Stage.FILL_NOISE.ordinal()];
        long calls = fill.calls.sum();
        return "OceanGen chunks=" + calls + " avg=" + ms(fill.totalNs.sum(), calls) + "ms" +
            " p95=" + ms(percentile(fill, 0.95)) + "ms inFlight=" + inFlight.get() +
            " peak=" + peakInFlight.get() + " races=" + seedRaces.sum() +
            " duplicates=" + duplicateEntries.sum() + " errors=" + allErrors() + " seed=" + seed;
    }

    public String threadStatus() {
        StringBuilder out = new StringBuilder("OceanGen threads inFlight=")
            .append(inFlight.get()).append(" peak=").append(peakInFlight.get())
            .append(" activeStages=").append(activeChunks.size());
        threadCalls.entrySet().stream()
            .sorted((a, b) -> Long.compare(b.getValue().sum(), a.getValue().sum()))
            .limit(12)
            .forEach(e -> out.append("\n  ").append(e.getKey()).append('=').append(e.getValue().sum()));
        return out.toString();
    }

    public String fullReport(long seed) {
        long now = System.currentTimeMillis();
        double uptimeSec = Math.max(1.0, (now - startedMs.get()) / 1000.0);
        Runtime runtime = Runtime.getRuntime();
        long usedMb = (runtime.totalMemory() - runtime.freeMemory()) >> 20;
        StringBuilder out = new StringBuilder(1024);
        out.append("\n========== OceanGen diagnostics ==========")
            .append("\nuptime=").append(String.format("%.1fs", uptimeSec))
            .append(" seed=").append(seed)
            .append(" memoryUsed=").append(usedMb).append("MiB")
            .append(" memoryMax=").append(runtime.maxMemory() >> 20).append("MiB")
            .append("\nconcurrency current=").append(inFlight.get())
            .append(" peak=").append(peakInFlight.get())
            .append(" threads=").append(threadCalls.size())
            .append(" activeStages=").append(activeChunks.size());
        for (Stage stage : Stage.values()) {
            StageStats s = stages[stage.ordinal()];
            long calls = s.calls.sum();
            out.append("\nstage ").append(stage)
                .append(" calls=").append(calls)
                .append(" rate=").append(String.format("%.2f/s", calls / uptimeSec))
                .append(" avg=").append(ms(s.totalNs.sum(), calls)).append("ms")
                .append(" p50=").append(ms(percentile(s, .50))).append("ms")
                .append(" p95=").append(ms(percentile(s, .95))).append("ms")
                .append(" p99=").append(ms(percentile(s, .99))).append("ms")
                .append(" max=").append(ms(s.maxNs.get())).append("ms")
                .append(" errors=").append(s.errors.sum());
        }
        out.append("\nsafety seedRaces=").append(seedRaces.sum())
            .append(" duplicateStages=").append(duplicateEntries.sum())
            .append(" invalidEnds=").append(invalidEnds.sum())
            .append(" trackingDropped=").append(droppedTracking.sum())
            .append(" seedResets=").append(seedResets.sum())
            .append(" slowChunks=").append(slowChunks.sum())
            .append(" worst=").append(ms(worstNs.get())).append("ms@")
            .append(chunk(worstChunk.get()));
        for (Cache cache : Cache.values()) {
            CacheStats c = caches[cache.ordinal()];
            long hits = c.hits.sum(), misses = c.misses.sum(), accesses = hits + misses;
            out.append("\ncache ").append(cache)
                .append(" size=").append(c.size.get()).append('/').append(c.limit.get())
                .append(" hitRate=").append(accesses == 0 ? "n/a" : String.format("%.1f%%", hits * 100.0 / accesses))
                .append(" hits=").append(hits).append(" misses=").append(misses)
                .append(" trims=").append(c.trims.sum()).append(" removed=").append(c.removed.sum());
        }
        out.append("\n==========================================");
        return out.toString();
    }

    public void forceReport(long seed) {
        LOG.info(fullReport(seed));
        lastReportMs.set(System.currentTimeMillis());
    }

    private void maybeReport(long seed) {
        long now = System.currentTimeMillis(), last = lastReportMs.get();
        if (now - last >= REPORT_INTERVAL_MS && lastReportMs.compareAndSet(last, now)) {
            LOG.info(verbose.get() ? fullReport(seed) : compactStatus(seed));
        }
    }

    private void warnRateLimited(String message) {
        if (!verbose.get()) return;
        long now = System.currentTimeMillis(), last = lastWarningMs.get();
        if (now - last >= WARNING_INTERVAL_MS && lastWarningMs.compareAndSet(last, now)) {
            LOG.warn("[OceanGen safety] {}", message);
        }
    }

    private long allErrors() {
        long result = 0L;
        for (StageStats s : stages) result += s.errors.sum();
        return result;
    }

    private static long activeKey(Stage stage, long chunkKey) {
        return chunkKey ^ (0x9E3779B97F4A7C15L * (stage.ordinal() + 1L));
    }

    private static int bucket(long ns) {
        long micros = Math.max(1L, ns / 1_000L);
        return Math.min(HISTOGRAM_BUCKETS - 1, 63 - Long.numberOfLeadingZeros(micros));
    }

    private static long percentile(StageStats stats, double percentile) {
        long count = stats.calls.sum();
        if (count == 0) return 0L;
        long target = Math.max(1L, (long) Math.ceil(count * percentile));
        long seen = 0L;
        for (int i = 0; i < HISTOGRAM_BUCKETS; i++) {
            seen += stats.histogram.get(i);
            if (seen >= target) return (1L << i) * 1_000L;
        }
        return stats.maxNs.get();
    }

    private static String ms(long ns) {
        return String.format("%.3f", ns / 1_000_000.0);
    }

    private static String ms(long totalNs, long calls) {
        return ms(calls == 0 ? 0L : totalNs / calls);
    }

    private static String chunk(long key) {
        if (key == Long.MIN_VALUE) return "n/a";
        return "[" + (int) key + "," + (int) (key >>> 32) + "]";
    }
}
