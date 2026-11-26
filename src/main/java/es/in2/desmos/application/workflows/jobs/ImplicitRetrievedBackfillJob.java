package es.in2.desmos.application.workflows.jobs;

import es.in2.desmos.domain.models.ImplicitRetrievedBackfillSummary;
import reactor.core.publisher.Mono;

/**
 * Records an implicit RETRIEVED/CONSUMER for the subentities written to the local broker before 2.0.28, which have
 * none: a later broker notification with the same content is then recognised as self-generated instead of being
 * published as a local change.
 */
public interface ImplicitRetrievedBackfillJob {

    /**
     * Runs {@link #run(String)} when {@code backfill.implicit-retrieved} (BACKFILL_IMPLICIT_RETRIEVED) is true;
     * otherwise completes without reading anything.
     */
    Mono<Void> runIfEnabled(String processId);

    /**
     * Logs a {@code [backfill]} summary line when it ends. Fails, after that line, when the subentities could not be
     * read or some of them could not be recorded.
     */
    Mono<ImplicitRetrievedBackfillSummary> run(String processId);
}
