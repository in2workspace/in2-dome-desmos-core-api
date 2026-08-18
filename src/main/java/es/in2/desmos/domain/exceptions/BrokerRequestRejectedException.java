package es.in2.desmos.domain.exceptions;

public class BrokerRequestRejectedException extends RuntimeException {

    public BrokerRequestRejectedException(String message) {
        super(message);
    }

}
