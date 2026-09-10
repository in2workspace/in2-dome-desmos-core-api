package es.in2.desmos.support;

import es.in2.desmos.domain.models.AuditRecord;
import es.in2.desmos.domain.repositories.AuditRecordRepository;
import org.awaitility.core.ConditionTimeoutException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.Supplier;

import static org.awaitility.Awaitility.await;

/**
 * {@code QueueService} is a unicast sink: an event posted before a node's own queue consumer has
 * subscribed (which only happens once that node's {@code ApplicationRunner} startup sequence
 * finishes) is dropped for good, not merely delayed. Both the publish path and the DLT-subscribe
 * path need the same shape of workaround: give one attempt a short window to progress, and if it
 * didn't (because the consumer wasn't ready yet), let an outer loop try again -- rather than
 * throwing away partial progress on an inner timeout.
 * <p>
 * Callers differ on what a "retry" means, so this deliberately stays low-level rather than
 * baking in a single retry policy: a fresh-entity-per-attempt caller (the publish flow, which
 * asserts an exact trail shape and can't tolerate duplicate RECEIVED rows from re-posting the
 * same notification) and a same-entity-per-attempt caller (the subscribe flow's "publish on node
 * A" step, which only needs the latest row to be PUBLISHED/PRODUCER and is unaffected by
 * duplicates) both build their outer retry loop out of {@link #pollWithPartialFallback}.
 */
public final class QueueReadiness {

    private QueueReadiness() {
    }

    /** An audit trail for an entity, oldest first. Never {@code null}, unlike a raw repository read. */
    public static List<AuditRecord> trailFor(AuditRecordRepository repository, String entityId) {
        List<AuditRecord> trail = repository.findByEntityId(entityId).collectList().block(Duration.ofSeconds(5));
        List<AuditRecord> mutable = trail == null ? new ArrayList<>() : new ArrayList<>(trail);
        mutable.sort(Comparator.comparing(AuditRecord::getCreatedAt));
        return mutable;
    }

    /**
     * Polls {@code probe} for up to {@code innerBudget} until {@code success}; on timeout,
     * returns the last-seen value instead of throwing, so a caller retrying a fresh attempt
     * doesn't lose track of a lost (pre-readiness) attempt's partial progress.
     */
    public static <T> T pollWithPartialFallback(Duration innerBudget, Duration innerPoll,
                                                 Supplier<T> probe, Predicate<T> success) {
        try {
            return await().atMost(innerBudget).pollInterval(innerPoll).until(probe::get, success);
        } catch (ConditionTimeoutException e) {
            return probe.get();
        }
    }
}
