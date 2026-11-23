package es.in2.desmos.domain.utils;

import es.in2.desmos.domain.models.AuditRecord;
import org.slf4j.Logger;

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
    public static final String AUDIT = "[audit]";
    public static final String SUBSCRIBE = "[subscribe]";
    public static final String BACKFILL = "[backfill]";

    private static final int LOGGED_HASH_LENGTH = 12;

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

    /**
     * One INFO line per saved audit record: what the node recorded for which entity and from which flow. Shared by the
     * single saves of the notifications and the batch writes of the P2P sync, so both write the same line. The entity
     * hash is shortened; it is enough to tell whether repeated records are about the same content.
     */
    public static void logAuditSaved(Logger log, String processId, AuditRecord auditRecord, String source) {
        log.info("{} ProcessID: {} - {} {} -> {} {} (source={}, entityHash={})",
                AUDIT, processId, auditRecord.getEntityType(), auditRecord.getEntityId(), auditRecord.getStatus(),
                auditRecord.getTrader(), source, shortHash(auditRecord.getEntityHash()));
    }

    private static String shortHash(String hash) {
        if (hash == null || hash.isBlank()) {
            return "-";
        }
        return hash.length() > LOGGED_HASH_LENGTH ? hash.substring(0, LOGGED_HASH_LENGTH) : hash;
    }
}
