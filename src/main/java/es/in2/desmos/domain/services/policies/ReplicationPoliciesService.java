package es.in2.desmos.domain.services.policies;

import es.in2.desmos.domain.models.Id;
import es.in2.desmos.domain.models.MVEntityReplicationPoliciesInfo;
import es.in2.desmos.domain.models.ReplicationDecision;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface ReplicationPoliciesService {
    Mono<Boolean> isMVEntityReplicable(String processId, MVEntityReplicationPoliciesInfo mvEntity);

    Mono<ReplicationDecision> evaluate(String processId, MVEntityReplicationPoliciesInfo mvEntity);

    Flux<Id> filterReplicableMvEntitiesList( String processId, Flux<MVEntityReplicationPoliciesInfo> replicationPoliciesInfoFlux);
}
