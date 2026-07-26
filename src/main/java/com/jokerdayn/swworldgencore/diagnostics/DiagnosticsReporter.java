package com.jokerdayn.swworldgencore.diagnostics;

import static com.jokerdayn.swworldgencore.diagnostics.DiagnosticsFormat.avg;
import static com.jokerdayn.swworldgencore.diagnostics.DiagnosticsFormat.bytes;
import static com.jokerdayn.swworldgencore.diagnostics.DiagnosticsFormat.chunk;
import static com.jokerdayn.swworldgencore.diagnostics.DiagnosticsFormat.csvRow;
import static com.jokerdayn.swworldgencore.diagnostics.DiagnosticsFormat.delta;
import static com.jokerdayn.swworldgencore.diagnostics.DiagnosticsFormat.fmt;
import static com.jokerdayn.swworldgencore.diagnostics.DiagnosticsFormat.load;
import static com.jokerdayn.swworldgencore.diagnostics.DiagnosticsFormat.ms;
import static com.jokerdayn.swworldgencore.diagnostics.DiagnosticsFormat.msOrNa;
import static com.jokerdayn.swworldgencore.diagnostics.DiagnosticsFormat.percent;
import static com.jokerdayn.swworldgencore.diagnostics.DiagnosticsFormat.ratio;
import static com.jokerdayn.swworldgencore.diagnostics.DiagnosticsFormat.safeSubtract;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryUsage;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Renders {@link GeneratorDiagnostics} state as plain text, CSV and an automatic
 * diagnosis.
 *
 * <p>Split out from the recorder on purpose: everything here is allowed to be slow
 * (MXBean walks, sorting, percentile scans), and keeping it in a separate type makes it
 * obvious that no generation thread should call into it.</p>
 */
final class DiagnosticsReporter {

    private final GeneratorDiagnostics diagnostics;

    DiagnosticsReporter(GeneratorDiagnostics diagnostics) {
        this.diagnostics = diagnostics;
    }

    // -------------------------------------------------------------------------
    // Text views
    // -------------------------------------------------------------------------

    String compactStatus(long seed) {
        TimingStats fill = diagnostics.stageStats(Stage.FILL_NOISE);
        long calls = fill.callCount();
        double seconds = diagnostics.elapsedSeconds();
        return "OceanGen enabled=" + diagnostics.enabled()
            + " seed=" + seed
            + " window=" + fmt(seconds) + "s"
            + " chunks=" + calls
            + " rate=" + fmt(calls / seconds) + "/s"
            + " avg=" + ms(fill.totalElapsedNs(), calls) + "ms"
            + " p95=" + ms(fill.percentile(.95)) + "ms"
            + " p99=" + ms(fill.percentile(.99)) + "ms"
            + " alloc=" + bytes(avg(fill.totalAllocatedBytes.sum(), calls)) + "/chunk"
            + " inFlight=" + diagnostics.inFlightCount()
            + " peak=" + diagnostics.peakInFlightCount()
            + " overBudget=" + diagnostics.slowStageCount()
            + " errors=" + diagnostics.allErrors();
    }

    String phaseStatus() {
        StringBuilder out = new StringBuilder("OceanGen phase timings");
        double seconds = diagnostics.elapsedSeconds();
        for (Phase phase : Phase.values()) {
            appendTiming(
                out,
                "phase",
                phase.name(),
                diagnostics.phaseStats(phase),
                phase.budgetNs(),
                seconds,
                diagnostics.stageStats(phase.parent()).totalElapsedNs()
            );
        }
        return out.toString();
    }

    String counterStatus() {
        StringBuilder out = new StringBuilder("OceanGen terrain/decor counters");
        long chunks = diagnostics.stageStats(Stage.FILL_NOISE).callCount();
        for (Counter counter : Counter.values()) {
            long value = diagnostics.counter(counter);
            out.append("\n  ").append(counter).append('=').append(value);
            if (chunks > 0L) out.append(" perChunk=").append(fmt(value / (double) chunks));
        }
        return out.toString();
    }

    String cacheStatus() {
        StringBuilder out = new StringBuilder("OceanGen caches");
        for (CacheId cache : CacheId.values()) {
            appendCache(out, cache, diagnostics.cacheStats(cache));
        }
        return out.toString();
    }

