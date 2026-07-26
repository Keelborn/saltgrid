package com.jokerdayn.swworldgencore.diagnostics;

import java.lang.management.BufferPoolMXBean;
import java.lang.management.ClassLoadingMXBean;
import java.lang.management.CompilationMXBean;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;

/**
 * All JVM introspection in one place.
 *
 * <p>Every method here walks platform MXBeans and costs microseconds to milliseconds, so
 * none of it may be called from a generation hot path — only from report rendering and
 * from window baselines. Per-thread CPU and allocation probes are the cheap exception and
 * are still gated by {@link Stage#detailed()} at the call site.</p>
 */
public final class RuntimeProbe {

    private final MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
    private final ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
    private final com.sun.management.ThreadMXBean allocationBean;
    private final boolean cpuTimeSupported;
    private final boolean allocationSupported;

    public RuntimeProbe() {
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
    }

    public boolean cpuTimeSupported() {
        return cpuTimeSupported;
    }

    public boolean allocationSupported() {
        return allocationSupported;
    }

    /** CPU time of the calling thread, or {@code -1} when unavailable. */
    public long currentCpuTime() {
        if (!cpuTimeSupported) return -1L;
        try {
            return threadBean.getCurrentThreadCpuTime();
        } catch (RuntimeException ignored) {
            return -1L;
        }
    }

    /** Bytes allocated by the calling thread so far, or {@code -1} when unavailable. */
    public long currentAllocatedBytes() {
        if (!allocationSupported || allocationBean == null) return -1L;
        try {
            return allocationBean.getThreadAllocatedBytes(Thread.currentThread().threadId());
        } catch (RuntimeException ignored) {
            return -1L;
        }
    }

    public int threadCount() {
        return threadBean.getThreadCount();
    }

    /** Histogram of every live JVM thread's state — diagnoses pool starvation. */
    public Map<Thread.State, Integer> threadStateHistogram() {
        EnumMap<Thread.State, Integer> states = new EnumMap<>(Thread.State.class);
        ThreadInfo[] infos = threadBean.getThreadInfo(threadBean.getAllThreadIds(), 0);
        for (ThreadInfo info : infos) {
            if (info != null) states.merge(info.getThreadState(), 1, Integer::sum);
        }
        return states;
    }

    public RuntimeSample sample() {
        MemoryUsage heap = memoryBean.getHeapMemoryUsage();
        MemoryUsage nonHeap = memoryBean.getNonHeapMemoryUsage();

        long directUsed = 0L;
        long directCapacity = 0L;
        long mappedUsed = 0L;
        for (BufferPoolMXBean pool : ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class)) {
            if ("direct".equals(pool.getName())) {
                directUsed = pool.getMemoryUsed();
                directCapacity = pool.getTotalCapacity();
            } else if (pool.getName().startsWith("mapped")) {
                mappedUsed += Math.max(0L, pool.getMemoryUsed());
            }
        }

        long gcCount = 0L;
        long gcTime = 0L;
        Map<String, RuntimeSample.CollectorSample> collectorSamples = new HashMap<>();
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            long count = Math.max(0L, gc.getCollectionCount());
            long time = Math.max(0L, gc.getCollectionTime());
            gcCount += count;
            gcTime += time;
            collectorSamples.put(gc.getName(), new RuntimeSample.CollectorSample(count, time));
        }

        long processCpu = -1L;
        double processLoad = -1.0;
        double systemLoad = -1.0;
        long physicalTotal = -1L;
        long physicalFree = -1L;
        long virtual = -1L;
        if (ManagementFactory.getOperatingSystemMXBean()
                instanceof com.sun.management.OperatingSystemMXBean os) {
            processCpu = os.getProcessCpuTime();
            processLoad = os.getProcessCpuLoad();
            systemLoad = os.getCpuLoad();
            physicalTotal = os.getTotalMemorySize();
            physicalFree = os.getFreeMemorySize();
            virtual = os.getCommittedVirtualMemorySize();
        }

        ClassLoadingMXBean classes = ManagementFactory.getClassLoadingMXBean();
        CompilationMXBean compilation = ManagementFactory.getCompilationMXBean();
        long compilationTime =
            compilation != null && compilation.isCompilationTimeMonitoringSupported()
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
}
