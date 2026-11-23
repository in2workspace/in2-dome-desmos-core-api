package es.in2.desmos.domain.exceptions;

import es.in2.desmos.domain.models.ImplicitRetrievedBackfillSummary;
import lombok.Getter;

import java.io.Serial;

@Getter
public class ImplicitRetrievedBackfillException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final transient ImplicitRetrievedBackfillSummary summary;

    public ImplicitRetrievedBackfillException(ImplicitRetrievedBackfillSummary summary) {
        super(summary.failed() + " of " + summary.subentities() + " subentities could not be recorded");
        this.summary = summary;
    }
}