    String threadStatus() {
        StringBuilder out = new StringBuilder("OceanGen concurrency current=")
            .append(diagnostics.inFlightCount())
            .append(" peak=").append(diagnostics.peakInFlightCount())
            .append(" active=").append(diagnostics.activeCount())
            .append(" trackedThreads=").append(diagnostics.threads().size());
        diagnostics.threads().entrySet().stream()
            .sorted(Comparator.comparingLong(
                (Map.Entry<String, ThreadStats> e) -> e.getValue().wallNanos()).reversed())
            .limit(24)
            .forEach(entry -> appendThread(out, entry.getKey(), entry.getValue()));
        out.append("\n  JVM states=").append(diagnostics.probe().threadStateHistogram());
        return out.toString();
    }

    String activeStatus() {
        long now = System.nanoTime();
        List<Token> copy = new ArrayList<>(diagnostics.activeTokens());
        copy.sort(Comparator.comparingLong(Token::startedNs));
        StringBuilder out = new StringBuilder("OceanGen active operations count=")
            .append(copy.size())
            .append(" inFlight=").append(diagnostics.inFlightCount());
        copy.stream().limit(32).forEach(token -> out.append("\n  ")
            .append(token.stage()).append(' ').append(chunk(token.chunkKey()))
            .append(" age=").append(ms(Math.max(0L, now - token.startedNs()))).append("ms")
            .append(" thread=").append(token.threadName())
            .append(" seed=").append(token.seed()));
        if (copy.isEmpty()) out.append("\n  none");
        return out.toString();
    }

    String slowStatus() {
        List<SlowSample> copy = diagnostics.slowCopy();
        StringBuilder out = new StringBuilder("OceanGen top slow calls retained=")
            .append(copy.size())
            .append(" overBudgetTotal=").append(diagnostics.slowStageCount());
        copy.stream().limit(GeneratorDiagnostics.REPORTED_SLOW_SAMPLES).forEach(sample -> {
            out.append("\n  ").append(sample.stage()).append(' ')
                .append(chunk(sample.chunkKey()))
                .append(" wall=").append(ms(sample.elapsedNs())).append("ms")
                .append(" cpu=").append(msOrNa(sample.cpuNs())).append("ms")
                .append(" alloc=").append(bytes(sample.allocatedBytes()))
                .append(" thread=").append(sample.threadName());
            if (sample.detail() != null && !sample.detail().isBlank()) {
                out.append(" details={").append(sample.detail()).append('}');
            }
        });
        if (copy.isEmpty()) out.append("\n  none");
        return out.toString();
    }

