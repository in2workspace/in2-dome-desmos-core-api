package es.in2.desmos.domain.services.policies;

import es.in2.desmos.domain.models.Id;
import es.in2.desmos.domain.models.MVEntityReplicationPoliciesInfo;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

public interface ReplicationPoliciesService {
    Mono<Boolean> isMVEntityReplicable(String processId, MVEntityReplicationPoliciesInfo mvEntity);

    Flux<Id> filterReplicableMvEntitiesList( String processId, Flux<MVEntityReplicationPoliciesInfo> replicationPoliciesInfoFlux);
}
