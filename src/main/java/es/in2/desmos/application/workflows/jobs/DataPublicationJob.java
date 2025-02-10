package es.in2.desmos.application.workflows.jobs;

import es.in2.desmos.domain.models.Entity;
import es.in2.desmos.domain.models.HashAndHashLink;
import es.in2.desmos.domain.models.Id;
import es.in2.desmos.domain.models.MVEntity4DataNegotiation;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

public interface DataPublicationJob {
    Mono<Void> verifyData(String processId, Mono<String> issuer, Mono<Map<Id, Entity>> entitiesByIdMono, Mono<List<MVEntity4DataNegotiation>> allMVEntity4DataNegotiation, Mono<Map<Id, HashAndHashLink>> existingEntitiesOriginalValidationDataById);
}
