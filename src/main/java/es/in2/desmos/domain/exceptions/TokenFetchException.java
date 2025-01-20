package es.in2.desmos.domain.exceptions;

public class TokenFetchException extends RuntimeException {
    public TokenFetchException(String message, Throwable cause) {
        super(message, cause);
    }
}