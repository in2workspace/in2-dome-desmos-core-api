package es.in2.desmos.domain.utils;

import es.in2.desmos.domain.exceptions.*;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeoutException;

/**
 * Failures that happen in normal operation: a broker or a peer node rejecting a request, being unreachable or too
 * slow, or sending data that does not verify. They are logged in one line, without stack trace; any other failure
 * is a bug and keeps its stack trace.
 */
public final class ExpectedFailures {

    private static final List<Class<? extends Throwable>> EXPECTED_FAILURE_TYPES = List.of(
            BrokerRequestRejectedException.class,
            WebClientResponseException.class,
            WebClientRequestException.class,
            TimeoutException.class,
            DiscoverySyncException.class,
            EntitySyncException.class,
            InvalidSyncResponseException.class,
            HashLinkException.class);

    private ExpectedFailures() {
        throw new IllegalStateException("Utility class");
    }

    // Looks through the causes too: an exhausted retry wraps the failure that was retried
    public static boolean isExpected(Throwable error) {
        return expectedCause(error).isPresent();
    }

    public static String describe(Throwable error) {
        Throwable failure = expectedCause(error).orElse(error);
        if (failure instanceof BrokerRequestRejectedException rejection) {
            return rejection.getStatus() + " " + rejection.getDetail();
        }
        return failure.getMessage() != null ? failure.getMessage() : failure.getClass().getSimpleName();
    }

    private static Optional<Throwable> expectedCause(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            Throwable current = cause;
            if (EXPECTED_FAILURE_TYPES.stream().anyMatch(type -> type.isInstance(current))) {
                return Optional.of(current);
            }
            if (cause.getCause() == cause) {
                break;
            }
        }
        return Optional.empty();
    }

}
