package com.jokerdayn.swworldgencore.diagnostics;

import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import org.slf4j.Logger;

/**
 * Bounded, lock-free instrumentation for the ocean generator.
 *
 * <p>Recording never touches terrain RNG and never blocks; the only lock guards the
 * bounded top-slowest list, and it is taken only for a call that is already over budget.
 * All rendering lives in {@link DiagnosticsReporter} so that nothing on a generation
 * thread can accidentally pull in an MXBean walk.</p>
 */
public final class GeneratorDiagnostics {

    private static final Logger LOG = LogUtils.getLogger();
    private static final long REPORT_INTERVAL_MS = 30_000L;
    private static final long WARNING_INTERVAL_MS = 10_000L;
    private static final int MAX_ACTIVE = 4096;
    private static final int MAX_THREADS = 128;
    private static final int MAX_SLOW_SAMPLES = 96;
    static final int REPORTED_SLOW_SAMPLES = 24;
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter
        .ofPattern("uuuuMMdd-HHmmss", Locale.ROOT)
        .withZone(ZoneOffset.UTC);

    /** Sentinel handed out while disabled; compared by identity, never recorded. */
    private static final Token DISABLED_TOKEN = new Token(
        null, Long.MIN_VALUE, 0L, Long.MAX_VALUE, -1L, -1L,
        "<disabled>", "<disabled>", null, false, Long.MIN_VALUE
    );

    private final TimingStats[] stages = new TimingStats[Stage.values().length];
    private final TimingStats[] phases = new TimingStats[Phase.values().length];
    private final CacheStats[] caches = new CacheStats[CacheId.values().length];
    private final LongAdder[] counters = new LongAdder[Counter.values().length];
    private final ConcurrentHashMap<Token.ActiveKey, Token> active = new ConcurrentHashMap<>();
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

    private final RuntimeProbe probe = new RuntimeProbe();
    private final DiagnosticsReporter reporter = new DiagnosticsReporter(this);
    private volatile RuntimeSample baseline;

    public GeneratorDiagnostics() {
        for (int i = 0; i < stages.length; i++) stages[i] = new TimingStats();
        for (int i = 0; i < phases.length; i++) phases[i] = new TimingStats();
        for (int i = 0; i < caches.length; i++) caches[i] = new CacheStats();
        for (int i = 0; i < counters.length; i++) counters[i] = new LongAdder();
        baseline = probe.sample();
    }

    // -------------------------------------------------------------------------
    // Hot path
    // -------------------------------------------------------------------------

    /**
     * Opens a measurement.
     *
     * @param chunkKey packed {@code ChunkPos}, or {@link Long#MIN_VALUE} for stages that
     *                 are not chunk-scoped (those skip in-flight tracking entirely)
     */
    public Token begin(Stage stage, long chunkKey, long seed) {
        if (!enabled.get()) return DISABLED_TOKEN;

        long now = System.nanoTime();
        String threadName = Thread.currentThread().getName();
        String threadKey =
            threadStats.containsKey(threadName) || threadStats.size() < MAX_THREADS
                ? threadName
                : "<other>";
        peakInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max);

        // The per-thread MXBean probes cost ~1us each, which only pays for itself on the
        // coarse once-per-chunk stages.
        boolean detailed = stage.detailed();
        long startedCpu = detailed ? probe.currentCpuTime() : -1L;
        long startedAllocated = detailed ? probe.currentAllocatedBytes() : -1L;

        Token.ActiveKey activeKey =
            chunkKey == Long.MIN_VALUE ? null : new Token.ActiveKey(stage, chunkKey);
        if (activeKey == null) {
            return new Token(
                stage, chunkKey, seed, now, startedCpu, startedAllocated,
                threadName, threadKey, null, false, epoch.get()
            );
        }
        if (active.size() >= MAX_ACTIVE) {
            droppedTracking.increment();
            return new Token(
                stage, chunkKey, seed, now, startedCpu, startedAllocated,
                threadName, threadKey, activeKey, false, epoch.get()
            );
        }

        // Claim the slot with the token we are about to return, so the common path
        // allocates exactly one Token instead of two.
        Token token = new Token(
            stage, chunkKey, seed, now, startedCpu, startedAllocated,
            threadName, threadKey, activeKey, true, epoch.get()
        );
        Token previous = active.putIfAbsent(activeKey, token);
        if (previous == null) return token;

        // Someone else already owns this stage/chunk: hand back an untracked token so
        // end() cannot evict their entry.
        duplicateEntries.increment();
        warnRateLimited("duplicate " + stage + " at " + DiagnosticsFormat.chunk(chunkKey));
        return new Token(
            stage, chunkKey, seed, now, startedCpu, startedAllocated,
            threadName, threadKey, activeKey, false, epoch.get()
        );
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
        boolean detailed = token.stage().detailed();
        long cpu = detailed
            ? DiagnosticsFormat.deltaMetric(probe.currentCpuTime(), token.startedCpuNs())
            : -1L;
        long allocated = detailed
            ? DiagnosticsFormat.deltaMetric(
                probe.currentAllocatedBytes(), token.startedAllocatedBytes())
            : -1L;

