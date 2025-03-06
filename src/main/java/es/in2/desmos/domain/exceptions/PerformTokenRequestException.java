package es.in2.desmos.domain.exceptions;

import java.io.Serial;

public class PerformTokenRequestException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public PerformTokenRequestException(String message) {
        super(message);
    }

}