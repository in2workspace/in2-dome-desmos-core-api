package es.in2.desmos.domain.utils;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class LogUtilsTests {

    @Test
    void itShouldKeepShortTextsAndTruncateLongOnes() {
        assertThat(LogUtils.truncate("short", 10)).isEqualTo("short");
        assertThat(LogUtils.truncate(null, 10)).isNull();
        assertThat(LogUtils.truncate("0123456789abcdef", 10)).isEqualTo("0123456789... (truncated, 16 chars)");
    }

    @Test
    void itShouldFormatDurationsForHumans() {
        assertThat(LogUtils.formatDuration(Duration.ofMillis(850))).isEqualTo("850ms");
        assertThat(LogUtils.formatDuration(Duration.ofMillis(12_340))).isEqualTo("12.3s");
        assertThat(LogUtils.formatDuration(Duration.ofSeconds(245))).isEqualTo("4m05s");
    }
}
