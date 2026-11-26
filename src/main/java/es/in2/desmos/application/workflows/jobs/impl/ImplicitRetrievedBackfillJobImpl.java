package es.in2.desmos.application.workflows.jobs.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import es.in2.desmos.application.workflows.jobs.ImplicitRetrievedBackfillJob;
import es.in2.desmos.domain.exceptions.ImplicitRetrievedBackfillException;
import es.in2.desmos.domain.exceptions.JsonReadingException;
import es.in2.desmos.domain.models.AuditRecord;
import es.in2.desmos.domain.models.Id;
import es.in2.desmos.domain.models.ImplicitRetrievedBackfillSummary;
import es.in2.desmos.domain.models.ImplicitRetrievedEntity;
import es.in2.desmos.domain.repositories.AuditRecordRepository;
import es.in2.desmos.domain.services.api.AuditRecordService;
import es.in2.desmos.domain.services.broker.BrokerPublisherService;
import es.in2.desmos.domain.utils.ApplicationUtils;
import es.in2.desmos.domain.utils.LogUtils;
import es.in2.desmos.infrastructure.configs.properties.AuditProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;

import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import static es.in2.desmos.domain.utils.ApplicationConstants.ROOT_OBJECTS_LIST;
import static es.in2.desmos.domain.utils.LogUtils.BACKFILL;

/**
 * Backfill of the implicit RETRIEVED records of the subentities written before 2.0.28.
 * <p>
 * Roots are the entities with a PUBLISHED/CONSUMER record. Each root is read from the broker with its subentities,
 * as when serving it to another node, and every entity read that is not a root is a subentity. Only the subentities
 * of a root type are handled: the broker is only subscribed to those types and never notifies the others.
 * <p>
 * A subentity gets its RETRIEVED only when the hash of its current content already appears in an audit record of
 * that entity (any status, any trader): content this node already saw. An unknown hash may be a local edit whose
 * broker notification has not arrived yet; recording it would hide that edit as an echo, so it is skipped.
 * <p>
 * It never writes to the broker and only appends audit records, through {@link AuditRecordService}, in chunks of
 * {@code audit.batch-size}. The deduplication of {@code saveImplicitConsumerRetrieved} makes a second run write
 * nothing.
 */
@Slf4j
@Service
public class ImplicitRetrievedBackfillJobImpl implements ImplicitRetrievedBackfillJob {

    private final AuditRecordRepository auditRecordRepository;
    private final AuditRecordService auditRecordService;
    private final BrokerPublisherService brokerPublisherService;
    private final ObjectMapper objectMapper;
    private final int batchSize;
    private final boolean enabled;

    public ImplicitRetrievedBackfillJobImpl(AuditRecordRepository auditRecordRepository,
                                            AuditRecordService auditRecordService,
                                            BrokerPublisherService brokerPublisherService,
                                            ObjectMapper objectMapper,
                                            AuditProperties auditProperties,
                                            @Value("${backfill.implicit-retrieved:false}") boolean enabled) {
        this.auditRecordRepository = auditRecordRepository;
        this.auditRecordService = auditRecordService;
        this.brokerPublisherService = brokerPublisherService;
        this.objectMapper = objectMapper;
        this.batchSize = auditProperties.batchSize();
        this.enabled = enabled;
    }

    @Override
    public Mono<Void> runIfEnabled(String processId) {
        if (!enabled) {
            log.debug("ProcessID: {} - Implicit RETRIEVED backfill disabled", processId);
            return Mono.empty();
        }
        return run(processId).then();
    }

    @Override
    public Mono<ImplicitRetrievedBackfillSummary> run(String processId) {
        return Mono.defer(() -> {
            Counters counters = new Counters();
            long start = System.nanoTime();
            return auditRecordRepository.findEntityIdsWithConsumerPublished()
                    .collect(Collectors.toSet())
                    .flatMapMany(rootIds -> {
                        counters.roots.set(rootIds.size());
                        log.info("{} ProcessID: {} - START roots={} batchSize={}", BACKFILL, processId, rootIds.size(), batchSize);
                        List<Id> ids = rootIds.stream().map(Id::new).toList();
                        return brokerPublisherService.findEntitiesAndItsSubentitiesById(processId, Mono.just(ids))
                                .map(this::toImplicitRetrievedEntity)
                                .filter(entity -> !rootIds.contains(entity.id()));
                    })
                    .doOnNext(subentity -> counters.subentities.incrementAndGet())
                    .filter(subentity -> {
                        if (ROOT_OBJECTS_LIST.contains(subentity.type())) {
                            return true;
                        }
                        counters.skippedByType.incrementAndGet();
                        return false;
                    })
                    .buffer(batchSize)
                    .concatMap(chunk -> recordChunk(processId, chunk, counters))
                    .then(Mono.fromCallable(counters::summary))
                    .flatMap(summary -> summary.failed() > 0
                            ? Mono.error(new ImplicitRetrievedBackfillException(summary))
                            : Mono.just(summary))
                    .doFinally(signal -> logSummary(processId, counters, signal, start));
        });
    }

