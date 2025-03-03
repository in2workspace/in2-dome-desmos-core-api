package es.in2.desmos.domain.exceptions;

import java.io.Serial;

public class JWTClaimMissingException extends RuntimeException{

    @Serial
    private static final long serialVersionUID = 1L;

    public JWTClaimMissingException(String message) {
        super(message);
    }

}
