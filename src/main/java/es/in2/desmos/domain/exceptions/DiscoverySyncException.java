package es.in2.desmos.domain.exceptions;

import java.io.Serial;

public class DiscoverySyncException extends RuntimeException{

    @Serial
    private static final long serialVersionUID = 1L;

    public DiscoverySyncException(String message) {
        super(message);
    }

}
