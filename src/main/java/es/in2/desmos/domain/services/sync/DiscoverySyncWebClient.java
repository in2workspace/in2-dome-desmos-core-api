package es.in2.desmos.domain.services.sync;

import es.in2.desmos.domain.models.MVEntity4DataNegotiation;
import jakarta.validation.constraints.NotNull;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface DiscoverySyncWebClient {
    Flux<MVEntity4DataNegotiation> makeRequest(String processId, Mono<String> externalAccessNodeMono, Flux<@NotNull MVEntity4DataNegotiation> externalMVEntities4DataNegotiation);
}