    private Mono<Void> recordChunk(String processId, List<ImplicitRetrievedEntity> chunk, Counters counters) {
        String[] entityIds = chunk.stream().map(ImplicitRetrievedEntity::id).toArray(String[]::new);
        return auditRecordRepository.findKnownEntityHashesByEntityIds(entityIds)
                .collect(Collectors.groupingBy(AuditRecord::getEntityId,
                        Collectors.mapping(AuditRecord::getEntityHash, Collectors.toSet())))
                .map(knownHashesById -> withKnownContent(processId, chunk, knownHashesById, counters))
                .onErrorResume(error -> {
                    logFailed(processId, chunk, "reading their audit records", error, counters);
                    return Mono.empty();
                })
                .flatMap(eligible -> eligible.isEmpty()
                        ? Mono.empty()
                        : auditRecordService.saveImplicitConsumerRetrieved(processId, eligible)
                        .doOnNext(written -> {
                            counters.inserted.addAndGet(written);
                            counters.upToDate.addAndGet(eligible.size() - (long) written);
                        })
                        .then()
                        .onErrorResume(error -> {
                            logFailed(processId, eligible, "writing their RETRIEVED", error, counters);
                            return Mono.empty();
                        }));
    }

    private List<ImplicitRetrievedEntity> withKnownContent(String processId, List<ImplicitRetrievedEntity> chunk,
                                                           Map<String, Set<String>> knownHashesById,
                                                           Counters counters) {
        return chunk.stream()
                .filter(subentity -> {
                    if (knownHashesById.getOrDefault(subentity.id(), Set.of()).contains(hashOf(subentity.body()))) {
                        return true;
                    }
                    counters.skippedByUnknownHash.incrementAndGet();
                    log.info("{} ProcessID: {} - Skipped {} {}: its current content has no audit record",
                            BACKFILL, processId, subentity.type(), subentity.id());
                    return false;
                })
                .toList();
    }

    // One line per subentity, so the ones to look at are named
    private static void logFailed(String processId, List<ImplicitRetrievedEntity> subentities, String step,
                                  Throwable error, Counters counters) {
        counters.failed.addAndGet(subentities.size());
        subentities.forEach(subentity -> log.error("{} ProcessID: {} - Failed {} {} ({}): {}",
                BACKFILL, processId, subentity.type(), subentity.id(), step, error.getMessage()));
    }

    private static void logSummary(String processId, Counters counters, SignalType signal, long start) {
        ImplicitRetrievedBackfillSummary summary = counters.summary();
        String outcome = switch (signal) {
            case ON_COMPLETE -> "completed";
            case CANCEL -> "cancelled";
            default -> summary.failed() > 0 ? "incomplete" : "failed";
        };
        log.info("{} ProcessID: {} - END {} roots={} subentities={} skippedByType={} skippedByUnknownHash={} "
                        + "upToDate={} inserted={} failed={} duration={}",
                BACKFILL, processId, outcome, summary.roots(), summary.subentities(), summary.skippedByType(),
                summary.skippedByUnknownHash(), summary.upToDate(), summary.inserted(), summary.failed(),
                LogUtils.formatDurationSince(start));
    }

    private ImplicitRetrievedEntity toImplicitRetrievedEntity(String body) {
        try {
            JsonNode entity = objectMapper.readTree(body);
            return new ImplicitRetrievedEntity(entity.path("id").asText(), entity.path("type").asText(), body);
        } catch (JsonProcessingException e) {
            throw new JsonReadingException(e.getMessage());
        }
    }

    private static String hashOf(String body) {
        try {
            return ApplicationUtils.calculateSHA256(body);
        } catch (NoSuchAlgorithmException | JsonProcessingException e) {
            throw new IllegalStateException("Could not hash an entity of the broker", e);
        }
    }

    private static final class Counters {
        private final AtomicLong roots = new AtomicLong();
        private final AtomicLong subentities = new AtomicLong();
        private final AtomicLong skippedByType = new AtomicLong();
        private final AtomicLong skippedByUnknownHash = new AtomicLong();
        private final AtomicLong upToDate = new AtomicLong();
        private final AtomicLong inserted = new AtomicLong();
        private final AtomicLong failed = new AtomicLong();

        private ImplicitRetrievedBackfillSummary summary() {
            return new ImplicitRetrievedBackfillSummary(roots.get(), subentities.get(), skippedByType.get(),
                    skippedByUnknownHash.get(), upToDate.get(), inserted.get(), failed.get());
        }
    }
}
