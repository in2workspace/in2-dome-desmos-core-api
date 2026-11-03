package es.in2.desmos.domain.services.broker.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import es.in2.desmos.domain.exceptions.BrokerNotificationSelfGeneratedException;
import es.in2.desmos.domain.exceptions.JsonReadingException;
import es.in2.desmos.domain.models.*;
import es.in2.desmos.domain.services.api.AuditRecordService;
import es.in2.desmos.domain.services.api.QueueService;
import es.in2.desmos.domain.services.broker.BrokerListenerService;
import es.in2.desmos.domain.services.broker.adapter.BrokerAdapterService;
import es.in2.desmos.domain.services.broker.adapter.factory.BrokerAdapterFactory;
import es.in2.desmos.domain.services.policies.ReplicationPoliciesService;
import es.in2.desmos.infrastructure.configs.properties.LoggingProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.Map;

import static es.in2.desmos.domain.utils.ApplicationUtils.calculateSHA256;
import static es.in2.desmos.domain.utils.LogUtils.NOTIF;
import static es.in2.desmos.domain.utils.LogUtils.truncate;

@Slf4j
@Service
public class BrokerListenerServiceImpl implements BrokerListenerService {

    private static final int MAX_NAME_LENGTH = 80;
    private static final String VALUE_FIELD = "value";

    private final ObjectMapper objectMapper;
    private final BrokerAdapterService brokerAdapter;
    private final AuditRecordService auditRecordService;
    private final QueueService pendingPublishEventsQueue;
    private final ReplicationPoliciesService replicationPoliciesService;
    private final LoggingProperties loggingProperties;

    public BrokerListenerServiceImpl(BrokerAdapterFactory brokerAdapterFactory, ObjectMapper objectMapper,
                                     AuditRecordService auditRecordService, QueueService pendingPublishEventsQueue,
                                     ReplicationPoliciesService replicationPoliciesService,
                                     LoggingProperties loggingProperties) {
        this.brokerAdapter = brokerAdapterFactory.getBrokerAdapter();
        this.objectMapper = objectMapper;
        this.auditRecordService = auditRecordService;
        this.pendingPublishEventsQueue = pendingPublishEventsQueue;
        this.replicationPoliciesService = replicationPoliciesService;
        this.loggingProperties = loggingProperties;
    }

    @Override
    public Mono<Void> createSubscription(String processId, BrokerSubscription brokerSubscription) {
        return brokerAdapter.createSubscription(processId, brokerSubscription);
    }

    @Override
    public Mono<Void> processBrokerNotification(String processId, BrokerNotification brokerNotification) {
        // Validate BrokerNotification is not null and has data
        // One INFO line per notification with its outcome; the payload only at DEBUG and truncated, since some
        // entities carry license texts of several KB.
        return getDataFromBrokerNotification(brokerNotification)
                .doFirst(() -> {
                    if (log.isDebugEnabled()) {
                        log.debug("{} ProcessID: {} - Broker Notification payload: {}", NOTIF, processId,
                                truncate(serialize(brokerNotification.data()), loggingProperties.payloadMaxChars()));
                    }
                })
                // Validate if BrokerNotification is from an external source or self-generated
                .flatMap(dataMap -> isBrokerNotificationSelfGenerated(processId, dataMap)
                        .flatMap(isSelfGenerated -> {
                            if (Boolean.TRUE.equals(isSelfGenerated)) {
                                logOutcome(processId, dataMap, "skipped (self-generated)");
                                return Mono.empty();
                            } else {
                                MVEntityReplicationPoliciesInfo mvEntityReplicationPoliciesInfo =
                                        createMVEntityReplicationPoliciesInfo(dataMap);
                                return replicationPoliciesService
                                        .evaluate(processId, mvEntityReplicationPoliciesInfo)
                                        .flatMap(decision -> {
                                            if (!decision.replicable()) {
                                                log.warn("{} ProcessID: {} - {} {}{} -> not replicable: {}", NOTIF, processId,
                                                        dataMap.get("type"), dataMap.get("id"), quotedName(dataMap), decision.reason());
                                                return Mono.empty();
                                            } else {
                                                return publishEventAndCreateAuditRecord(processId, brokerNotification, dataMap);
                                            }
                                        });
                            }
                        }));
    }

    private void logOutcome(String processId, Map<String, Object> dataMap, String outcome) {
        log.info("{} ProcessID: {} - {} {}{} -> {}", NOTIF, processId, dataMap.get("type"), dataMap.get("id"),
                quotedName(dataMap), outcome);
    }

