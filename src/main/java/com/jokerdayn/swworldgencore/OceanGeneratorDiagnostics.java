package com.jokerdayn.swworldgencore;

import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.lang.management.BufferPoolMXBean;
import java.lang.management.ClassLoadingMXBean;
import java.lang.management.CompilationMXBean;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.DoubleAdder;
import java.util.concurrent.atomic.LongAdder;
import org.slf4j.Logger;

/**
 * Bounded diagnostics tailored to {@code OceanChunkGenerator}. The hot path is
 * lock-free; the only lock is used when a call is already slow enough to be
 * retained in the bounded top-slowest list. Terrain RNG is never touched.
 */
public final class OceanGeneratorDiagnostics {
    private static final Logger LOG = LogUtils.getLogger();
    private static final long REPORT_INTERVAL_MS = 30_000L;
    private static final long WARNING_INTERVAL_MS = 10_000L;
    private static final int HISTOGRAM_BUCKETS = 128;
    private static final long[] HISTOGRAM_UPPER_NS = histogramBounds();
    private static final int MAX_ACTIVE = 4096;
    private static final int MAX_THREADS = 128;
    private static final int MAX_SLOW_SAMPLES = 96;
    private static final int REPORTED_SLOW_SAMPLES = 24;
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter
        .ofPattern("uuuuMMdd-HHmmss", Locale.ROOT)
        .withZone(ZoneOffset.UTC);

    public enum Stage {
        FILL_NOISE(16.0),
        DECORATION(8.0),
        BASE_HEIGHT(2.0),
        BASE_COLUMN(4.0);

        private final long budgetNs;

        Stage(double budgetMs) {
            this.budgetNs = (long) (budgetMs * 1_000_000.0);
        }

        public long budgetNs() { return budgetNs; }
    }

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

