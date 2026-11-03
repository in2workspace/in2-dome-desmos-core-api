package es.in2.desmos.domain.utils;

import es.in2.desmos.domain.exceptions.BrokerRequestRejectedException;
import es.in2.desmos.domain.exceptions.EntitySyncException;
import org.junit.jupiter.api.Test;
import reactor.core.Exceptions;

import static org.assertj.core.api.Assertions.assertThat;

class ExpectedFailuresTests {

    @Test
    void itShouldTreatABrokerRejectionAsExpectedAndDescribeItWithStatusAndDetail() {
        var rejection = new BrokerRequestRejectedException("{\"detail\":\"Duplicated datasetId\"}", 400, "Duplicated datasetId");

        assertThat(ExpectedFailures.isExpected(rejection)).isTrue();
        assertThat(ExpectedFailures.describe(rejection)).isEqualTo("400 Duplicated datasetId");
    }

    @Test
    void itShouldFindAnExpectedFailureWrappedByAnExhaustedRetry() {
        var exhausted = Exceptions.retryExhausted("Retries exhausted: 3/3", new EntitySyncException("Peer node unreachable"));

        assertThat(ExpectedFailures.isExpected(exhausted)).isTrue();
        assertThat(ExpectedFailures.describe(exhausted)).isEqualTo("Peer node unreachable");
    }

    @Test
    void itShouldNotTreatABugAsExpected() {
        var bug = new NullPointerException("entity is null");

        assertThat(ExpectedFailures.isExpected(bug)).isFalse();
        assertThat(ExpectedFailures.describe(bug)).isEqualTo("entity is null");
    }

}
