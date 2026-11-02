package es.in2.desmos.domain.services.broker;

import ch.qos.logback.classic.Level;
import es.in2.desmos.domain.services.broker.impl.NotificationStats;
import es.in2.desmos.infrastructure.configs.properties.LoggingProperties;
import es.in2.desmos.testutils.LogCapture;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationStatsTests {

    private final NotificationStats notificationStats = new NotificationStats(new LoggingProperties(null, null, null));

    @Test
    void itShouldLogCountersOfThePeriodAndResetThem() {
        notificationStats.received();
        notificationStats.received();
        notificationStats.received();
        notificationStats.enqueued();
        notificationStats.skippedSelfGenerated();
        notificationStats.skippedByPolicy();

        try (LogCapture logCapture = LogCapture.of(NotificationStats.class)) {
            logSummary();
            logSummary();

            assertThat(logCapture.messages(Level.INFO)).containsExactly(
                    "[notif] last 60s: received=3 enqueued=1 skipped_self=1 skipped_policy=1 errors=0");
        }
    }

    @Test
    void itShouldNotLogWhenThereWasNoActivity() {
        try (LogCapture logCapture = LogCapture.of(NotificationStats.class)) {
            logSummary();

            assertThat(logCapture.messages(Level.INFO)).isEmpty();
        }
    }

    @Test
    void itShouldLogWhenThereWereOnlyErrors() {
        notificationStats.error();

        try (LogCapture logCapture = LogCapture.of(NotificationStats.class)) {
            logSummary();

            assertThat(logCapture.messages(Level.INFO)).containsExactly(
                    "[notif] last 60s: received=0 enqueued=0 skipped_self=0 skipped_policy=0 errors=1");
        }
    }

    private void logSummary() {
        ReflectionTestUtils.invokeMethod(notificationStats, "logSummary");
    }

    @Test
    void itShouldNotScheduleTheSummaryWhenTheIntervalIsZeroOrNegative() {
        for (Duration interval : new Duration[]{Duration.ZERO, Duration.ofSeconds(-1)}) {
            NotificationStats stats = new NotificationStats(new LoggingProperties(null, null, interval));

            ReflectionTestUtils.invokeMethod(stats, "startSummary");

            assertThat((Boolean) ReflectionTestUtils.invokeMethod(stats, "isSummaryScheduled")).isFalse();
        }
    }

    @Test
    void itShouldScheduleTheSummaryWhenTheIntervalIsPositive() {
        NotificationStats stats = new NotificationStats(new LoggingProperties(null, null, Duration.ofHours(1)));

        ReflectionTestUtils.invokeMethod(stats, "startSummary");
        try {
            assertThat((Boolean) ReflectionTestUtils.invokeMethod(stats, "isSummaryScheduled")).isTrue();
        } finally {
            ReflectionTestUtils.invokeMethod(stats, "stopSummary");
        }
    }
}
