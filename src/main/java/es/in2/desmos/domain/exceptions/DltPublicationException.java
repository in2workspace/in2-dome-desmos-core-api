package es.in2.desmos.domain.exceptions;

import java.io.Serial;

/**
 * A publication to the DLT adapter failed. It has already been logged with its details by the adapter client.
 */
public class DltPublicationException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public DltPublicationException(String message, Throwable cause) {
        super(message, cause);
    }

}
