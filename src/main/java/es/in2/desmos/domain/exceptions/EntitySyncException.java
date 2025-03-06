package es.in2.desmos.domain.exceptions;

import java.io.Serial;

public class EntitySyncException extends RuntimeException{

    @Serial
    private static final long serialVersionUID = 1L;

    public EntitySyncException(String message) {
        super(message);
    }

}
