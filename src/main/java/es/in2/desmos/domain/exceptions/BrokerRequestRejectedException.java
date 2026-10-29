package es.in2.desmos.domain.exceptions;

import lombok.Getter;

/**
 * The broker answered a request with a 4xx status. The message keeps the raw response body; {@link #getDetail()}
 * holds only its NGSI-LD problem detail, for log lines.
 */
@Getter
public class BrokerRequestRejectedException extends RuntimeException {

    private final int status;
    private final String detail;

    public BrokerRequestRejectedException(String body, int status, String detail) {
        super(body);
        this.status = status;
        this.detail = detail;
    }

}
