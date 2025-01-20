package es.in2.desmos.domain.exceptions;

public class JWTClaimMissingException extends RuntimeException{

    public JWTClaimMissingException(String message) {
        super(message);
    }

}
