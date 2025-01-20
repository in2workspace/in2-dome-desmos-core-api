package es.in2.desmos.domain.exceptions;

public class JWTVerificationException extends RuntimeException {

    public JWTVerificationException(String message) {
        super(message);
    }

}