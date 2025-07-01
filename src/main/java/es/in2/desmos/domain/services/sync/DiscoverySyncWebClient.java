package es.in2.desmos.domain.services.sync;

import es.in2.desmos.domain.models.DiscoverySyncResponse;
import es.in2.desmos.domain.models.MVEntity4DataNegotiation;
import jakarta.validation.constraints.NotNull;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface DiscoverySyncWebClient {
    Mono<DiscoverySyncResponse> makeRequest(String processId, Mono<String> externalAccessNodeMono, Flux<@NotNull MVEntity4DataNegotiation> externalMVEntities4DataNegotiation);
}
