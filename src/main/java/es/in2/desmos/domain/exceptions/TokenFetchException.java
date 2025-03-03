package es.in2.desmos.domain.exceptions;

import java.io.Serial;

public class TokenFetchException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public TokenFetchException(String message, Throwable cause) {
        super(message, cause);
    }
}