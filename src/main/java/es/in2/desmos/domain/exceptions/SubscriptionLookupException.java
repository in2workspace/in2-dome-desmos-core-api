package es.in2.desmos.domain.exceptions;

import java.time.Duration;

/**
 * Listing the existing subscriptions (the read-only step before creating one) failed. Unlike creating a
 * subscription, it can be retried safely.
 */
public class SubscriptionLookupException extends SubscriptionCreationException {

    /**
     * Bounds the lookup so that a dependency that hangs fails it here, as a lookup that can be retried, before
     * the startup step timeout cuts the whole step without saying which request was slow.
     */
    public static final Duration TIMEOUT = Duration.ofSeconds(60);

    public SubscriptionLookupException(String message, Throwable cause) {
        super(message, cause);
    }
}
