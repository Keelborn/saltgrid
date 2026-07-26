package com.jokerdayn.swworldgencore.diagnostics;

import java.util.Locale;

/**
 * Number and text rendering shared by the plain-text and CSV reports.
 *
 * <p>By convention a negative input means "not measurable on this platform" and renders
 * as {@code n/a}, so an unsupported MXBean never masquerades as a real zero.</p>
 */
public final class DiagnosticsFormat {

    private DiagnosticsFormat() {}

    public static long avg(long total, long calls) {
        return calls == 0L ? 0L : total / calls;
    }

    /** Renders a packed {@code ChunkPos} key, or {@code n/a} for non-chunk stages. */
    public static String chunk(long key) {
        return key == Long.MIN_VALUE ? "n/a" : "[" + (int) key + "," + (int) (key >>> 32) + "]";
    }

    public static String ms(long ns) {
        return fmt(ns / 1_000_000.0);
    }

    public static String ms(long ns, long calls) {
        return ms(avg(ns, calls));
    }

    public static String msOrNa(long ns) {
        return ns < 0L ? "n/a" : ms(ns);
    }

    public static String fmt(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }

    public static String ratio(long part, long total) {
        return part < 0L || total <= 0L ? "n/a" : fmt(part / (double) total);
    }

    public static String percent(long part, long total) {
        return part < 0L || total <= 0L ? "n/a" : fmt(part * 100.0 / total) + '%';
    }

    public static String load(double value) {
        return value < 0.0 ? "n/a" : fmt(value * 100.0) + '%';
    }

    public static long safeSubtract(long total, long free) {
        return total < 0L || free < 0L ? -1L : Math.max(0L, total - free);
    }

    /** Difference between two cumulative counters, propagating unavailability. */
    public static long delta(long value, long base) {
        return value < 0L || base < 0L ? -1L : Math.max(0L, value - base);
    }

    /** Difference between two per-thread probes, propagating unavailability. */
    public static long deltaMetric(long current, long started) {
        return current < 0L || started < 0L ? -1L : Math.max(0L, current - started);
    }

    public static String bytes(long value) {
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

    /**
     * RFC 4180 field quoting.
     *
     * <p>A bare carriage return also has to trigger quoting: thread names and the free-form
     * detail strings are attacker-adjacent enough that a lone CR would otherwise split the
     * row when the CSV is read back on a platform that treats CR as a line terminator.</p>
     */
    public static String csv(String value) {
        if (value.indexOf(',') < 0
            && value.indexOf('"') < 0
            && value.indexOf('\n') < 0
            && value.indexOf('\r') < 0) {
            return value;
        }
        return '"' + value.replace("\"", "\"\"") + '"';
    }

    public static void csvRow(
        StringBuilder out,
        String kind,
        String name,
        String metric,
        String value,
        String unit
    ) {
        out.append(csv(kind)).append(',')
            .append(csv(name)).append(',')
            .append(csv(metric)).append(',')
            .append(csv(value)).append(',')
            .append(csv(unit)).append('\n');
    }
}
