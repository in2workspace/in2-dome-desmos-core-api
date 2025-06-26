package es.in2.desmos.application.workflows.jobs;

import es.in2.desmos.domain.models.Entity;
import es.in2.desmos.domain.models.Id;
import es.in2.desmos.domain.models.MVEntity4DataNegotiation;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

public interface P2PDataSyncJob {
    Mono<Void> synchronizeData(String processId);

    Flux<MVEntity4DataNegotiation> dataDiscovery(String processId, Mono<String> issuer, Flux<MVEntity4DataNegotiation> externalMvEntities4DataNegotiation);

    Mono<List<Entity>> getLocalEntitiesByIdInBase64(String processId, Mono<List<Id>> ids);
}
