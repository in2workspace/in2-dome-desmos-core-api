package es.in2.desmos.domain.services.broker.impl;

import es.in2.desmos.infrastructure.configs.properties.LoggingProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.concurrent.atomic.LongAdder;

import static es.in2.desmos.domain.utils.LogUtils.NOTIF;

/**
 * Counts what happens to broker notifications and writes a periodic "[notif] last ..." summary at INFO,
 * only when there was activity in the period.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationStats {

    private final LoggingProperties loggingProperties;

    private final LongAdder received = new LongAdder();
    private final LongAdder enqueued = new LongAdder();
    private final LongAdder skippedSelf = new LongAdder();
    private final LongAdder skippedPolicy = new LongAdder();
    private final LongAdder errors = new LongAdder();

    private Disposable summaryTask;

    // A zero or negative interval disables the summary; Reactor would otherwise re-run the task in a tight loop.
    @PostConstruct
    void startSummary() {
        Duration interval = loggingProperties.notificationSummaryInterval();
        if (interval.isZero() || interval.isNegative()) {
            log.info("{} Periodic notification summary disabled (interval {})", NOTIF, interval);
            return;
        }
        summaryTask = Flux.interval(interval, interval).subscribe(tick -> logSummary());
    }

    boolean isSummaryScheduled() {
        return summaryTask != null && !summaryTask.isDisposed();
    }

    @PreDestroy
    void stopSummary() {
        if (summaryTask != null) {
            summaryTask.dispose();
        }
    }

    public void received() {
        received.increment();
    }

    public void enqueued() {
        enqueued.increment();
    }

    public void skippedSelfGenerated() {
        skippedSelf.increment();
    }

    public void skippedByPolicy() {
        skippedPolicy.increment();
    }

    public void error() {
        errors.increment();
    }

    void logSummary() {
        long receivedCount = received.sumThenReset();
        long enqueuedCount = enqueued.sumThenReset();
        long skippedSelfCount = skippedSelf.sumThenReset();
        long skippedPolicyCount = skippedPolicy.sumThenReset();
        long errorCount = errors.sumThenReset();
        if (receivedCount + enqueuedCount + skippedSelfCount + skippedPolicyCount + errorCount == 0) {
            return;
        }
        log.info("{} last {}s: received={} enqueued={} skipped_self={} skipped_policy={} errors={}",
                NOTIF, loggingProperties.notificationSummaryInterval().toSeconds(), receivedCount, enqueuedCount,
                skippedSelfCount, skippedPolicyCount, errorCount);
    }
}
