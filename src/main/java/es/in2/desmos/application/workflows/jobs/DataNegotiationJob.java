package es.in2.desmos.application.workflows.jobs;

import es.in2.desmos.domain.models.DataNegotiationEvent;
import es.in2.desmos.domain.models.Issuer;
import es.in2.desmos.domain.models.MVEntity4DataNegotiation;
import org.springframework.context.event.EventListener;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

public interface DataNegotiationJob {

    Mono<Void> negotiateDataSyncWithMultipleIssuers(String processId, Mono<Map<Issuer, Flux<MVEntity4DataNegotiation>>> localMvEntities4DataNegotiationMono, Flux<MVEntity4DataNegotiation> mvEntities4DataNegotiationMono);

    @EventListener
    Mono<Void> negotiateDataSyncFromEvent(DataNegotiationEvent dataNegotiationEvent);
}
