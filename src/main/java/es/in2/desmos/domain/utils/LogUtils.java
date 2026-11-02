package es.in2.desmos.domain.utils;

import java.time.Duration;
import java.util.Locale;

/**
 * Prefixes and helpers shared by Desmos log lines. Every line starts with an area prefix so it can be
 * filtered with grep, e.g. {@code grep '\[p2p-sync\]'}.
 */
public final class LogUtils {

    public static final String P2P_SYNC = "[p2p-sync]";
    public static final String NOTIF = "[notif]";
    public static final String PUBLISH = "[publish]";
    public static final String INIT = "[init]";

    private LogUtils() {
        throw new IllegalStateException("Utility class");
    }

    public static String truncate(String text, int maxChars) {
        if (text == null || text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, maxChars) + "... (truncated, " + text.length() + " chars)";
    }

    /**
     * Formats a duration for humans: "850ms", "12.3s", "4m05s".
     */
    public static String formatDuration(Duration duration) {
        long millis = duration.toMillis();
        if (millis < 1000) {
            return millis + "ms";
        }
        if (millis < 60_000) {
            return String.format(Locale.ROOT, "%.1fs", millis / 1000.0);
        }
        return String.format(Locale.ROOT, "%dm%02ds", millis / 60_000, (millis % 60_000) / 1000);
    }

    public static String formatDurationSince(long startNanos) {
        return formatDuration(Duration.ofNanos(System.nanoTime() - startNanos));
    }
}