        public Stage parent() { return parent; }
        public long budgetNs() { return budgetNs; }
        public boolean requiredPerParent() { return requiredPerParent; }
    }

    public enum Cache { FLOOR, BIOME, BEACH, DECOR, BOULDER }

    public enum Counter {
        COLUMNS_TOTAL,
        COLUMNS_LAND,
        COLUMNS_OCEAN,
        COLUMNS_SPAWN_ISLAND,
        COLUMNS_GRID_ISLAND,
        COLUMNS_VOLCANO,
        COLUMNS_CRATER,
        COLUMNS_BEACH,
        COLUMNS_SLOPE,
        BEACH_SEARCHES,
        BEACH_FLOOR_SAMPLES,
        SOLID_BLOCK_WRITES,
        WATER_BLOCK_WRITES,
        LAVA_BLOCK_WRITES,
        UNDERWATER_SLABS,
        SEAGRASS_BLOCKS,
        PALM_ATTEMPTS,
        PALMS_PLACED,
        PALM_BLOCK_WRITES,
        ACACIA_ATTEMPTS,
        ACACIAS_PLACED,
        ACACIA_BLOCK_WRITES,
        TREE_PREFLIGHT_FAILURES,
        BOULDER_LAYOUTS_BUILT,
        BOULDER_FRAGMENTS,
        BOULDER_BLOCK_WRITES,
        BOULDER_ORE_WRITES,
        SHELLS_PLACED,
        GROUND_DECORATIONS_PLACED,
        SHORT_GRASS_PLACED,
        FLOWERS_PLACED,
        BUSHES_PLACED,
        BUSH_LEAVES_PLACED,
        VOLCANIC_FEATURE_WRITES
    }

    private record ActiveKey(Stage stage, long chunkKey) {}

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
    ) {}

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

    public record ExportResult(Path reportPath, Path csvPath) {}

    private static final Token DISABLED_TOKEN = new Token(
        null, Long.MIN_VALUE, 0L, Long.MAX_VALUE, -1L, -1L,
        "<disabled>", "<disabled>", null, false, Long.MIN_VALUE
    );

    private static final class TimingStats {
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

        void record(
            long elapsedNs,
            long cpuNs,
            long allocatedBytes,
            long budgetNs,
            boolean error
        ) {
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
    }

    private static final class CacheStats {
        final LongAdder hits = new LongAdder();
        final LongAdder misses = new LongAdder();
        final LongAdder trims = new LongAdder();
        final LongAdder removed = new LongAdder();
        final LongAdder trimNs = new LongAdder();
        final LongAdder timedTrims = new LongAdder();
        final AtomicLong maxTrimNs = new AtomicLong();
        final AtomicInteger size = new AtomicInteger();
        final AtomicInteger limit = new AtomicInteger();
    }

    private static final class ThreadStats {
        final LongAdder calls = new LongAdder();
        final LongAdder errors = new LongAdder();
        final LongAdder wallNs = new LongAdder();
        final LongAdder cpuNs = new LongAdder();
        final LongAdder allocatedBytes = new LongAdder();
        final AtomicLong maxNs = new AtomicLong();
    }

    private record CollectorSample(long collections, long timeMs) {}

    private record RuntimeSample(
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
    ) {}

    private final TimingStats[] stages = new TimingStats[Stage.values().length];
    private final TimingStats[] phases = new TimingStats[Phase.values().length];
    private final CacheStats[] caches = new CacheStats[Cache.values().length];
    private final LongAdder[] counters = new LongAdder[Counter.values().length];
    private final ConcurrentHashMap<ActiveKey, Token> active = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ThreadStats> threadStats = new ConcurrentHashMap<>();
    private final Object slowLock = new Object();
    private final PriorityQueue<SlowSample> slowSamples = new PriorityQueue<>(
        Comparator.comparingLong(SlowSample::elapsedNs)
    );
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
    private final AtomicBoolean enabled = new AtomicBoolean(true);
    private final MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
    private final ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
    private final com.sun.management.ThreadMXBean allocationBean;
    private final boolean cpuTimeSupported;
    private final boolean allocationSupported;
    private volatile RuntimeSample baseline;

    public OceanGeneratorDiagnostics() {
        for (int i = 0; i < stages.length; i++) stages[i] = new TimingStats();
        for (int i = 0; i < phases.length; i++) phases[i] = new TimingStats();
        for (int i = 0; i < caches.length; i++) caches[i] = new CacheStats();
        for (int i = 0; i < counters.length; i++) counters[i] = new LongAdder();

        boolean cpuSupported = threadBean.isCurrentThreadCpuTimeSupported();
        if (cpuSupported && !threadBean.isThreadCpuTimeEnabled()) {
            try {
                threadBean.setThreadCpuTimeEnabled(true);
            } catch (RuntimeException ignored) {
                cpuSupported = false;
            }
        }
        cpuTimeSupported = cpuSupported;

        com.sun.management.ThreadMXBean alloc =
            threadBean instanceof com.sun.management.ThreadMXBean bean ? bean : null;
        boolean allocSupported = alloc != null && alloc.isThreadAllocatedMemorySupported();
        if (allocSupported && !alloc.isThreadAllocatedMemoryEnabled()) {
            try {
                alloc.setThreadAllocatedMemoryEnabled(true);
            } catch (RuntimeException ignored) {
                allocSupported = false;
            }
        }
        allocationBean = alloc;
        allocationSupported = allocSupported;
        baseline = runtimeSample();
    }

    public Token begin(Stage stage, long chunkKey, long seed) {
        if (!enabled.get()) return DISABLED_TOKEN;
        long now = System.nanoTime();
        Thread currentThread = Thread.currentThread();
        String threadName = currentThread.getName();
        String threadKey = threadStats.containsKey(threadName) || threadStats.size() < MAX_THREADS
            ? threadName
            : "<other>";
        int current = inFlight.incrementAndGet();
        peakInFlight.accumulateAndGet(current, Math::max);
        long currentEpoch = epoch.get();
        ActiveKey activeKey = chunkKey == Long.MIN_VALUE ? null : new ActiveKey(stage, chunkKey);
        Token token = new Token(
            stage,
            chunkKey,
            seed,
            now,
            currentCpuTime(),
            currentAllocatedBytes(),
            threadName,
            threadKey,
            activeKey,
            false,
            currentEpoch
        );
        if (activeKey != null) {
            if (active.size() < MAX_ACTIVE) {
                Token previous = active.putIfAbsent(activeKey, token);
                if (previous == null) {
                    Token trackedToken = new Token(
                        stage,
                        chunkKey,
                        seed,
                        now,
                        token.startedCpuNs(),
                        token.startedAllocatedBytes(),
                        threadName,
                        threadKey,
                        activeKey,
                        true,
                        currentEpoch
                    );
                    // begin() has not returned yet, therefore nobody can end this token
                    // between putIfAbsent and replace.
                    active.replace(activeKey, token, trackedToken);
                    token = trackedToken;
                } else {
                    duplicateEntries.increment();
                    warnRateLimited("duplicate " + stage + " at " + chunk(chunkKey));
                }
            } else {
                droppedTracking.increment();
            }
        }
        return token;
    }

    public void end(Token token, long currentSeed, Throwable error) {
        end(token, currentSeed, error, null);
    }

    public void end(Token token, long currentSeed, Throwable error, String detail) {
        if (token == DISABLED_TOKEN) return;
        if (token == null) {
            invalidEnds.increment();
            return;
        }
        long elapsed = Math.max(0L, System.nanoTime() - token.startedNs());
        long cpu = deltaMetric(currentCpuTime(), token.startedCpuNs());
        long allocated = deltaMetric(currentAllocatedBytes(), token.startedAllocatedBytes());
        if (token.epoch() == epoch.get()) {
            TimingStats stats = stages[token.stage().ordinal()];
            stats.record(
                elapsed,
                cpu,
                allocated,
                token.stage().budgetNs(),
                error != null
            );
            if (token.seed() != currentSeed) seedRaces.increment();
            updateThread(token, elapsed, cpu, allocated, error != null);
            if (elapsed > token.stage().budgetNs()) {
                recordSlow(token, elapsed, cpu, allocated, detail);
            }
        }
        if (
            token.trackedActive() &&
            !active.remove(token.activeKey(), token)
        ) invalidEnds.increment();
        int remaining = inFlight.decrementAndGet();
        if (remaining < 0) {
            invalidEnds.increment();
            inFlight.compareAndSet(remaining, 0);
        }
        maybeReport(currentSeed);
    }

    public void phase(Phase phase, long elapsedNs) {
        if (!enabled.get()) return;
        recordPhase(phase, elapsedNs);
    }

    private void recordPhase(Phase phase, long elapsedNs) {
        phases[phase.ordinal()].record(
            Math.max(0L, elapsedNs),
            -1L,
            -1L,
            phase.budgetNs(),
            false
        );
    }

    public void phase(Token token, Phase phase, long elapsedNs) {
        if (isCurrentWindow(token)) recordPhase(phase, elapsedNs);
    }

    public void add(Counter counter, long amount) {
        if (!enabled.get()) return;
        addCounter(counter, amount);
    }

    public void add(Token token, Counter counter, long amount) {
        if (isCurrentWindow(token)) addCounter(counter, amount);
    }

    private void addCounter(Counter counter, long amount) {
        if (amount > 0L) counters[counter.ordinal()].add(amount);
    }

    public void cacheAccess(Cache cache, boolean hit) {
        if (!enabled.get()) return;
        if (hit) caches[cache.ordinal()].hits.increment();
        else caches[cache.ordinal()].misses.increment();
    }

    public void cacheState(Cache cache, int size, int limit) {
        if (!enabled.get()) return;
        CacheStats stats = caches[cache.ordinal()];
        stats.size.set(Math.max(0, size));
        stats.limit.set(Math.max(0, limit));
    }

    public void cacheTrim(Cache cache, int removed) {
        cacheTrim(cache, removed, 0L);
    }

    public void cacheTrim(Cache cache, int removed, long elapsedNs) {
        if (!enabled.get()) return;
        if (removed <= 0) return;
        CacheStats stats = caches[cache.ordinal()];
        stats.trims.increment();
        stats.removed.add(removed);
        if (elapsedNs > 0L) {
            stats.timedTrims.increment();
            stats.trimNs.add(elapsedNs);
            stats.maxTrimNs.accumulateAndGet(elapsedNs, Math::max);
        }
    }

    public void seedReset() { if (enabled.get()) seedResets.increment(); }
    public boolean enabled() { return enabled.get(); }
    public void setEnabled(boolean value) { enabled.set(value); }
    public boolean verbose() { return verbose.get(); }
    public void setVerbose(boolean enabled) { verbose.set(enabled); }

    /** Starts a new window without corrupting measurements already in flight. */
    public void reset() {
        epoch.incrementAndGet();
        for (TimingStats stats : stages) stats.reset();
        for (TimingStats stats : phases) stats.reset();
        for (CacheStats cache : caches) {
            cache.hits.reset();
            cache.misses.reset();
            cache.trims.reset();
            cache.removed.reset();
            cache.trimNs.reset();
            cache.timedTrims.reset();
            cache.maxTrimNs.set(0L);
        }
        for (LongAdder counter : counters) counter.reset();
        duplicateEntries.reset();
        seedRaces.reset();
        invalidEnds.reset();
        droppedTracking.reset();
        slowStages.reset();
        seedResets.reset();
        synchronized (slowLock) {
            slowSamples.clear();
        }
        threadStats.clear();
        peakInFlight.set(inFlight.get());
        startedMs.set(System.currentTimeMillis());
        lastReportMs.set(System.currentTimeMillis());
        baseline = runtimeSample();
    }

    public String compactStatus(long seed) {
        TimingStats fill = stages[Stage.FILL_NOISE.ordinal()];
        long calls = fill.calls.sum();
        double seconds = elapsedSeconds();
        return "OceanGen enabled=" + enabled.get() + " seed=" + seed + " window=" + fmt(seconds) + "s chunks=" + calls
            + " rate=" + fmt(calls / seconds) + "/s avg=" + ms(fill.totalNs.sum(), calls)
            + "ms p95=" + ms(percentile(fill, .95)) + "ms p99=" + ms(percentile(fill, .99))
            + "ms alloc=" + bytes(avg(fill.totalAllocatedBytes.sum(), calls)) + "/chunk"
            + " inFlight=" + inFlight.get() + " peak=" + peakInFlight.get()
            + " errors=" + allErrors() + " findings=" + findingCount();
    }

    public String phaseStatus() {
        StringBuilder out = new StringBuilder("OceanGen phase timings");
        for (Phase phase : Phase.values()) {
            appendTiming(out, "phase", phase.name(), phases[phase.ordinal()], phase.budgetNs(), elapsedSeconds(), parentTotal(phase));
        }
        return out.toString();
    }

    public String counterStatus() {
        StringBuilder out = new StringBuilder("OceanGen terrain/decor counters");
        appendCounters(out);
        return out.toString();
    }

    public String cacheStatus() {
        StringBuilder out = new StringBuilder("OceanGen caches");
        for (Cache cache : Cache.values()) appendCache(out, cache, caches[cache.ordinal()]);
        return out.toString();
    }

    public String threadStatus() {
        StringBuilder out = new StringBuilder("OceanGen concurrency current=")
            .append(inFlight.get()).append(" peak=").append(peakInFlight.get())
            .append(" active=").append(active.size()).append(" trackedThreads=").append(threadStats.size());
        threadStats.entrySet().stream()
            .sorted((a, b) -> Long.compare(b.getValue().wallNs.sum(), a.getValue().wallNs.sum()))
            .limit(24)
            .forEach(entry -> appendThread(out, entry.getKey(), entry.getValue()));
        appendThreadStates(out);
        return out.toString();
    }

    public String activeStatus() {
        long now = System.nanoTime();
        List<Token> copy = new ArrayList<>(active.values());
        copy.sort(Comparator.comparingLong(Token::startedNs));
        StringBuilder out = new StringBuilder("OceanGen active operations count=")
            .append(copy.size()).append(" inFlight=").append(inFlight.get());
        copy.stream().limit(32).forEach(token -> out.append("\n  ")
            .append(token.stage()).append(' ').append(chunk(token.chunkKey()))
            .append(" age=").append(ms(Math.max(0L, now - token.startedNs()))).append("ms")
            .append(" thread=").append(token.threadName())
            .append(" seed=").append(token.seed()));
        if (copy.isEmpty()) out.append("\n  none");
        return out.toString();
    }

    public String slowStatus() {
        List<SlowSample> copy = slowCopy();
        StringBuilder out = new StringBuilder("OceanGen top slow calls retained=")
            .append(copy.size()).append(" overBudgetTotal=").append(slowStages.sum());
        copy.stream().limit(REPORTED_SLOW_SAMPLES).forEach(sample -> {
            out.append("\n  ").append(sample.stage()).append(' ')
                .append(chunk(sample.chunkKey())).append(" wall=")
                .append(ms(sample.elapsedNs())).append("ms cpu=")
                .append(msOrNa(sample.cpuNs())).append("ms alloc=")
                .append(bytes(sample.allocatedBytes())).append(" thread=")
                .append(sample.threadName());
            if (sample.detail() != null && !sample.detail().isBlank()) {
                out.append(" details={").append(sample.detail()).append('}');
            }
        });
        if (copy.isEmpty()) out.append("\n  none");
        return out.toString();
    }

    public String runtimeStatus() {
        RuntimeSample now = runtimeSample();
        RuntimeSample base = baseline;
        long wallDelta = Math.max(1L, now.wallMs() - base.wallMs());
        long cpuDelta = delta(now.processCpuNs(), base.processCpuNs());
        double usedCores = cpuDelta < 0L ? -1.0 : cpuDelta / (wallDelta * 1_000_000.0);
        StringBuilder out = new StringBuilder("OceanGen JVM/runtime")
            .append("\n  heap used=").append(bytes(now.heapUsed()))
            .append(" committed=").append(bytes(now.heapCommitted()))
            .append(" max=").append(bytes(now.heapMax()))
            .append(" nonHeap=").append(bytes(now.nonHeapUsed()))
            .append("\n  direct used=").append(bytes(now.directUsed()))
            .append(" capacity=").append(bytes(now.directCapacity()))
            .append(" mapped=").append(bytes(now.mappedUsed()))
            .append("\n  gc countDelta=").append(delta(now.gcCount(), base.gcCount()))
            .append(" timeDelta=").append(delta(now.gcTimeMs(), base.gcTimeMs())).append("ms")
            .append(" share=").append(percent(delta(now.gcTimeMs(), base.gcTimeMs()), wallDelta))
            .append("\n  processCpuDelta=").append(cpuDelta < 0L ? "n/a" : ms(cpuDelta) + "ms")
            .append(" usedCores=").append(usedCores < 0.0 ? "n/a" : fmt(usedCores))
            .append(" processLoad=").append(load(now.processCpuLoad()))
            .append(" systemLoad=").append(load(now.systemCpuLoad()))
            .append(" processors=").append(Runtime.getRuntime().availableProcessors())
            .append("\n  physical used=").append(bytes(safeSubtract(now.physicalTotal(), now.physicalFree())))
            .append('/').append(bytes(now.physicalTotal()))
            .append(" virtualCommitted=").append(bytes(now.committedVirtual()))
            .append("\n  threads live=").append(now.liveThreads())
            .append(" daemon=").append(now.daemonThreads())
            .append(" peak=").append(now.peakThreads())
            .append(" classesLoaded=").append(now.loadedClasses())
            .append(" unloaded=").append(now.unloadedClasses())
            .append(" jitTimeDeltaMs=")
            .append(delta(now.compilationTimeMs(), base.compilationTimeMs()));
        appendCollectors(out, now, base);
        appendMemoryPools(out);
        return out.toString();
    }

    public String diagnosis() {
        List<String> findings = findings();
        StringBuilder out = new StringBuilder("OceanGen automatic diagnosis findings=")
            .append(findings.size());
        if (findings.isEmpty()) return out.append("\n  no suspicious metrics in this window").toString();
        for (String finding : findings) out.append("\n  - ").append(finding);
        return out.toString();
    }

    public String histogramStatus() {
        StringBuilder out = new StringBuilder("OceanGen timing histograms (bucket upper bounds)");
        for (Stage stage : Stage.values()) appendHistogram(out, "stage " + stage, stages[stage.ordinal()]);
        for (Phase phase : Phase.values()) appendHistogram(out, "phase " + phase, phases[phase.ordinal()]);
        return out.toString();
    }

    public String fullReport(long seed) {
        double seconds = elapsedSeconds();
        StringBuilder out = new StringBuilder(16_384);
        out.append("\n================ OceanGen ultra benchmark ================")
            .append("\ngeneratedUtc=").append(Instant.now())
            .append(" windowSec=").append(fmt(seconds))
            .append(" seed=").append(seed)
            .append(" epoch=").append(epoch.get())
            .append(" enabled=").append(enabled.get())
            .append(" counters=approximate-under-concurrency")
            .append(" cpuTime=").append(cpuTimeSupported)
            .append(" threadAllocation=").append(allocationSupported);
        for (Stage stage : Stage.values()) {
            appendTiming(out, "stage", stage.name(), stages[stage.ordinal()], stage.budgetNs(), seconds, -1L);
        }
        out.append('\n').append(phaseStatus());
        out.append('\n').append(counterStatus());
        out.append('\n').append(cacheStatus());
        out.append("\nsafety errors=").append(allErrors())
            .append(" seedRaces=").append(seedRaces.sum())
            .append(" duplicates=").append(duplicateEntries.sum())
            .append(" invalidEnds=").append(invalidEnds.sum())
            .append(" trackingDropped=").append(droppedTracking.sum())
            .append(" seedResets=").append(seedResets.sum());
        out.append('\n').append(runtimeStatus());
        out.append('\n').append(threadStatus());
        out.append('\n').append(activeStatus());
        out.append('\n').append(slowStatus());
        out.append('\n').append(diagnosis());
        out.append('\n').append(histogramStatus());
        out.append("\n===========================================================");
        return out.toString();
    }

    public String csv(long seed) {
        RuntimeSample now = runtimeSample();
        RuntimeSample base = baseline;
        double seconds = elapsedSeconds();
        StringBuilder out = new StringBuilder(16_384);
        out.append("kind,name,metric,value,unit\n");
        csvRow(out, "window", "OceanGen", "seed", Long.toString(seed), "id");
        csvRow(out, "window", "OceanGen", "duration", fmt(seconds), "s");
        csvRow(out, "window", "OceanGen", "epoch", Long.toString(epoch.get()), "id");
        csvRow(out, "window", "OceanGen", "enabled", Boolean.toString(enabled.get()), "boolean");
        csvRow(out, "capability", "JVM", "thread_cpu_time", Boolean.toString(cpuTimeSupported), "boolean");
        csvRow(out, "capability", "JVM", "thread_allocation", Boolean.toString(allocationSupported), "boolean");
        for (Stage stage : Stage.values()) appendTimingCsv(out, "stage", stage.name(), stages[stage.ordinal()], stage.budgetNs(), seconds);
        for (Phase phase : Phase.values()) appendTimingCsv(out, "phase", phase.name(), phases[phase.ordinal()], phase.budgetNs(), seconds);
        for (Stage stage : Stage.values()) appendHistogramCsv(out, "stage_histogram", stage.name(), stages[stage.ordinal()]);
        for (Phase phase : Phase.values()) appendHistogramCsv(out, "phase_histogram", phase.name(), phases[phase.ordinal()]);
        for (Cache cache : Cache.values()) {
            CacheStats stats = caches[cache.ordinal()];
            long hits = stats.hits.sum(), misses = stats.misses.sum();
            csvRow(out, "cache", cache.name(), "hits", Long.toString(hits), "count");
            csvRow(out, "cache", cache.name(), "misses", Long.toString(misses), "count");
            csvRow(out, "cache", cache.name(), "hit_rate", ratio(hits, hits + misses), "ratio");
            csvRow(out, "cache", cache.name(), "size", Integer.toString(stats.size.get()), "entries");
            csvRow(out, "cache", cache.name(), "limit", Integer.toString(stats.limit.get()), "entries");
            csvRow(out, "cache", cache.name(), "trims", Long.toString(stats.trims.sum()), "count");
            csvRow(out, "cache", cache.name(), "removed", Long.toString(stats.removed.sum()), "entries");
            csvRow(out, "cache", cache.name(), "trim_total", Long.toString(stats.trimNs.sum()), "ns");
            csvRow(out, "cache", cache.name(), "timed_trims", Long.toString(stats.timedTrims.sum()), "count");
            csvRow(out, "cache", cache.name(), "trim_maximum", Long.toString(stats.maxTrimNs.get()), "ns");
        }
        for (Counter counter : Counter.values()) {
            csvRow(out, "counter", counter.name(), "total", Long.toString(counters[counter.ordinal()].sum()), "count");
        }
        csvRow(out, "safety", "OceanGen", "errors", Long.toString(allErrors()), "count");
        csvRow(out, "safety", "OceanGen", "seed_races", Long.toString(seedRaces.sum()), "count");
        csvRow(out, "safety", "OceanGen", "duplicate_entries", Long.toString(duplicateEntries.sum()), "count");
        csvRow(out, "safety", "OceanGen", "invalid_ends", Long.toString(invalidEnds.sum()), "count");
        csvRow(out, "safety", "OceanGen", "tracking_dropped", Long.toString(droppedTracking.sum()), "count");
        csvRow(out, "safety", "OceanGen", "seed_resets", Long.toString(seedResets.sum()), "count");
        csvRow(out, "concurrency", "OceanGen", "in_flight", Integer.toString(inFlight.get()), "count");
        csvRow(out, "concurrency", "OceanGen", "peak_in_flight", Integer.toString(peakInFlight.get()), "count");
        csvRow(out, "concurrency", "OceanGen", "active_tracked", Integer.toString(active.size()), "count");
        for (Map.Entry<String, ThreadStats> entry : threadStats.entrySet()) {
            ThreadStats stats = entry.getValue();
            csvRow(out, "thread", entry.getKey(), "calls", Long.toString(stats.calls.sum()), "count");
            csvRow(out, "thread", entry.getKey(), "wall", Long.toString(stats.wallNs.sum()), "ns");
            csvRow(out, "thread", entry.getKey(), "cpu", Long.toString(stats.cpuNs.sum()), "ns");
            csvRow(out, "thread", entry.getKey(), "allocated", Long.toString(stats.allocatedBytes.sum()), "bytes");
            csvRow(out, "thread", entry.getKey(), "maximum", Long.toString(stats.maxNs.get()), "ns");
            csvRow(out, "thread", entry.getKey(), "errors", Long.toString(stats.errors.sum()), "count");
        }
        csvRow(out, "runtime", "JVM", "heap_used", Long.toString(now.heapUsed()), "bytes");
        csvRow(out, "runtime", "JVM", "heap_committed", Long.toString(now.heapCommitted()), "bytes");
        csvRow(out, "runtime", "JVM", "heap_max", Long.toString(now.heapMax()), "bytes");
        csvRow(out, "runtime", "JVM", "non_heap_used", Long.toString(now.nonHeapUsed()), "bytes");
        csvRow(out, "runtime", "JVM", "direct_used", Long.toString(now.directUsed()), "bytes");
        csvRow(out, "runtime", "JVM", "direct_capacity", Long.toString(now.directCapacity()), "bytes");
        csvRow(out, "runtime", "JVM", "mapped_used", Long.toString(now.mappedUsed()), "bytes");
        csvRow(out, "runtime", "JVM", "gc_count_delta", Long.toString(delta(now.gcCount(), base.gcCount())), "count");
        csvRow(out, "runtime", "JVM", "gc_time_delta", Long.toString(delta(now.gcTimeMs(), base.gcTimeMs())), "ms");
        csvRow(out, "runtime", "JVM", "process_cpu_delta", Long.toString(delta(now.processCpuNs(), base.processCpuNs())), "ns");
        csvRow(out, "runtime", "JVM", "process_cpu_load", fmt(now.processCpuLoad()), "ratio");
        csvRow(out, "runtime", "JVM", "system_cpu_load", fmt(now.systemCpuLoad()), "ratio");
        csvRow(out, "runtime", "JVM", "physical_total", Long.toString(now.physicalTotal()), "bytes");
        csvRow(out, "runtime", "JVM", "physical_free", Long.toString(now.physicalFree()), "bytes");
        csvRow(out, "runtime", "JVM", "virtual_committed", Long.toString(now.committedVirtual()), "bytes");
        csvRow(out, "runtime", "JVM", "live_threads", Integer.toString(now.liveThreads()), "count");
        csvRow(out, "runtime", "JVM", "daemon_threads", Integer.toString(now.daemonThreads()), "count");
        csvRow(out, "runtime", "JVM", "peak_threads", Integer.toString(now.peakThreads()), "count");
        csvRow(out, "runtime", "JVM", "loaded_classes", Integer.toString(now.loadedClasses()), "count");
        csvRow(out, "runtime", "JVM", "unloaded_classes", Long.toString(now.unloadedClasses()), "count");
        csvRow(
            out,
            "runtime",
            "JVM",
            "jit_time_delta",
            Long.toString(delta(now.compilationTimeMs(), base.compilationTimeMs())),
            "ms"
        );
        for (Map.Entry<String, CollectorSample> entry : now.collectors().entrySet()) {
            CollectorSample before = base.collectors().get(entry.getKey());
            long count = before == null ? entry.getValue().collections() : delta(entry.getValue().collections(), before.collections());
            long time = before == null ? entry.getValue().timeMs() : delta(entry.getValue().timeMs(), before.timeMs());
            csvRow(out, "collector", entry.getKey(), "collections_delta", Long.toString(count), "count");
            csvRow(out, "collector", entry.getKey(), "time_delta", Long.toString(time), "ms");
        }
        for (SlowSample sample : slowCopy()) {
            String name = sample.stage() + "@" + chunk(sample.chunkKey()) + "@" + sample.capturedMs();
            csvRow(out, "slow", name, "wall", Long.toString(sample.elapsedNs()), "ns");
            csvRow(out, "slow", name, "cpu", Long.toString(sample.cpuNs()), "ns");
            csvRow(out, "slow", name, "allocated", Long.toString(sample.allocatedBytes()), "bytes");
            csvRow(out, "slow", name, "thread", sample.threadName(), "name");
            csvRow(out, "slow", name, "detail", sample.detail() == null ? "" : sample.detail(), "text");
        }
        int activeIndex = 0;
        long activeNow = System.nanoTime();
        for (Token token : active.values()) {
            String name = token.stage() + "@" + chunk(token.chunkKey()) + "@" + activeIndex++;
            csvRow(out, "active", name, "age", Long.toString(Math.max(0L, activeNow - token.startedNs())), "ns");
            csvRow(out, "active", name, "thread", token.threadName(), "name");
            csvRow(out, "active", name, "seed", Long.toString(token.seed()), "id");
        }
        return out.toString();
    }

    public void forceReport(long seed) {
        LOG.info(fullReport(seed));
        lastReportMs.set(System.currentTimeMillis());
    }

    public ExportResult export(Path directory, long seed) throws IOException {
        Files.createDirectories(directory);
        String baseName = "oceangen-" + FILE_TIME.format(Instant.now()) + "-seed-" + seed;
        Path report = directory.resolve(baseName + ".txt");
        Path csv = directory.resolve(baseName + ".csv");
        int suffix = 2;
        while (Files.exists(report) || Files.exists(csv)) {
            report = directory.resolve(baseName + '-' + suffix + ".txt");
            csv = directory.resolve(baseName + '-' + suffix + ".csv");
            suffix++;
        }
        Files.writeString(report, fullReport(seed), StandardCharsets.UTF_8);
        Files.writeString(csv, csv(seed), StandardCharsets.UTF_8);
        return new ExportResult(report.toAbsolutePath(), csv.toAbsolutePath());
    }

    private void updateThread(
        Token token,
        long elapsed,
        long cpu,
        long allocated,
        boolean error
    ) {
        ThreadStats stats = threadStats.computeIfAbsent(token.threadKey(), ignored -> new ThreadStats());
        stats.calls.increment();
        stats.wallNs.add(elapsed);
        stats.maxNs.accumulateAndGet(elapsed, Math::max);
        if (cpu >= 0L) stats.cpuNs.add(cpu);
        if (allocated >= 0L) stats.allocatedBytes.add(allocated);
        if (error) stats.errors.increment();
    }

    private void recordSlow(Token token, long elapsed, long cpu, long allocated, String detail) {
        slowStages.increment();
        SlowSample sample = new SlowSample(
            token.stage(),
            token.chunkKey(),
            elapsed,
            cpu,
            allocated,
            token.threadName(),
            System.currentTimeMillis(),
            detail
        );
        synchronized (slowLock) {
            if (slowSamples.size() < MAX_SLOW_SAMPLES) {
                slowSamples.add(sample);
            } else if (slowSamples.peek() != null && elapsed > slowSamples.peek().elapsedNs()) {
                slowSamples.poll();
                slowSamples.add(sample);
            }
        }
    }

    private List<SlowSample> slowCopy() {
        List<SlowSample> copy;
        synchronized (slowLock) {
            copy = new ArrayList<>(slowSamples);
        }
        copy.sort(Comparator.comparingLong(SlowSample::elapsedNs).reversed());
        return copy;
    }

    private void maybeReport(long seed) {
        if (!enabled.get()) return;
        long now = System.currentTimeMillis(), last = lastReportMs.get();
        if (
            now - last >= REPORT_INTERVAL_MS &&
            lastReportMs.compareAndSet(last, now)
        ) LOG.info(verbose.get() ? fullReport(seed) : compactStatus(seed));
    }

    private void warnRateLimited(String message) {
        if (!verbose.get()) return;
        long now = System.currentTimeMillis(), last = lastWarningMs.get();
        if (
            now - last >= WARNING_INTERVAL_MS &&
            lastWarningMs.compareAndSet(last, now)
        ) LOG.warn("[OceanGen] {}", message);
    }

    private RuntimeSample runtimeSample() {
        MemoryUsage heap = memoryBean.getHeapMemoryUsage();
        MemoryUsage nonHeap = memoryBean.getNonHeapMemoryUsage();
        long directUsed = 0L, directCapacity = 0L, mappedUsed = 0L;
        for (BufferPoolMXBean pool : ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class)) {
            if ("direct".equals(pool.getName())) {
                directUsed = pool.getMemoryUsed();
                directCapacity = pool.getTotalCapacity();
            } else if (pool.getName().startsWith("mapped")) {
                mappedUsed += Math.max(0L, pool.getMemoryUsed());
            }
        }
        long gcCount = 0L, gcTime = 0L;
        Map<String, CollectorSample> collectorSamples = new HashMap<>();
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            long count = Math.max(0L, gc.getCollectionCount());
            long time = Math.max(0L, gc.getCollectionTime());
            gcCount += count;
            gcTime += time;
            collectorSamples.put(gc.getName(), new CollectorSample(count, time));
        }
        long processCpu = -1L;
        double processLoad = -1.0, systemLoad = -1.0;
        long physicalTotal = -1L, physicalFree = -1L, virtual = -1L;
        if (
            ManagementFactory.getOperatingSystemMXBean() instanceof
                com.sun.management.OperatingSystemMXBean os
        ) {
            processCpu = os.getProcessCpuTime();
            processLoad = os.getProcessCpuLoad();
            systemLoad = os.getCpuLoad();
            physicalTotal = os.getTotalMemorySize();
            physicalFree = os.getFreeMemorySize();
            virtual = os.getCommittedVirtualMemorySize();
        }
        ClassLoadingMXBean classes = ManagementFactory.getClassLoadingMXBean();
        CompilationMXBean compilation = ManagementFactory.getCompilationMXBean();
        long compilationTime = compilation != null && compilation.isCompilationTimeMonitoringSupported()
            ? compilation.getTotalCompilationTime()
            : -1L;
        return new RuntimeSample(
            System.currentTimeMillis(),
            heap.getUsed(),
            heap.getCommitted(),
            heap.getMax(),
            nonHeap.getUsed(),
            directUsed,
            directCapacity,
            mappedUsed,
            gcCount,
            gcTime,
            processCpu,
            processLoad,
            systemLoad,
            physicalTotal,
            physicalFree,
            virtual,
            threadBean.getThreadCount(),
            threadBean.getDaemonThreadCount(),
            threadBean.getPeakThreadCount(),
            classes.getLoadedClassCount(),
            classes.getUnloadedClassCount(),
            compilationTime,
            Map.copyOf(collectorSamples)
        );
    }

    private long currentCpuTime() {
        if (!cpuTimeSupported) return -1L;
        try {
            return threadBean.getCurrentThreadCpuTime();
        } catch (RuntimeException ignored) {
            return -1L;
        }
    }

    private long currentAllocatedBytes() {
        if (!allocationSupported || allocationBean == null) return -1L;
        try {
            return allocationBean.getThreadAllocatedBytes(Thread.currentThread().threadId());
        } catch (RuntimeException ignored) {
            return -1L;
        }
    }

    private void appendTiming(
        StringBuilder out,
        String kind,
        String name,
        TimingStats stats,
        long budgetNs,
        double seconds,
        long parentNs
    ) {
        long calls = stats.calls.sum();
        long totalNs = stats.totalNs.sum();
        long cpuNs = stats.totalCpuNs.sum();
        long allocated = stats.totalAllocatedBytes.sum();
        out.append("\n").append(kind).append(' ').append(name)
            .append(" calls=").append(calls)
            .append(" rate=").append(fmt(calls / seconds)).append("/s")
            .append(" avg=").append(ms(totalNs, calls)).append("ms")
            .append(" min=").append(ms(minNs(stats))).append("ms")
            .append(" p50=").append(ms(percentile(stats, .50))).append("ms")
            .append(" p90=").append(ms(percentile(stats, .90))).append("ms")
            .append(" p95=").append(ms(percentile(stats, .95))).append("ms")
            .append(" p99=").append(ms(percentile(stats, .99))).append("ms")
            .append(" p99.9=").append(ms(percentile(stats, .999))).append("ms")
            .append(" max=").append(ms(stats.maxNs.get())).append("ms")
            .append(" stddev=").append(fmt(stddevMs(stats))).append("ms")
            .append(" budget=").append(ms(budgetNs)).append("ms")
            .append(" overBudget=").append(stats.overBudget.sum())
            .append('(').append(percent(stats.overBudget.sum(), calls)).append(')')
            .append(" errors=").append(stats.errors.sum());
        long cpuSamples = stats.cpuSamples.sum();
        if (cpuSamples > 0L) {
            out.append(" cpuAvg=").append(ms(cpuNs, cpuSamples)).append("ms")
                .append(" cpu/wall=").append(ratio(cpuNs, totalNs));
        } else {
            out.append(" cpuAvg=n/a cpu/wall=n/a");
        }
        long allocationSamples = stats.allocationSamples.sum();
        if (allocationSamples > 0L) {
            out.append(" allocAvg=").append(bytes(avg(allocated, allocationSamples)))
                .append(" allocMax=").append(bytes(stats.maxAllocatedBytes.get()))
                .append(" allocTotal=").append(bytes(allocated));
        } else {
            out.append(" allocAvg=n/a allocMax=n/a allocTotal=n/a");
        }
        if (parentNs >= 0L) out.append(" parentShare=").append(percent(totalNs, parentNs));
    }

    private static void appendCache(StringBuilder out, Cache cache, CacheStats stats) {
        long hits = stats.hits.sum(), misses = stats.misses.sum();
        int limit = stats.limit.get();
        out.append("\ncache ").append(cache)
            .append(" hitRate=").append(percent(hits, hits + misses))
            .append(" hits=").append(hits)
            .append(" misses=").append(misses)
            .append(" size=").append(stats.size.get()).append('/').append(limit)
            .append(" load=").append(percent(stats.size.get(), limit))
            .append(" trims=").append(stats.trims.sum())
            .append(" removed=").append(stats.removed.sum())
            .append(" trimAvg=")
            .append(stats.timedTrims.sum() == 0L
                ? "n/a"
                : ms(stats.trimNs.sum(), stats.timedTrims.sum()) + "ms")
            .append(" trimMax=").append(ms(stats.maxTrimNs.get())).append("ms")
            .append(" trimTotal=").append(ms(stats.trimNs.sum())).append("ms");
    }

    private void appendCounters(StringBuilder out) {
        long chunks = stages[Stage.FILL_NOISE.ordinal()].calls.sum();
        for (Counter counter : Counter.values()) {
            long value = counters[counter.ordinal()].sum();
            out.append("\n  ").append(counter).append('=').append(value);
            if (chunks > 0L) out.append(" perChunk=").append(fmt(value / (double) chunks));
        }
    }

    private static void appendThread(StringBuilder out, String name, ThreadStats stats) {
        long calls = stats.calls.sum(), wall = stats.wallNs.sum();
        out.append("\n  ").append(name)
            .append(" calls=").append(calls)
            .append(" wall=").append(ms(wall)).append("ms")
            .append(" avg=").append(ms(wall, calls)).append("ms")
            .append(" max=").append(ms(stats.maxNs.get())).append("ms")
            .append(" cpu=").append(ms(stats.cpuNs.sum())).append("ms")
            .append(" alloc=").append(bytes(stats.allocatedBytes.sum()))
            .append(" errors=").append(stats.errors.sum());
    }

    private void appendThreadStates(StringBuilder out) {
        EnumMap<Thread.State, Integer> states = new EnumMap<>(Thread.State.class);
        ThreadInfo[] infos = threadBean.getThreadInfo(threadBean.getAllThreadIds(), 0);
        for (ThreadInfo info : infos) {
            if (info != null) states.merge(info.getThreadState(), 1, Integer::sum);
        }
        out.append("\n  JVM states=").append(states);
    }

    private static void appendCollectors(StringBuilder out, RuntimeSample now, RuntimeSample base) {
        for (Map.Entry<String, CollectorSample> entry : now.collectors().entrySet()) {
            CollectorSample before = base.collectors().get(entry.getKey());
            long count = before == null ? entry.getValue().collections() : delta(entry.getValue().collections(), before.collections());
            long time = before == null ? entry.getValue().timeMs() : delta(entry.getValue().timeMs(), before.timeMs());
            out.append("\n  gc[").append(entry.getKey()).append("] countDelta=")
                .append(count).append(" timeDelta=").append(time).append("ms");
        }
    }

    private static void appendMemoryPools(StringBuilder out) {
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            MemoryUsage usage = pool.getUsage();
            if (usage == null) continue;
            out.append("\n  memoryPool[").append(pool.getName()).append("] used=")
                .append(bytes(usage.getUsed())).append(" committed=")
                .append(bytes(usage.getCommitted())).append(" max=")
                .append(bytes(usage.getMax()));
        }
    }

    private static void appendHistogram(StringBuilder out, String name, TimingStats stats) {
        long calls = stats.calls.sum();
        out.append("\n").append(name).append(" samples=").append(calls);
        if (calls == 0L) return;
        long lower = 0L;
        for (int i = 0; i < HISTOGRAM_BUCKETS; i++) {
            long count = stats.histogram.get(i);
            if (count > 0L) {
                out.append("\n  (").append(ms(lower)).append(',')
                    .append(ms(HISTOGRAM_UPPER_NS[i])).append("]ms=")
                    .append(count).append(" (").append(percent(count, calls)).append(')');
            }
            lower = HISTOGRAM_UPPER_NS[i];
        }
    }

    private void appendTimingCsv(
        StringBuilder out,
        String kind,
        String name,
        TimingStats stats,
        long budgetNs,
        double seconds
    ) {
        long calls = stats.calls.sum();
        csvRow(out, kind, name, "calls", Long.toString(calls), "count");
        csvRow(out, kind, name, "rate", fmt(calls / seconds), "calls_per_s");
        csvRow(out, kind, name, "total", Long.toString(stats.totalNs.sum()), "ns");
        csvRow(out, kind, name, "average", Long.toString(avg(stats.totalNs.sum(), calls)), "ns");
        csvRow(out, kind, name, "minimum", Long.toString(minNs(stats)), "ns");
        csvRow(out, kind, name, "p50", Long.toString(percentile(stats, .50)), "ns");
        csvRow(out, kind, name, "p90", Long.toString(percentile(stats, .90)), "ns");
        csvRow(out, kind, name, "p95", Long.toString(percentile(stats, .95)), "ns");
        csvRow(out, kind, name, "p99", Long.toString(percentile(stats, .99)), "ns");
        csvRow(out, kind, name, "p999", Long.toString(percentile(stats, .999)), "ns");
        csvRow(out, kind, name, "maximum", Long.toString(stats.maxNs.get()), "ns");
        csvRow(out, kind, name, "stddev", fmt(stddevMs(stats)), "ms");
        csvRow(out, kind, name, "budget", Long.toString(budgetNs), "ns");
        csvRow(out, kind, name, "over_budget", Long.toString(stats.overBudget.sum()), "count");
        csvRow(out, kind, name, "errors", Long.toString(stats.errors.sum()), "count");
        csvRow(out, kind, name, "cpu_samples", Long.toString(stats.cpuSamples.sum()), "count");
        csvRow(out, kind, name, "cpu_total", Long.toString(stats.totalCpuNs.sum()), "ns");
        csvRow(out, kind, name, "cpu_maximum", Long.toString(stats.maxCpuNs.get()), "ns");
        csvRow(out, kind, name, "allocation_samples", Long.toString(stats.allocationSamples.sum()), "count");
        csvRow(out, kind, name, "allocated_total", Long.toString(stats.totalAllocatedBytes.sum()), "bytes");
        csvRow(out, kind, name, "allocated_maximum", Long.toString(stats.maxAllocatedBytes.get()), "bytes");
    }

    private static void appendHistogramCsv(
        StringBuilder out,
        String kind,
        String name,
        TimingStats stats
    ) {
        for (int i = 0; i < HISTOGRAM_BUCKETS; i++) {
            long count = stats.histogram.get(i);
            if (count == 0L) continue;
            String upper = HISTOGRAM_UPPER_NS[i] == Long.MAX_VALUE
                ? "infinity"
                : Long.toString(HISTOGRAM_UPPER_NS[i]);
            csvRow(out, kind, name, "le_" + upper + "ns", Long.toString(count), "count");
        }
    }

    private List<String> findings() {
        List<String> findings = new ArrayList<>();
        if (allErrors() > 0L) findings.add("generation errors recorded: " + allErrors());
        if (seedRaces.sum() > 0L) findings.add("seed changed while generation was in flight: " + seedRaces.sum());
        if (duplicateEntries.sum() > 0L) findings.add("same stage/chunk entered concurrently: " + duplicateEntries.sum());
        if (invalidEnds.sum() > 0L) findings.add("diagnostic begin/end imbalance: " + invalidEnds.sum());
        if (droppedTracking.sum() > 0L) findings.add("active-operation tracking overflow: " + droppedTracking.sum());
        for (Stage stage : Stage.values()) {
            TimingStats stats = stages[stage.ordinal()];
            long calls = stats.calls.sum();
            if (calls < 16L) continue;
            long average = avg(stats.totalNs.sum(), calls);
            long p99 = percentile(stats, .99);
            if (average > stage.budgetNs()) {
                findings.add(stage + " average " + ms(average) + "ms exceeds budget " + ms(stage.budgetNs()) + "ms");
            }
            if (p99 > stage.budgetNs() * 3L) {
                findings.add(stage + " unstable tail: p99=" + ms(p99) + "ms, budget=" + ms(stage.budgetNs()) + "ms");
            }
            if (allocationSupported && avg(stats.totalAllocatedBytes.sum(), calls) > 1_048_576L) {
                findings.add(stage + " allocates " + bytes(avg(stats.totalAllocatedBytes.sum(), calls)) + " per call");
            }
            if (allocationSupported && stats.maxAllocatedBytes.get() > 2_097_152L) {
                findings.add(stage + " has a rare allocation spike of " + bytes(stats.maxAllocatedBytes.get()));
            }
            long cpu = stats.totalCpuNs.sum(), wall = stats.totalNs.sum();
            if (cpuTimeSupported && wall > 0L && cpu * 100L < wall * 45L) {
                findings.add(stage + " CPU/wall=" + ratio(cpu, wall) + " suggests waiting, contention, or scheduler stalls");
            }
        }
        for (Phase phase : Phase.values()) {
            TimingStats stats = phases[phase.ordinal()];
            long calls = stats.calls.sum();
            if (calls < 16L) continue;
            long average = avg(stats.totalNs.sum(), calls);
            long p99 = percentile(stats, .99);
            if (average > phase.budgetNs()) {
                findings.add(phase + " average " + ms(average) + "ms exceeds phase budget " + ms(phase.budgetNs()) + "ms");
            }
            if (p99 > phase.budgetNs() * 3L) {
                findings.add(phase + " unstable tail: p99=" + ms(p99) + "ms, budget=" + ms(phase.budgetNs()) + "ms");
            }
            long parentCalls = stages[phase.parent().ordinal()].calls.sum();
            if (
                phase.requiredPerParent() &&
                stages[phase.parent().ordinal()].errors.sum() == 0L &&
                parentCalls >= 16L &&
                calls < parentCalls
            ) {
                findings.add(phase + " has fewer samples than " + phase.parent() + ": " + calls + "/" + parentCalls);
            }
        }
        long columns = counter(Counter.COLUMNS_TOTAL);
        long landAndOcean = counter(Counter.COLUMNS_LAND) + counter(Counter.COLUMNS_OCEAN);
        if (columns != landAndOcean) {
            findings.add("terrain counter mismatch: total=" + columns + ", land+ocean=" + landAndOcean);
        }
        if (counter(Counter.PALMS_PLACED) > counter(Counter.PALM_ATTEMPTS)) {
            findings.add("palm counter invariant failed: placed exceeds attempts");
        }
        if (counter(Counter.ACACIAS_PLACED) > counter(Counter.ACACIA_ATTEMPTS)) {
            findings.add("acacia counter invariant failed: placed exceeds attempts");
        }
        if (counter(Counter.BOULDER_ORE_WRITES) > counter(Counter.BOULDER_BLOCK_WRITES)) {
            findings.add("boulder counter invariant failed: ore writes exceed all boulder writes");
        }
        long beachSearches = counter(Counter.BEACH_SEARCHES);
        long beachSamples = counter(Counter.BEACH_FLOOR_SAMPLES);
        if (beachSearches >= 1_000L && beachSamples > beachSearches * 20L) {
            findings.add(
                "beach detection samples " +
                fmt(beachSamples / (double) beachSearches) +
                " floor positions per uncached search"
            );
        }
        for (Cache cache : Cache.values()) {
            CacheStats stats = caches[cache.ordinal()];
            long hits = stats.hits.sum(), misses = stats.misses.sum(), total = hits + misses;
            if (total >= 1000L && hits * 100L < total * 70L) {
                findings.add(cache + " cache hit rate is low: " + percent(hits, total));
            }
            if (stats.maxTrimNs.get() > 2_000_000L) {
                findings.add(cache + " cache eviction pauses for up to " + ms(stats.maxTrimNs.get()) + "ms");
            }
            if (
                stats.trimNs.sum() > 0L &&
                stats.trimNs.sum() > elapsedSeconds() * 10_000_000.0
            ) {
                findings.add(cache + " cache eviction consumes " + ms(stats.trimNs.sum()) + "ms in this window");
            }
            if (stats.limit.get() > 0 && stats.size.get() > stats.limit.get()) {
                findings.add(cache + " cache exceeds its limit: " + stats.size.get() + "/" + stats.limit.get());
            }
        }
        RuntimeSample now = runtimeSample(), base = baseline;
        long wallMs = Math.max(1L, now.wallMs() - base.wallMs());
        long gcMs = delta(now.gcTimeMs(), base.gcTimeMs());
        if (gcMs >= 0L && gcMs * 100L > wallMs * 5L) {
            findings.add("GC consumed " + percent(gcMs, wallMs) + " of benchmark wall time");
        }
        if (now.heapMax() > 0L && now.heapUsed() * 100L > now.heapMax() * 85L) {
            findings.add("heap usage is above 85%: " + bytes(now.heapUsed()) + "/" + bytes(now.heapMax()));
        }
        if (now.systemCpuLoad() >= 0.95) {
            findings.add("system CPU is saturated at report time: " + load(now.systemCpuLoad()));
        }
        if (
            now.physicalTotal() > 0L &&
            now.physicalFree() >= 0L &&
            now.physicalFree() * 100L < now.physicalTotal() * 10L
        ) {
            findings.add(
                "system physical memory is below 10% free: " +
                bytes(now.physicalFree()) + "/" + bytes(now.physicalTotal())
            );
        }
        if (!active.isEmpty()) {
            long oldestNs = System.nanoTime() - active.values().stream()
                .mapToLong(Token::startedNs).min().orElse(System.nanoTime());
            if (oldestNs > 5_000_000_000L) {
                findings.add("an operation has been active for " + ms(oldestNs) + "ms; inspect benchmark active");
            }
        }
        return findings;
    }

    private int findingCount() { return findings().size(); }
    private long counter(Counter counter) { return counters[counter.ordinal()].sum(); }
    private boolean isCurrentWindow(Token token) {
        return token != null && token.epoch() == epoch.get();
    }
    private long parentTotal(Phase phase) { return stages[phase.parent().ordinal()].totalNs.sum(); }
    private double elapsedSeconds() { return Math.max(.001, (System.currentTimeMillis() - startedMs.get()) / 1000.0); }
    private long allErrors() { long count = 0L; for (TimingStats stats : stages) count += stats.errors.sum(); return count; }

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
        int low = 0, high = HISTOGRAM_UPPER_NS.length - 1;
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (ns <= HISTOGRAM_UPPER_NS[middle]) high = middle;
            else low = middle + 1;
        }
        return low;
    }

    private static long percentile(TimingStats stats, double percentile) {
        long count = stats.calls.sum();
        if (count == 0L) return 0L;
        long target = Math.max(1L, (long) Math.ceil(count * percentile));
        long seen = 0L;
        for (int i = 0; i < HISTOGRAM_BUCKETS; i++) {
            seen += stats.histogram.get(i);
            if (seen >= target) return Math.min(HISTOGRAM_UPPER_NS[i], stats.maxNs.get());
        }
        return stats.maxNs.get();
    }

    private static double stddevMs(TimingStats stats) {
        long calls = stats.calls.sum();
        if (calls <= 1L) return 0.0;
        double averageMs = stats.totalNs.sum() / calls / 1_000_000.0;
        return Math.sqrt(Math.max(0.0, stats.totalMsSquared.sum() / calls - averageMs * averageMs));
    }

    private static long minNs(TimingStats stats) {
        long value = stats.minNs.get();
        return value == Long.MAX_VALUE ? 0L : value;
    }

    private static void csvRow(
        StringBuilder out,
        String kind,
        String name,
        String metric,
        String value,
        String unit
    ) {
        out.append(csv(kind)).append(',').append(csv(name)).append(',')
            .append(csv(metric)).append(',').append(csv(value)).append(',')
            .append(csv(unit)).append('\n');
    }

    private static String csv(String value) {
        if (value.indexOf(',') < 0 && value.indexOf('"') < 0 && value.indexOf('\n') < 0) return value;
        return '"' + value.replace("\"", "\"\"") + '"';
    }

    private static long deltaMetric(long current, long started) {
        return current < 0L || started < 0L ? -1L : Math.max(0L, current - started);
    }

    private static long avg(long total, long calls) { return calls == 0L ? 0L : total / calls; }
    private static String chunk(long key) { return key == Long.MIN_VALUE ? "n/a" : "[" + (int) key + "," + (int) (key >>> 32) + "]"; }
    private static String ms(long ns) { return fmt(ns / 1_000_000.0); }
    private static String ms(long ns, long calls) { return ms(avg(ns, calls)); }
    private static String msOrNa(long ns) { return ns < 0L ? "n/a" : ms(ns); }
    private static String fmt(double value) { return String.format(Locale.ROOT, "%.3f", value); }
    private static String ratio(long part, long total) { return part < 0L || total <= 0L ? "n/a" : fmt(part / (double) total); }
    private static String percent(long part, long total) { return part < 0L || total <= 0L ? "n/a" : fmt(part * 100.0 / total) + '%'; }
    private static String load(double value) { return value < 0.0 ? "n/a" : fmt(value * 100.0) + '%'; }
    private static long safeSubtract(long total, long free) { return total < 0L || free < 0L ? -1L : Math.max(0L, total - free); }
    private static long delta(long value, long base) { return value < 0L || base < 0L ? -1L : Math.max(0L, value - base); }

    private static String bytes(long value) {
        if (value < 0L) return "n/a";
        if (value < 1024L) return value + "B";
        double scaled = value;
        String[] units = { "KiB", "MiB", "GiB", "TiB" };
        int unit = -1;
        do {
            scaled /= 1024.0;
            unit++;
        } while (scaled >= 1024.0 && unit < units.length - 1);
        return fmt(scaled) + units[unit];
    }
}