    String runtimeStatus() {
        RuntimeSample now = diagnostics.probe().sample();
        RuntimeSample base = diagnostics.baseline();
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
            .append("\n  physical used=")
            .append(bytes(safeSubtract(now.physicalTotal(), now.physicalFree())))
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

    String diagnosis() {
        List<String> findings = findings();
        StringBuilder out = new StringBuilder("OceanGen automatic diagnosis findings=")
            .append(findings.size());
        if (findings.isEmpty()) {
            return out.append("\n  no suspicious metrics in this window").toString();
        }
        for (String finding : findings) out.append("\n  - ").append(finding);
        return out.toString();
    }

    String histogramStatus() {
        StringBuilder out =
            new StringBuilder("OceanGen timing histograms (bucket upper bounds)");
        for (Stage stage : Stage.values()) {
            appendHistogram(out, "stage " + stage, diagnostics.stageStats(stage));
        }
        for (Phase phase : Phase.values()) {
            appendHistogram(out, "phase " + phase, diagnostics.phaseStats(phase));
        }
        return out.toString();
    }

    String fullReport(long seed) {
        double seconds = diagnostics.elapsedSeconds();
        StringBuilder out = new StringBuilder(16_384);
        out.append("\n================ OceanGen ultra benchmark ================")
            .append("\ngeneratedUtc=").append(Instant.now())
            .append(" windowSec=").append(fmt(seconds))
            .append(" seed=").append(seed)
            .append(" epoch=").append(diagnostics.epochValue())
            .append(" enabled=").append(diagnostics.enabled())
            .append(" counters=approximate-under-concurrency")
            .append(" cpuTime=").append(diagnostics.probe().cpuTimeSupported())
            .append(" threadAllocation=").append(diagnostics.probe().allocationSupported());
        for (Stage stage : Stage.values()) {
            appendTiming(
                out, "stage", stage.name(), diagnostics.stageStats(stage),
                stage.budgetNs(), seconds, -1L
            );
        }
        out.append('\n').append(phaseStatus());
        out.append('\n').append(counterStatus());
        out.append('\n').append(cacheStatus());
        out.append("\nsafety errors=").append(diagnostics.allErrors())
            .append(" seedRaces=").append(diagnostics.seedRaceCount())
            .append(" duplicates=").append(diagnostics.duplicateEntryCount())
            .append(" invalidEnds=").append(diagnostics.invalidEndCount())
            .append(" trackingDropped=").append(diagnostics.droppedTrackingCount())
            .append(" seedResets=").append(diagnostics.seedResetCount());
        out.append('\n').append(runtimeStatus());
        out.append('\n').append(threadStatus());
        out.append('\n').append(activeStatus());
        out.append('\n').append(slowStatus());
        out.append('\n').append(diagnosis());
        out.append('\n').append(histogramStatus());
        out.append("\n===========================================================");
        return out.toString();
    }

    // -------------------------------------------------------------------------
    // CSV
    // -------------------------------------------------------------------------

    String csv(long seed) {
        RuntimeSample now = diagnostics.probe().sample();
        RuntimeSample base = diagnostics.baseline();
        double seconds = diagnostics.elapsedSeconds();
        StringBuilder out = new StringBuilder(16_384);
        out.append("kind,name,metric,value,unit\n");
        csvRow(out, "window", "OceanGen", "seed", Long.toString(seed), "id");
        csvRow(out, "window", "OceanGen", "duration", fmt(seconds), "s");
        csvRow(out, "window", "OceanGen", "epoch", Long.toString(diagnostics.epochValue()), "id");
        csvRow(out, "window", "OceanGen", "enabled",
            Boolean.toString(diagnostics.enabled()), "boolean");
        csvRow(out, "capability", "JVM", "thread_cpu_time",
            Boolean.toString(diagnostics.probe().cpuTimeSupported()), "boolean");
        csvRow(out, "capability", "JVM", "thread_allocation",
            Boolean.toString(diagnostics.probe().allocationSupported()), "boolean");

        for (Stage stage : Stage.values()) {
            appendTimingCsv(out, "stage", stage.name(),
                diagnostics.stageStats(stage), stage.budgetNs(), seconds);
        }
        for (Phase phase : Phase.values()) {
            appendTimingCsv(out, "phase", phase.name(),
                diagnostics.phaseStats(phase), phase.budgetNs(), seconds);
        }
        for (Stage stage : Stage.values()) {
            appendHistogramCsv(out, "stage_histogram", stage.name(),
                diagnostics.stageStats(stage));
        }
        for (Phase phase : Phase.values()) {
            appendHistogramCsv(out, "phase_histogram", phase.name(),
                diagnostics.phaseStats(phase));
        }
        for (CacheId cache : CacheId.values()) {
            CacheStats stats = diagnostics.cacheStats(cache);
            long hits = stats.hitCount();
            long misses = stats.missCount();
            csvRow(out, "cache", cache.name(), "hits", Long.toString(hits), "count");
            csvRow(out, "cache", cache.name(), "misses", Long.toString(misses), "count");
            csvRow(out, "cache", cache.name(), "hit_rate", ratio(hits, hits + misses), "ratio");
            csvRow(out, "cache", cache.name(), "size",
                Integer.toString(stats.currentSize()), "entries");
            csvRow(out, "cache", cache.name(), "limit",
                Integer.toString(stats.sizeLimit()), "entries");
            csvRow(out, "cache", cache.name(), "trims",
                Long.toString(stats.trims.sum()), "count");
            csvRow(out, "cache", cache.name(), "removed",
                Long.toString(stats.removed.sum()), "entries");
            csvRow(out, "cache", cache.name(), "trim_total",
                Long.toString(stats.totalTrimNanos()), "ns");
            csvRow(out, "cache", cache.name(), "timed_trims",
                Long.toString(stats.timedTrims.sum()), "count");
            csvRow(out, "cache", cache.name(), "trim_maximum",
                Long.toString(stats.maxTrimNanos()), "ns");
        }
        for (Counter counter : Counter.values()) {
            csvRow(out, "counter", counter.name(), "total",
                Long.toString(diagnostics.counter(counter)), "count");
        }
        csvRow(out, "safety", "OceanGen", "errors",
            Long.toString(diagnostics.allErrors()), "count");
        csvRow(out, "safety", "OceanGen", "seed_races",
            Long.toString(diagnostics.seedRaceCount()), "count");
        csvRow(out, "safety", "OceanGen", "duplicate_entries",
            Long.toString(diagnostics.duplicateEntryCount()), "count");
        csvRow(out, "safety", "OceanGen", "invalid_ends",
            Long.toString(diagnostics.invalidEndCount()), "count");
        csvRow(out, "safety", "OceanGen", "tracking_dropped",
            Long.toString(diagnostics.droppedTrackingCount()), "count");
        csvRow(out, "safety", "OceanGen", "seed_resets",
            Long.toString(diagnostics.seedResetCount()), "count");
        csvRow(out, "concurrency", "OceanGen", "in_flight",
            Integer.toString(diagnostics.inFlightCount()), "count");
        csvRow(out, "concurrency", "OceanGen", "peak_in_flight",
            Integer.toString(diagnostics.peakInFlightCount()), "count");
        csvRow(out, "concurrency", "OceanGen", "active_tracked",
            Integer.toString(diagnostics.activeCount()), "count");
        for (Map.Entry<String, ThreadStats> entry : diagnostics.threads().entrySet()) {
            ThreadStats stats = entry.getValue();
            csvRow(out, "thread", entry.getKey(), "calls",
                Long.toString(stats.calls.sum()), "count");
            csvRow(out, "thread", entry.getKey(), "wall",
                Long.toString(stats.wallNanos()), "ns");
            csvRow(out, "thread", entry.getKey(), "cpu",
                Long.toString(stats.cpuNs.sum()), "ns");
            csvRow(out, "thread", entry.getKey(), "allocated",
                Long.toString(stats.allocatedBytes.sum()), "bytes");
            csvRow(out, "thread", entry.getKey(), "maximum",
                Long.toString(stats.maxNs.get()), "ns");
            csvRow(out, "thread", entry.getKey(), "errors",
                Long.toString(stats.errors.sum()), "count");
        }
        appendRuntimeCsv(out, now, base);
        for (SlowSample sample : diagnostics.slowCopy()) {
            String name = sample.stage() + "@" + chunk(sample.chunkKey())
                + "@" + sample.capturedMs();
            csvRow(out, "slow", name, "wall", Long.toString(sample.elapsedNs()), "ns");
            csvRow(out, "slow", name, "cpu", Long.toString(sample.cpuNs()), "ns");
            csvRow(out, "slow", name, "allocated",
                Long.toString(sample.allocatedBytes()), "bytes");
            csvRow(out, "slow", name, "thread", sample.threadName(), "name");
            csvRow(out, "slow", name, "detail",
                sample.detail() == null ? "" : sample.detail(), "text");
        }
        int activeIndex = 0;
        long activeNow = System.nanoTime();
        for (Token token : diagnostics.activeTokens()) {
            String name = token.stage() + "@" + chunk(token.chunkKey()) + "@" + activeIndex++;
            csvRow(out, "active", name, "age",
                Long.toString(Math.max(0L, activeNow - token.startedNs())), "ns");
            csvRow(out, "active", name, "thread", token.threadName(), "name");
            csvRow(out, "active", name, "seed", Long.toString(token.seed()), "id");
        }
        return out.toString();
    }

    private static void appendRuntimeCsv(
        StringBuilder out,
        RuntimeSample now,
        RuntimeSample base
    ) {
        csvRow(out, "runtime", "JVM", "heap_used", Long.toString(now.heapUsed()), "bytes");
        csvRow(out, "runtime", "JVM", "heap_committed",
            Long.toString(now.heapCommitted()), "bytes");
        csvRow(out, "runtime", "JVM", "heap_max", Long.toString(now.heapMax()), "bytes");
        csvRow(out, "runtime", "JVM", "non_heap_used",
            Long.toString(now.nonHeapUsed()), "bytes");
        csvRow(out, "runtime", "JVM", "direct_used", Long.toString(now.directUsed()), "bytes");
        csvRow(out, "runtime", "JVM", "direct_capacity",
            Long.toString(now.directCapacity()), "bytes");
        csvRow(out, "runtime", "JVM", "mapped_used", Long.toString(now.mappedUsed()), "bytes");
        csvRow(out, "runtime", "JVM", "gc_count_delta",
            Long.toString(delta(now.gcCount(), base.gcCount())), "count");
        csvRow(out, "runtime", "JVM", "gc_time_delta",
            Long.toString(delta(now.gcTimeMs(), base.gcTimeMs())), "ms");
        csvRow(out, "runtime", "JVM", "process_cpu_delta",
            Long.toString(delta(now.processCpuNs(), base.processCpuNs())), "ns");
        csvRow(out, "runtime", "JVM", "process_cpu_load",
            fmt(now.processCpuLoad()), "ratio");
        csvRow(out, "runtime", "JVM", "system_cpu_load", fmt(now.systemCpuLoad()), "ratio");
        csvRow(out, "runtime", "JVM", "physical_total",
            Long.toString(now.physicalTotal()), "bytes");
        csvRow(out, "runtime", "JVM", "physical_free",
            Long.toString(now.physicalFree()), "bytes");
        csvRow(out, "runtime", "JVM", "virtual_committed",
            Long.toString(now.committedVirtual()), "bytes");
        csvRow(out, "runtime", "JVM", "live_threads",
            Integer.toString(now.liveThreads()), "count");
        csvRow(out, "runtime", "JVM", "daemon_threads",
            Integer.toString(now.daemonThreads()), "count");
        csvRow(out, "runtime", "JVM", "peak_threads",
            Integer.toString(now.peakThreads()), "count");
        csvRow(out, "runtime", "JVM", "loaded_classes",
            Integer.toString(now.loadedClasses()), "count");
        csvRow(out, "runtime", "JVM", "unloaded_classes",
            Long.toString(now.unloadedClasses()), "count");
        csvRow(out, "runtime", "JVM", "jit_time_delta",
            Long.toString(delta(now.compilationTimeMs(), base.compilationTimeMs())), "ms");
        for (Map.Entry<String, RuntimeSample.CollectorSample> entry
                : now.collectors().entrySet()) {
            RuntimeSample.CollectorSample before = base.collectors().get(entry.getKey());
            long count = before == null
                ? entry.getValue().collections()
                : delta(entry.getValue().collections(), before.collections());
            long time = before == null
                ? entry.getValue().timeMs()
                : delta(entry.getValue().timeMs(), before.timeMs());
            csvRow(out, "collector", entry.getKey(), "collections_delta",
                Long.toString(count), "count");
            csvRow(out, "collector", entry.getKey(), "time_delta",
                Long.toString(time), "ms");
        }
    }

    // -------------------------------------------------------------------------
    // Automatic diagnosis
    // -------------------------------------------------------------------------

    /**
     * Heuristics that turn raw numbers into a short list of suspicions.
     *
     * <p>Expensive — walks every MXBean once. Only ever called from
     * {@code /oceangen benchmark diagnose} and the periodic verbose report.</p>
     */
    private List<String> findings() {
        List<String> findings = new ArrayList<>();
        appendSafetyFindings(findings);
        appendStageFindings(findings);
        appendPhaseFindings(findings);
        appendCounterFindings(findings);
        appendCacheFindings(findings);
        appendRuntimeFindings(findings);
        return findings;
    }

    private void appendSafetyFindings(List<String> findings) {
        if (diagnostics.allErrors() > 0L) {
            findings.add("generation errors recorded: " + diagnostics.allErrors());
        }
        if (diagnostics.seedRaceCount() > 0L) {
            findings.add("seed changed while generation was in flight: "
                + diagnostics.seedRaceCount());
        }
        if (diagnostics.duplicateEntryCount() > 0L) {
            findings.add("same stage/chunk entered concurrently: "
                + diagnostics.duplicateEntryCount());
        }
        if (diagnostics.invalidEndCount() > 0L) {
            findings.add("diagnostic begin/end imbalance: " + diagnostics.invalidEndCount());
        }
        if (diagnostics.droppedTrackingCount() > 0L) {
            findings.add("active-operation tracking overflow: "
                + diagnostics.droppedTrackingCount());
        }
    }

    private void appendStageFindings(List<String> findings) {
        boolean allocation = diagnostics.probe().allocationSupported();
        boolean cpu = diagnostics.probe().cpuTimeSupported();
        for (Stage stage : Stage.values()) {
            TimingStats stats = diagnostics.stageStats(stage);
            long calls = stats.callCount();
            if (calls < 16L) continue;
            long average = avg(stats.totalElapsedNs(), calls);
            long p99 = stats.percentile(.99);
            if (average > stage.budgetNs()) {
                findings.add(stage + " average " + ms(average)
                    + "ms exceeds budget " + ms(stage.budgetNs()) + "ms");
            }
            if (p99 > stage.budgetNs() * 3L) {
                findings.add(stage + " unstable tail: p99=" + ms(p99)
                    + "ms, budget=" + ms(stage.budgetNs()) + "ms");
            }
            long allocationSamples = stats.allocationSamples.sum();
            if (allocation && allocationSamples > 0L) {
                long perCall = avg(stats.totalAllocatedBytes.sum(), allocationSamples);
                if (perCall > 1_048_576L) {
                    findings.add(stage + " allocates " + bytes(perCall) + " per call");
                }
                if (stats.maxAllocatedBytes.get() > 2_097_152L) {
                    findings.add(stage + " has a rare allocation spike of "
                        + bytes(stats.maxAllocatedBytes.get()));
                }
            }
            long cpuNs = stats.totalCpuNs.sum();
            long wallNs = stats.totalElapsedNs();
            if (cpu && stats.cpuSamples.sum() > 0L && wallNs > 0L
                    && cpuNs * 100L < wallNs * 45L) {
                findings.add(stage + " CPU/wall=" + ratio(cpuNs, wallNs)
                    + " suggests waiting, contention, or scheduler stalls");
            }
        }
    }

    private void appendPhaseFindings(List<String> findings) {
        for (Phase phase : Phase.values()) {
            TimingStats stats = diagnostics.phaseStats(phase);
            long calls = stats.callCount();
            if (calls < 16L) continue;
            long average = avg(stats.totalElapsedNs(), calls);
            long p99 = stats.percentile(.99);
            if (average > phase.budgetNs()) {
                findings.add(phase + " average " + ms(average)
                    + "ms exceeds phase budget " + ms(phase.budgetNs()) + "ms");
            }
            if (p99 > phase.budgetNs() * 3L) {
                findings.add(phase + " unstable tail: p99=" + ms(p99)
                    + "ms, budget=" + ms(phase.budgetNs()) + "ms");
            }
            // Read the parent BEFORE the child: phases are recorded inside the parent's try
            // block, i.e. strictly before the stage counter is incremented in end(). Sampling
            // the parent first therefore guarantees parentCalls <= childCalls for all work
            // that completed, and a genuine gap is the only thing left that can trip this.
            // Reading them the other way round made concurrent generation report a permanent
            // false positive.
            TimingStats parent = diagnostics.stageStats(phase.parent());
            long parentCalls = parent.callCount();
            long childCalls = stats.callCount();
            if (phase.requiredPerParent()
                && parent.errorCount() == 0L
                && parentCalls >= 16L
                && childCalls < parentCalls) {
                findings.add(phase + " has fewer samples than " + phase.parent()
                    + ": " + childCalls + "/" + parentCalls);
            }
        }
    }

    /**
     * Invariant checks over the terrain tallies.
     *
     * <p>Every comparison samples the side that is written LAST first, so that a concurrent
     * producer can only ever make the invariant look better, never worse. Without that
     * ordering these all fire spuriously while chunks are still generating, because the
     * counters are independent {@code LongAdder}s read at different instants.</p>
     */
    private void appendCounterFindings(List<String> findings) {
        long landAndOcean =
            diagnostics.counter(Counter.COLUMNS_LAND) + diagnostics.counter(Counter.COLUMNS_OCEAN);
        long columns = diagnostics.counter(Counter.COLUMNS_TOTAL);
        if (columns < landAndOcean) {
            findings.add("terrain counter mismatch: total=" + columns
                + ", land+ocean=" + landAndOcean);
        }
        if (diagnostics.counter(Counter.PALMS_PLACED)
                > diagnostics.counter(Counter.PALM_ATTEMPTS)) {
            findings.add("palm counter invariant failed: placed exceeds attempts");
        }
        if (diagnostics.counter(Counter.ACACIAS_PLACED)
                > diagnostics.counter(Counter.ACACIA_ATTEMPTS)) {
            findings.add("acacia counter invariant failed: placed exceeds attempts");
        }
        if (diagnostics.counter(Counter.BOULDER_ORE_WRITES)
                > diagnostics.counter(Counter.BOULDER_BLOCK_WRITES)) {
            findings.add("boulder counter invariant failed: ore writes exceed all writes");
        }
        long beachSearches = diagnostics.counter(Counter.BEACH_SEARCHES);
        long beachSamples = diagnostics.counter(Counter.BEACH_FLOOR_SAMPLES);
        if (beachSearches >= 1_000L && beachSamples > beachSearches * 20L) {
            findings.add("beach detection samples "
                + fmt(beachSamples / (double) beachSearches)
                + " floor positions per uncached search");
        }
    }

    private void appendCacheFindings(List<String> findings) {
        for (CacheId cache : CacheId.values()) {
            CacheStats stats = diagnostics.cacheStats(cache);
            long hits = stats.hitCount();
            long misses = stats.missCount();
            long total = hits + misses;
            if (total >= 1000L && hits * 100L < total * 70L) {
                findings.add(cache + " cache hit rate is low: " + percent(hits, total));
            }
            if (stats.maxTrimNanos() > 2_000_000L) {
                findings.add(cache + " cache eviction pauses for up to "
                    + ms(stats.maxTrimNanos()) + "ms");
            }
            if (stats.totalTrimNanos() > 0L
                    && stats.totalTrimNanos() > diagnostics.elapsedSeconds() * 10_000_000.0) {
                findings.add(cache + " cache eviction consumes "
                    + ms(stats.totalTrimNanos()) + "ms in this window");
            }
            if (stats.sizeLimit() > 0 && stats.currentSize() > stats.sizeLimit()) {
                findings.add(cache + " cache exceeds its limit: "
                    + stats.currentSize() + "/" + stats.sizeLimit());
            }
        }
    }

    private void appendRuntimeFindings(List<String> findings) {
        RuntimeSample now = diagnostics.probe().sample();
        RuntimeSample base = diagnostics.baseline();
        long wallMs = Math.max(1L, now.wallMs() - base.wallMs());
        long gcMs = delta(now.gcTimeMs(), base.gcTimeMs());
        if (gcMs >= 0L && gcMs * 100L > wallMs * 5L) {
            findings.add("GC consumed " + percent(gcMs, wallMs) + " of benchmark wall time");
        }
        if (now.heapMax() > 0L && now.heapUsed() * 100L > now.heapMax() * 85L) {
            findings.add("heap usage is above 85%: "
                + bytes(now.heapUsed()) + "/" + bytes(now.heapMax()));
        }
        if (now.systemCpuLoad() >= 0.95) {
            findings.add("system CPU is saturated at report time: "
                + load(now.systemCpuLoad()));
        }
        if (now.physicalTotal() > 0L
            && now.physicalFree() >= 0L
            && now.physicalFree() * 100L < now.physicalTotal() * 10L) {
            findings.add("system physical memory is below 10% free: "
                + bytes(now.physicalFree()) + "/" + bytes(now.physicalTotal()));
        }
        Collection<Token> tokens = diagnostics.activeTokens();
        if (!tokens.isEmpty()) {
            long oldestStart = Long.MAX_VALUE;
            for (Token token : tokens) oldestStart = Math.min(oldestStart, token.startedNs());
            long oldestNs = oldestStart == Long.MAX_VALUE ? 0L : System.nanoTime() - oldestStart;
            if (oldestNs > 5_000_000_000L) {
                findings.add("an operation has been active for " + ms(oldestNs)
                    + "ms; inspect 'benchmark active'");
            }
        }
    }

    // -------------------------------------------------------------------------
    // Fragment builders
    // -------------------------------------------------------------------------

    private static void appendTiming(
        StringBuilder out,
        String kind,
        String name,
        TimingStats stats,
        long budgetNs,
        double seconds,
        long parentNs
    ) {
        long calls = stats.callCount();
        long totalNs = stats.totalElapsedNs();
        long cpuNs = stats.totalCpuNs.sum();
        long allocated = stats.totalAllocatedBytes.sum();
        out.append("\n").append(kind).append(' ').append(name)
            .append(" calls=").append(calls)
            .append(" rate=").append(fmt(calls / seconds)).append("/s")
            .append(" avg=").append(ms(totalNs, calls)).append("ms")
            .append(" min=").append(ms(stats.observedMinNs())).append("ms")
            .append(" p50=").append(ms(stats.percentile(.50))).append("ms")
            .append(" p90=").append(ms(stats.percentile(.90))).append("ms")
            .append(" p95=").append(ms(stats.percentile(.95))).append("ms")
            .append(" p99=").append(ms(stats.percentile(.99))).append("ms")
            .append(" p99.9=").append(ms(stats.percentile(.999))).append("ms")
            .append(" max=").append(ms(stats.maxNs.get())).append("ms")
            .append(" stddev=").append(fmt(stats.stddevMs())).append("ms")
            .append(" budget=").append(ms(budgetNs)).append("ms")
            .append(" overBudget=").append(stats.overBudget.sum())
            .append('(').append(percent(stats.overBudget.sum(), calls)).append(')')
            .append(" errors=").append(stats.errorCount());
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

    private static void appendTimingCsv(
        StringBuilder out,
        String kind,
        String name,
        TimingStats stats,
        long budgetNs,
        double seconds
    ) {
        long calls = stats.callCount();
        csvRow(out, kind, name, "calls", Long.toString(calls), "count");
        csvRow(out, kind, name, "rate", fmt(calls / seconds), "calls_per_s");
        csvRow(out, kind, name, "total", Long.toString(stats.totalElapsedNs()), "ns");
        csvRow(out, kind, name, "average",
            Long.toString(avg(stats.totalElapsedNs(), calls)), "ns");
        csvRow(out, kind, name, "minimum", Long.toString(stats.observedMinNs()), "ns");
        csvRow(out, kind, name, "p50", Long.toString(stats.percentile(.50)), "ns");
        csvRow(out, kind, name, "p90", Long.toString(stats.percentile(.90)), "ns");
        csvRow(out, kind, name, "p95", Long.toString(stats.percentile(.95)), "ns");
        csvRow(out, kind, name, "p99", Long.toString(stats.percentile(.99)), "ns");
        csvRow(out, kind, name, "p999", Long.toString(stats.percentile(.999)), "ns");
        csvRow(out, kind, name, "maximum", Long.toString(stats.maxNs.get()), "ns");
        csvRow(out, kind, name, "stddev", fmt(stats.stddevMs()), "ms");
        csvRow(out, kind, name, "budget", Long.toString(budgetNs), "ns");
        csvRow(out, kind, name, "over_budget", Long.toString(stats.overBudget.sum()), "count");
        csvRow(out, kind, name, "errors", Long.toString(stats.errorCount()), "count");
        csvRow(out, kind, name, "cpu_samples", Long.toString(stats.cpuSamples.sum()), "count");
        csvRow(out, kind, name, "cpu_total", Long.toString(stats.totalCpuNs.sum()), "ns");
        csvRow(out, kind, name, "cpu_maximum", Long.toString(stats.maxCpuNs.get()), "ns");
        csvRow(out, kind, name, "allocation_samples",
            Long.toString(stats.allocationSamples.sum()), "count");
        csvRow(out, kind, name, "allocated_total",
            Long.toString(stats.totalAllocatedBytes.sum()), "bytes");
        csvRow(out, kind, name, "allocated_maximum",
            Long.toString(stats.maxAllocatedBytes.get()), "bytes");
    }

    private static void appendCache(StringBuilder out, CacheId cache, CacheStats stats) {
        long hits = stats.hitCount();
        long misses = stats.missCount();
        int limit = stats.sizeLimit();
        out.append("\ncache ").append(cache)
            .append(" hitRate=").append(percent(hits, hits + misses))
            .append(" hits=").append(hits)
            .append(" misses=").append(misses)
            .append(" size=").append(stats.currentSize()).append('/').append(limit)
            .append(" load=").append(percent(stats.currentSize(), limit))
            .append(" trims=").append(stats.trims.sum())
            .append(" removed=").append(stats.removed.sum())
            .append(" trimAvg=")
            .append(stats.timedTrims.sum() == 0L
                ? "n/a"
                : ms(stats.totalTrimNanos(), stats.timedTrims.sum()) + "ms")
            .append(" trimMax=").append(ms(stats.maxTrimNanos())).append("ms")
            .append(" trimTotal=").append(ms(stats.totalTrimNanos())).append("ms");
    }

    private static void appendThread(StringBuilder out, String name, ThreadStats stats) {
        long calls = stats.calls.sum();
        long wall = stats.wallNanos();
        out.append("\n  ").append(name)
            .append(" calls=").append(calls)
            .append(" wall=").append(ms(wall)).append("ms")
            .append(" avg=").append(ms(wall, calls)).append("ms")
            .append(" max=").append(ms(stats.maxNs.get())).append("ms")
            .append(" cpu=").append(ms(stats.cpuNs.sum())).append("ms")
            .append(" alloc=").append(bytes(stats.allocatedBytes.sum()))
            .append(" errors=").append(stats.errors.sum());
    }

    private static void appendCollectors(
        StringBuilder out,
        RuntimeSample now,
        RuntimeSample base
    ) {
        for (Map.Entry<String, RuntimeSample.CollectorSample> entry
                : now.collectors().entrySet()) {
            RuntimeSample.CollectorSample before = base.collectors().get(entry.getKey());
            long count = before == null
                ? entry.getValue().collections()
                : delta(entry.getValue().collections(), before.collections());
            long time = before == null
                ? entry.getValue().timeMs()
                : delta(entry.getValue().timeMs(), before.timeMs());
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
        long calls = stats.callCount();
        out.append("\n").append(name).append(" samples=").append(calls);
        if (calls == 0L) return;
        long lower = 0L;
        for (int i = 0; i < TimingStats.HISTOGRAM_BUCKETS; i++) {
            long count = stats.bucketCount(i);
            if (count > 0L) {
                out.append("\n  (").append(ms(lower)).append(',')
                    .append(ms(TimingStats.bucketUpperNs(i))).append("]ms=")
                    .append(count).append(" (").append(percent(count, calls)).append(')');
            }
            lower = TimingStats.bucketUpperNs(i);
        }
    }

    private static void appendHistogramCsv(
        StringBuilder out,
        String kind,
        String name,
        TimingStats stats
    ) {
        for (int i = 0; i < TimingStats.HISTOGRAM_BUCKETS; i++) {
            long count = stats.bucketCount(i);
            if (count == 0L) continue;
            String upper = TimingStats.bucketUpperNs(i) == Long.MAX_VALUE
                ? "infinity"
                : Long.toString(TimingStats.bucketUpperNs(i));
            csvRow(out, kind, name, "le_" + upper + "ns", Long.toString(count), "count");
        }
    }
}
