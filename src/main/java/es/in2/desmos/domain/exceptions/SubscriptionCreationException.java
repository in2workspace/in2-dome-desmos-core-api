package es.in2.desmos.domain.exceptions;

public class SubscriptionCreationException extends RuntimeException {

    public SubscriptionCreationException(String message) {
        super(message);
    }

    public SubscriptionCreationException(String message, Throwable cause) {
        super(message, cause);
    }

}
