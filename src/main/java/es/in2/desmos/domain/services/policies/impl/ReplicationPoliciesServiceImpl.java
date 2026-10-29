package es.in2.desmos.domain.services.policies.impl;

import es.in2.desmos.domain.models.Id;
import es.in2.desmos.domain.models.MVEntityReplicationPoliciesInfo;
import es.in2.desmos.domain.models.ReplicationDecision;
import es.in2.desmos.domain.models.ReplicationPolicies;
import es.in2.desmos.domain.services.policies.ReplicationPoliciesService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

import static es.in2.desmos.domain.utils.ApplicationConstants.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReplicationPoliciesServiceImpl implements ReplicationPoliciesService {

    private static final Set<String> VALID_STATUSES = Set.of("Launched", "Retired", "Obsolete");

    @Override
    public Mono<Boolean> isMVEntityReplicable(String processId, MVEntityReplicationPoliciesInfo mvEntity) {
        return evaluate(processId, mvEntity).map(ReplicationDecision::replicable);
    }

    // Not being replicable by policy is expected (e.g. drafts, expired offerings), so it is logged at DEBUG.
    // Callers that report the outcome (e.g. broker notifications) log the returned reason.
    @Override
    public Mono<ReplicationDecision> evaluate(String processId, MVEntityReplicationPoliciesInfo mvEntity) {
        boolean isLifecycleStatusReplicable = isLifecycleStatusReplicable(mvEntity.type(), mvEntity.lifecycleStatus());
        if (!isLifecycleStatusReplicable) {
            log.debug("ProcessID: {} - Global policy '{}' validation failed for entity '{}' - lifecycleStatus '{}' is not replicable",
                    processId, ReplicationPolicies.GP_1, mvEntity.id(), mvEntity.lifecycleStatus());

        }

        boolean isValidForReplicable = isValidForReplicable(mvEntity.startDateTime(), mvEntity.endDateTime());
        if (!isValidForReplicable) {
            log.debug("ProcessID: {} - Global policy '{}' validation failed for entity '{}' - validFor period (start: '{}', end: '{}') is not replicable",
                    processId, ReplicationPolicies.GP_2, mvEntity.id(), mvEntity.startDateTime(), mvEntity.endDateTime());

        }

        if (!isLifecycleStatusReplicable) {
            return Mono.just(ReplicationDecision.notReplicable(
                    "policy " + ReplicationPolicies.GP_1 + ": lifecycleStatus=" + mvEntity.lifecycleStatus()));
        }
        if (!isValidForReplicable) {
            return Mono.just(ReplicationDecision.notReplicable(
                    "policy " + ReplicationPolicies.GP_2 + ": validFor start=" + mvEntity.startDateTime()
                            + " end=" + mvEntity.endDateTime()));
        }
        log.debug("ProcessID: {} - Global policies validation successfully in Entity with ID {}.",
                processId, mvEntity.id());
        return Mono.just(ReplicationDecision.REPLICABLE);
    }

    @Override
    public Flux<Id> filterReplicableMvEntitiesList(String processId, Flux<MVEntityReplicationPoliciesInfo> replicationPoliciesInfoFlux) {
        return replicationPoliciesInfoFlux
                .concatMap(mvEntity -> evaluate(processId, mvEntity).map(decision -> Map.entry(mvEntity, decision)))
                .collectList()
                .doOnNext(evaluatedEntities -> logReplicationSummary(processId, evaluatedEntities))
                .flatMapIterable(evaluatedEntities -> evaluatedEntities.stream()
                        .filter(evaluatedEntity -> evaluatedEntity.getValue().replicable())
                        .map(evaluatedEntity -> new Id(evaluatedEntity.getKey().id()))
                        .toList());
    }

    // One line per entity type instead of one per non-replicable entity: how many are replicable and, for the rest,
    // how many each policy rejected and why.
    private void logReplicationSummary(String processId, List<Map.Entry<MVEntityReplicationPoliciesInfo, ReplicationDecision>> evaluatedEntities) {
        evaluatedEntities.stream()
                .collect(Collectors.groupingBy(evaluatedEntity -> Objects.toString(evaluatedEntity.getKey().type(), "unknown"),
                        LinkedHashMap::new, Collectors.toList()))
                .forEach((type, entitiesOfType) -> {
                    long replicable = entitiesOfType.stream().filter(evaluatedEntity -> evaluatedEntity.getValue().replicable()).count();
                    String notReplicableByReason = entitiesOfType.stream()
                            .filter(evaluatedEntity -> !evaluatedEntity.getValue().replicable())
                            .collect(Collectors.groupingBy(evaluatedEntity -> evaluatedEntity.getValue().reason(), Collectors.counting()))
                            .entrySet().stream()
                            .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                            .map(reasonCount -> reasonCount.getKey() + " (" + reasonCount.getValue() + ")")
                            .collect(Collectors.joining(", "));
                    if (notReplicableByReason.isEmpty()) {
                        log.info("ProcessID: {} - Replication policies - {}: {} of {} replicable", processId, type, replicable, entitiesOfType.size());
                    } else {
                        log.info("ProcessID: {} - Replication policies - {}: {} of {} replicable; not replicable: {}",
                                processId, type, replicable, entitiesOfType.size(), notReplicableByReason);
                    }
                });
    }


    private boolean isLifecycleStatusReplicable(String type, String lifecycleStatus) {
        return LIFECYCLE_STATUS_FREE_TYPES.contains(type) || (lifecycleStatus != null && VALID_STATUSES.contains(lifecycleStatus));
    }

    private boolean isValidForReplicable(String startDateTime, String endDateTime) {
        return isAfterStartDateTime(startDateTime) && isBeforeEndDateTime(endDateTime);
    }

    private boolean isAfterStartDateTime(String startDateTime) {
        return startDateTime == null ||
                Instant.now().plusSeconds(2).isAfter(Instant.parse(startDateTime));
    }

    private boolean isBeforeEndDateTime(String endDateTime) {
        return endDateTime == null ||
                Instant.now().isBefore(Instant.parse(endDateTime));
    }
}