    private static String quotedName(Map<String, Object> dataMap) {
        String name = getNameFromDataMap(dataMap);
        return name != null ? " \"" + name + "\"" : "";
    }

    @SuppressWarnings("unchecked")
    private static String getNameFromDataMap(Map<String, Object> dataMap) {
        Object name = dataMap.get("name");
        if (name instanceof Map<?, ?> nameProperty) {
            name = ((Map<String, Object>) nameProperty).get(VALUE_FIELD);
        }
        return name != null ? truncate(name.toString(), MAX_NAME_LENGTH) : null;
    }

    private String serialize(Object data) {
        try {
            return objectMapper.writeValueAsString(data);
        } catch (JsonProcessingException | RuntimeException e) {
            return String.valueOf(data);
        }
    }

    private Mono<Void> publishEventAndCreateAuditRecord(String processId, BrokerNotification brokerNotification, Map<String, Object> dataMap) {
        return auditRecordService.buildAndSaveAuditRecordFromBrokerNotification(processId, dataMap,
                        AuditRecordStatus.RECEIVED, null)
                // Set priority for the pendingSubscribeEventsQueue event
                .then(Mono.just(EventQueuePriority.MEDIUM))
                // Enqueue BrokerNotification to DataPublicationQueue
                .flatMap(eventQueuePriority -> pendingPublishEventsQueue.enqueueEvent(EventQueue.builder()
                        .event(Collections.singletonList(brokerNotification))
                        .priority(eventQueuePriority)
                        .build()))
                .doOnSuccess(unused -> logOutcome(processId, dataMap, "enqueued"))
                .doOnError(throwable -> {
                    if (throwable instanceof BrokerNotificationSelfGeneratedException) {
                        log.info("{} ProcessID: {} - Self-Generated Broker Notification. It does not need to do nothing.", NOTIF, processId);
                    } else {
                        log.error("{} ProcessID: {} - {} {} -> error: {}", NOTIF, processId, dataMap.get("type"),
                                dataMap.get("id"), throwable.getMessage());
                    }
                });
    }

    @SuppressWarnings("unchecked")
    private MVEntityReplicationPoliciesInfo createMVEntityReplicationPoliciesInfo(Map<String, Object> dataMap) {
        String lifeCycleStatus = null;
        if (dataMap.containsKey("lifecycleStatus")) {
            Map<String, Object> lifeCycleStatusMap = (Map<String, Object>) dataMap.get("lifecycleStatus");
            lifeCycleStatus = (String) lifeCycleStatusMap.get(VALUE_FIELD);
        }

        String startDateTime = null;
        String endDateTime = null;
        if (dataMap.containsKey("validFor")) {
            Map<String, Object> validForMap = (Map<String, Object>) dataMap.get("validFor");
            Map<String, Object> validForValueMap = (Map<String, Object>) validForMap.get(VALUE_FIELD);
            if (validForValueMap != null) {
                startDateTime = (String) validForValueMap.get("startDateTime");
                endDateTime = (String) validForValueMap.get("endDateTime");
            }
        }

        String type = dataMap.get("type") != null
                ? dataMap.get("type").toString()
                : null;

        return new MVEntityReplicationPoliciesInfo(
                dataMap.get("id").toString(),
                type,
                lifeCycleStatus,
                startDateTime,
                endDateTime
        );
    }

    private Mono<Map<String, Object>> getDataFromBrokerNotification(BrokerNotification brokerNotification) {
        //Get data from brokerNotification
        Map<String, Object> dataMap = brokerNotification.data().get(0);
        return Mono.just(dataMap);
    }

    private Mono<Boolean> isBrokerNotificationSelfGenerated(String processId, Map<String, Object> dataMap) {
        String id = dataMap.get("id").toString();

        return auditRecordService.findMostRecentRetrievedOrDeletedByEntityId(processId, id)
                .flatMap(auditRecordFound -> {
                    try {
                        String newEntityHash = calculateSHA256(objectMapper.writer().writeValueAsString(dataMap));
                        return auditRecordFound.getEntityHash().equals(newEntityHash)
                                ? Mono.just(true)
                                : Mono.just(false);
                    } catch (JsonProcessingException | NoSuchAlgorithmException e) {
                        log.warn("ProcessID: {} - Error processing JSON: {}", processId, e.getMessage());
                        return Mono.error(new JsonReadingException("Error processsing Broker Notification JSON"));
                    }
                })
                .switchIfEmpty(Mono.just(false));
    }
}
