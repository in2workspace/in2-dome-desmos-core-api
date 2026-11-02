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
    private static final String POLICY_PREFIX = "policy ";

    @Override
    public Mono<Boolean> isMVEntityReplicable(String processId, MVEntityReplicationPoliciesInfo mvEntity) {
        return evaluate(processId, mvEntity).map(ReplicationDecision::replicable);
    }

    // Not being replicable by policy is expected (e.g. drafts, expired offerings), so it is logged at DEBUG.
    // Callers that report the outcome (e.g. broker notifications) log the returned reason.
    @Override
    public Mono<ReplicationDecision> evaluate(String processId, MVEntityReplicationPoliciesInfo mvEntity) {
        ReplicationDecision decision = decide(mvEntity);
        if (decision.replicable()) {
            log.debug("ProcessID: {} - Global policies validation successfully in Entity with ID {}.", processId, mvEntity.id());
        } else {
            log.debug("ProcessID: {} - Entity '{}' is not replicable: {}", processId, mvEntity.id(), decision.reason());
        }
        return Mono.just(decision);
    }

    // Checks a whole batch without logging each entity: the outcome is logged once per entity type and reason.
    @Override
    public Flux<Id> filterReplicableMvEntitiesList(String processId, Flux<MVEntityReplicationPoliciesInfo> replicationPoliciesInfoFlux) {
        return replicationPoliciesInfoFlux
                .map(mvEntity -> Map.entry(mvEntity, decide(mvEntity)))
                .collectList()
                .doOnNext(evaluatedEntities -> logReplicationSummary(processId, evaluatedEntities))
                .flatMapIterable(evaluatedEntities -> evaluatedEntities.stream()
                        .filter(evaluatedEntity -> evaluatedEntity.getValue().replicable())
                        .map(evaluatedEntity -> new Id(evaluatedEntity.getKey().id()))
                        .toList());
    }

    private ReplicationDecision decide(MVEntityReplicationPoliciesInfo mvEntity) {
        if (!isLifecycleStatusReplicable(mvEntity.type(), mvEntity.lifecycleStatus())) {
            return ReplicationDecision.notReplicable(
                    POLICY_PREFIX + ReplicationPolicies.GP_1 + ": lifecycleStatus=" + mvEntity.lifecycleStatus());
        }
        if (!isValidForReplicable(mvEntity.startDateTime(), mvEntity.endDateTime())) {
            return ReplicationDecision.notReplicable(
                    POLICY_PREFIX + ReplicationPolicies.GP_2 + ": validFor start=" + mvEntity.startDateTime()
                            + " end=" + mvEntity.endDateTime());
        }
        return ReplicationDecision.REPLICABLE_DECISION;
    }

    // Per entity type: one INFO line with how many entities are replicable and how many each reason left out, and one
    // WARN line per reason with the ids it left out.
    private void logReplicationSummary(String processId, List<Map.Entry<MVEntityReplicationPoliciesInfo, ReplicationDecision>> evaluatedEntities) {
        evaluatedEntities.stream()
                .collect(Collectors.groupingBy(evaluatedEntity -> Objects.toString(evaluatedEntity.getKey().type(), "unknown"),
                        LinkedHashMap::new, Collectors.toList()))
                .forEach((type, entitiesOfType) -> {
                    long replicable = entitiesOfType.stream().filter(evaluatedEntity -> evaluatedEntity.getValue().replicable()).count();
                    List<Map.Entry<String, List<String>>> notReplicableByReason = entitiesOfType.stream()
                            .filter(evaluatedEntity -> !evaluatedEntity.getValue().replicable())
                            .collect(Collectors.groupingBy(evaluatedEntity -> groupReason(evaluatedEntity.getKey(), evaluatedEntity.getValue()),
                                    Collectors.mapping(evaluatedEntity -> describeNotReplicable(evaluatedEntity.getKey()), Collectors.toList())))
                            .entrySet().stream()
                            .sorted(Comparator.comparingInt((Map.Entry<String, List<String>> reasonGroup) -> reasonGroup.getValue().size()).reversed())
                            .toList();

                    if (notReplicableByReason.isEmpty()) {
                        log.info("ProcessID: {} - Replication policies - {}: {} of {} replicable", processId, type, replicable, entitiesOfType.size());
                        return;
                    }
                    log.info("ProcessID: {} - Replication policies - {}: {} of {} replicable; not replicable: {}",
                            processId, type, replicable, entitiesOfType.size(), notReplicableByReason.stream()
                                    .map(reasonGroup -> reasonGroup.getKey() + " (" + reasonGroup.getValue().size() + ")")
                                    .collect(Collectors.joining(", ")));
                    notReplicableByReason.forEach(reasonGroup ->
                            log.warn("ProcessID: {} - Replication policies - {}: {} ({}): {}",
                                    processId, type, reasonGroup.getKey(), reasonGroup.getValue().size(), reasonGroup.getValue()));
                });
    }

    // GP_2 rejections share one reason: their validFor dates differ per entity, so they go next to each id instead
    private String groupReason(MVEntityReplicationPoliciesInfo mvEntity, ReplicationDecision decision) {
        return failsValidFor(mvEntity) ? POLICY_PREFIX + ReplicationPolicies.GP_2 + ": validFor" : decision.reason();
    }

    private String describeNotReplicable(MVEntityReplicationPoliciesInfo mvEntity) {
        return failsValidFor(mvEntity)
                ? mvEntity.id() + " (start=" + mvEntity.startDateTime() + ", end=" + mvEntity.endDateTime() + ")"
                : mvEntity.id();
    }

    // GP_1 is checked first, so an entity counts as a GP_2 rejection only when its lifecycleStatus is replicable
    private boolean failsValidFor(MVEntityReplicationPoliciesInfo mvEntity) {
        return isLifecycleStatusReplicable(mvEntity.type(), mvEntity.lifecycleStatus());
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