        // A reset() between begin and end starts a new window; discard the stale sample
        // rather than mixing it into fresh statistics.
        if (token.epoch() == epoch.get()) {
            stages[token.stage().ordinal()].record(
                elapsed, cpu, allocated, token.stage().budgetNs(), error != null
            );
            if (token.seed() != currentSeed) seedRaces.increment();
            updateThread(token, elapsed, cpu, allocated, error != null);
            if (elapsed > token.stage().budgetNs()) {
                recordSlow(token, elapsed, cpu, allocated, detail);
            }
        }

        if (token.trackedActive() && !active.remove(token.activeKey(), token)) {
            invalidEnds.increment();
        }
        int remaining = inFlight.decrementAndGet();
        if (remaining < 0) {
            invalidEnds.increment();
            inFlight.compareAndSet(remaining, 0);
        }
        maybeReport(currentSeed);
    }

    public void phase(Phase phase, long elapsedNs) {
        if (enabled.get()) recordPhase(phase, elapsedNs);
    }

    /** Records a phase only if its parent token still belongs to the current window. */
    public void phase(Token token, Phase phase, long elapsedNs) {
        if (isCurrentWindow(token)) recordPhase(phase, elapsedNs);
    }

    private void recordPhase(Phase phase, long elapsedNs) {
        phases[phase.ordinal()].record(
            Math.max(0L, elapsedNs), -1L, -1L, phase.budgetNs(), false
        );
    }

    public void add(Counter counter, long amount) {
        if (enabled.get()) addCounter(counter, amount);
    }

    public void add(Token token, Counter counter, long amount) {
        if (isCurrentWindow(token)) addCounter(counter, amount);
    }

    private void addCounter(Counter counter, long amount) {
        if (amount > 0L) counters[counter.ordinal()].add(amount);
    }

    public void cacheAccess(CacheId cache, boolean hit) {
        if (!enabled.get()) return;
        CacheStats stats = caches[cache.ordinal()];
        if (hit) stats.hits.increment();
        else stats.misses.increment();
    }

    public void cacheState(CacheId cache, int size, int limit) {
        if (!enabled.get()) return;
        CacheStats stats = caches[cache.ordinal()];
        stats.size.set(Math.max(0, size));
        stats.limit.set(Math.max(0, limit));
    }

    public void cacheTrim(CacheId cache, int removed) {
        cacheTrim(cache, removed, 0L);
    }

    public void cacheTrim(CacheId cache, int removed, long elapsedNs) {
        if (!enabled.get() || removed <= 0) return;
        CacheStats stats = caches[cache.ordinal()];
        stats.trims.increment();
        stats.removed.add(removed);
        if (elapsedNs > 0L) {
            stats.timedTrims.increment();
            stats.trimNs.add(elapsedNs);
            stats.maxTrimNs.accumulateAndGet(elapsedNs, Math::max);
        }
    }

    public void seedReset() {
        if (enabled.get()) seedResets.increment();
    }

    // -------------------------------------------------------------------------
    // Control
    // -------------------------------------------------------------------------

    public boolean enabled() {
        return enabled.get();
    }

    public void setEnabled(boolean value) {
        enabled.set(value);
    }

    public boolean verbose() {
        return verbose.get();
    }

    public void setVerbose(boolean value) {
        verbose.set(value);
    }

    /** Starts a new window without corrupting measurements already in flight. */
    public void reset() {
        epoch.incrementAndGet();
        for (TimingStats stats : stages) stats.reset();
        for (TimingStats stats : phases) stats.reset();
        for (CacheStats cache : caches) cache.reset();
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
        long now = System.currentTimeMillis();
        startedMs.set(now);
        lastReportMs.set(now);
        baseline = probe.sample();
    }

    // -------------------------------------------------------------------------
    // Reports (delegated)
    // -------------------------------------------------------------------------

    /**
     * One-line status.
     *
     * <p>Deliberately cheap: this is rendered on the F3 debug overlay every frame, so it
     * must not walk MXBeans or run the automatic diagnosis. Use {@link #diagnosis()} for
     * that.</p>
     */
    public String compactStatus(long seed) {
        return reporter.compactStatus(seed);
    }

    public String phaseStatus() {
        return reporter.phaseStatus();
    }

    public String counterStatus() {
        return reporter.counterStatus();
    }

    public String cacheStatus() {
        return reporter.cacheStatus();
    }

    public String threadStatus() {
        return reporter.threadStatus();
    }

    public String activeStatus() {
        return reporter.activeStatus();
    }

    public String slowStatus() {
        return reporter.slowStatus();
    }

    public String runtimeStatus() {
        return reporter.runtimeStatus();
    }

    public String diagnosis() {
        return reporter.diagnosis();
    }

    public String histogramStatus() {
        return reporter.histogramStatus();
    }

    public String fullReport(long seed) {
        return reporter.fullReport(seed);
    }

    public String csv(long seed) {
        return reporter.csv(seed);
    }

    public void forceReport(long seed) {
        LOG.info(fullReport(seed));
        lastReportMs.set(System.currentTimeMillis());
    }

    public ExportResult export(Path directory, long seed) throws IOException {
        Files.createDirectories(directory);
        String baseName = "oceangen-" + FILE_TIME.format(Instant.now()) + "-seed-" + seed;
        Path report = directory.resolve(baseName + ".txt");
        Path csvPath = directory.resolve(baseName + ".csv");
        int suffix = 2;
        while (Files.exists(report) || Files.exists(csvPath)) {
            report = directory.resolve(baseName + '-' + suffix + ".txt");
            csvPath = directory.resolve(baseName + '-' + suffix + ".csv");
            suffix++;
        }
        Files.writeString(report, fullReport(seed), StandardCharsets.UTF_8);
        Files.writeString(csvPath, csv(seed), StandardCharsets.UTF_8);
        return new ExportResult(report.toAbsolutePath(), csvPath.toAbsolutePath());
    }

    // -------------------------------------------------------------------------
    // Internals
    // -------------------------------------------------------------------------

    private void updateThread(
        Token token,
        long elapsed,
        long cpu,
        long allocated,
        boolean error
    ) {
        ThreadStats stats =
            threadStats.computeIfAbsent(token.threadKey(), ignored -> new ThreadStats());
        stats.calls.increment();
        stats.wallNs.add(elapsed);
        stats.maxNs.accumulateAndGet(elapsed, Math::max);
        if (cpu >= 0L) stats.cpuNs.add(cpu);
        if (allocated >= 0L) stats.allocatedBytes.add(allocated);
        if (error) stats.errors.increment();
    }

    private void recordSlow(
        Token token,
        long elapsed,
        long cpu,
        long allocated,
        String detail
    ) {
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
            } else {
                SlowSample weakest = slowSamples.peek();
                if (weakest != null && elapsed > weakest.elapsedNs()) {
                    slowSamples.poll();
                    slowSamples.add(sample);
                }
            }
        }
    }

    private void maybeReport(long seed) {
        if (!enabled.get()) return;
        long now = System.currentTimeMillis();
        long last = lastReportMs.get();
        if (now - last >= REPORT_INTERVAL_MS && lastReportMs.compareAndSet(last, now)) {
            LOG.info(verbose.get() ? fullReport(seed) : compactStatus(seed));
        }
    }

    private void warnRateLimited(String message) {
        if (!verbose.get()) return;
        long now = System.currentTimeMillis();
        long last = lastWarningMs.get();
        if (now - last >= WARNING_INTERVAL_MS && lastWarningMs.compareAndSet(last, now)) {
            LOG.warn("[OceanGen] {}", message);
        }
    }

    private boolean isCurrentWindow(Token token) {
        return token != null && token != DISABLED_TOKEN && token.epoch() == epoch.get();
    }

    // -------------------------------------------------------------------------
    // Views for DiagnosticsReporter (same package)
    // -------------------------------------------------------------------------

    TimingStats stageStats(Stage stage) {
        return stages[stage.ordinal()];
    }

    TimingStats phaseStats(Phase phase) {
        return phases[phase.ordinal()];
    }

    CacheStats cacheStats(CacheId cache) {
        return caches[cache.ordinal()];
    }

    long counter(Counter counter) {
        return counters[counter.ordinal()].sum();
    }

    Map<String, ThreadStats> threads() {
        return threadStats;
    }

    Collection<Token> activeTokens() {
        return active.values();
    }

    List<SlowSample> slowCopy() {
        List<SlowSample> copy;
        synchronized (slowLock) {
            copy = new ArrayList<>(slowSamples);
        }
        copy.sort(Comparator.comparingLong(SlowSample::elapsedNs).reversed());
        return copy;
    }

    RuntimeProbe probe() {
        return probe;
    }

    RuntimeSample baseline() {
        return baseline;
    }

    long epochValue() {
        return epoch.get();
    }

    int inFlightCount() {
        return inFlight.get();
    }

    int peakInFlightCount() {
        return peakInFlight.get();
    }

    int activeCount() {
        return active.size();
    }

    long duplicateEntryCount() {
        return duplicateEntries.sum();
    }

    long seedRaceCount() {
        return seedRaces.sum();
    }

    long invalidEndCount() {
        return invalidEnds.sum();
    }

    long droppedTrackingCount() {
        return droppedTracking.sum();
    }

    long slowStageCount() {
        return slowStages.sum();
    }

    long seedResetCount() {
        return seedResets.sum();
    }

    long allErrors() {
        long count = 0L;
        for (TimingStats stats : stages) count += stats.errorCount();
        return count;
    }

    double elapsedSeconds() {
        return Math.max(.001, (System.currentTimeMillis() - startedMs.get()) / 1000.0);
    }
}
