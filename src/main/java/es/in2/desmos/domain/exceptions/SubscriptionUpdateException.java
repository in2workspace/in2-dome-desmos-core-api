package es.in2.desmos.domain.exceptions;

/**
 * Updating an existing subscription (a PATCH with its full definition) failed. Unlike creating a subscription,
 * sending it again has no side effect, so it can be retried safely.
 */
public class SubscriptionUpdateException extends SubscriptionCreationException {

    public SubscriptionUpdateException(String message, Throwable cause) {
        super(message, cause);
    }
}
