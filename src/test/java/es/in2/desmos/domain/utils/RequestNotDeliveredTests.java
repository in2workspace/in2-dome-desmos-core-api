package es.in2.desmos.domain.utils;

import io.netty.channel.ConnectTimeoutException;
import io.netty.handler.timeout.ReadTimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.net.ConnectException;
import java.net.URI;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

class RequestNotDeliveredTests {

    private static final URI URI_TO_SERVICE = URI.create("http://service:8080");

    @Test
    void itShouldDetectThatTheConnectionCouldNotBeEstablished() {
        assertThat(ApplicationUtils.isRequestNotDelivered(wrapped(new ConnectException("Connection refused")))).isTrue();
        assertThat(ApplicationUtils.isRequestNotDelivered(wrapped(new ConnectTimeoutException("connection timed out")))).isTrue();
        assertThat(ApplicationUtils.isRequestNotDelivered(
                new IllegalStateException("adapter error", wrapped(new ConnectException("Connection refused"))))).isTrue();
    }

    @Test
    void itShouldNotAssumeNonDeliveryWhenTheRequestMayHaveArrived() {
        assertThat(ApplicationUtils.isRequestNotDelivered(wrapped(ReadTimeoutException.INSTANCE))).isFalse();
        assertThat(ApplicationUtils.isRequestNotDelivered(new TimeoutException("no answer"))).isFalse();
        assertThat(ApplicationUtils.isRequestNotDelivered(new IllegalStateException("boom"))).isFalse();
    }

    @Test
    void itShouldOnlyConsiderSafeToResendWhatCertainlyWasNotProcessed() {
        assertThat(ApplicationUtils.isSafeToResend(wrapped(new ConnectException("Connection refused")))).isTrue();
        assertThat(ApplicationUtils.isSafeToResend(new IllegalStateException("adapter error",
                WebClientResponseException.create(503, "Service Unavailable", new HttpHeaders(), new byte[0], null)))).isTrue();
        assertThat(ApplicationUtils.isSafeToResend(
                WebClientResponseException.create(500, "Internal Server Error", new HttpHeaders(), new byte[0], null))).isFalse();
        assertThat(ApplicationUtils.isSafeToResend(wrapped(ReadTimeoutException.INSTANCE))).isFalse();
    }

    @Test
    void itShouldLookThroughTheCauseChainAndStopOnACycle() {
        IllegalStateException first = new IllegalStateException("first");
        IllegalArgumentException second = new IllegalArgumentException("second", first);
        first.initCause(second);

        assertThat(ApplicationUtils.anyCause(new RuntimeException("top", second), IllegalStateException.class::isInstance)).isTrue();
        assertThat(ApplicationUtils.anyCause(second, TimeoutException.class::isInstance)).isFalse();
        assertThat(ApplicationUtils.anyCause(null, error -> true)).isFalse();
    }

    private static WebClientRequestException wrapped(Throwable cause) {
        return new WebClientRequestException(cause, HttpMethod.POST, URI_TO_SERVICE, new HttpHeaders());
    }
}
