package es.in2.desmos.domain.exceptions;

import java.io.Serial;

public class JWTVerificationException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public JWTVerificationException(String message) {
        super(message);
    }

}