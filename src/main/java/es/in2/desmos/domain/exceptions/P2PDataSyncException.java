package es.in2.desmos.domain.exceptions;

import java.io.Serial;

public class P2PDataSyncException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public P2PDataSyncException(String message) {
        super(message);
    }

}
