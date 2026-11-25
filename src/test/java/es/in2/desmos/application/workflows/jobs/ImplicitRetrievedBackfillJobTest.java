package es.in2.desmos.application.workflows.jobs;

import ch.qos.logback.classic.Level;
import com.fasterxml.jackson.databind.ObjectMapper;
import es.in2.desmos.application.workflows.jobs.impl.ImplicitRetrievedBackfillJobImpl;
import es.in2.desmos.domain.exceptions.ImplicitRetrievedBackfillException;
import es.in2.desmos.domain.models.AuditRecord;
import es.in2.desmos.domain.models.Id;
import es.in2.desmos.domain.models.ImplicitRetrievedBackfillSummary;
import es.in2.desmos.domain.models.ImplicitRetrievedEntity;
import es.in2.desmos.domain.repositories.AuditRecordRepository;
import es.in2.desmos.domain.services.api.AuditRecordService;
import es.in2.desmos.domain.services.broker.BrokerPublisherService;
import es.in2.desmos.domain.utils.ApplicationUtils;
import es.in2.desmos.infrastructure.configs.properties.AuditProperties;
import es.in2.desmos.testutils.LogCapture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ImplicitRetrievedBackfillJobTest {

    private static final String PROCESS_ID = "processId";
    private static final String ROOT = "urn:ngsi-ld:product-offering:root";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Mock
    private AuditRecordRepository auditRecordRepository;
    @Mock
    private AuditRecordService auditRecordService;
    @Mock
    private BrokerPublisherService brokerPublisherService;
    @Captor
    private ArgumentCaptor<List<ImplicitRetrievedEntity>> writtenCaptor;
    @Captor
    private ArgumentCaptor<Mono<List<Id>>> rootsCaptor;

    @Test
    void itShouldDoNothingWhenDisabled() {
        StepVerifier.create(job(500, false).runIfEnabled(PROCESS_ID)).verifyComplete();

        verifyNoInteractions(auditRecordRepository, auditRecordService, brokerPublisherService);
    }

    @Test
    void itShouldRunWhenEnabled() {
        when(auditRecordRepository.findEntityIdsWithConsumerPublished()).thenReturn(Flux.empty());
        when(brokerPublisherService.findEntitiesAndItsSubentitiesById(eq(PROCESS_ID), any())).thenReturn(Flux.empty());

        StepVerifier.create(job(500, true).runIfEnabled(PROCESS_ID)).verifyComplete();

        verify(auditRecordRepository).findEntityIdsWithConsumerPublished();
    }

    @Test
    void itShouldRecordTheSubentitiesOfARootTypeWhoseContentIsKnown() {
        String child = entity("urn:ngsi-ld:product-offering-price:1", "product-offering-price");
        String otherRoot = entity("urn:ngsi-ld:category:2", "category");
        String outsideRootTypes = entity("urn:ngsi-ld:other:3", "other");
        String root = entity(ROOT, "product-offering");
        when(auditRecordRepository.findEntityIdsWithConsumerPublished()).thenReturn(Flux.just(ROOT, "urn:ngsi-ld:category:2"));
        when(brokerPublisherService.findEntitiesAndItsSubentitiesById(eq(PROCESS_ID), rootsCaptor.capture()))
                .thenReturn(Flux.just(child, otherRoot, outsideRootTypes, root));
        when(auditRecordRepository.findKnownEntityHashesByEntityIds(any()))
                .thenReturn(Flux.just(known("urn:ngsi-ld:product-offering-price:1", sha256(child))));
        when(auditRecordService.saveImplicitConsumerRetrieved(eq(PROCESS_ID), writtenCaptor.capture())).thenReturn(Mono.just(1));

        StepVerifier.create(job(500, true).run(PROCESS_ID))
                .expectNext(new ImplicitRetrievedBackfillSummary(2, 2, 1, 0, 0, 1, 0))
                .verifyComplete();

        assertThat(rootsCaptor.getValue().block()).extracting(Id::id)
                .containsExactlyInAnyOrder(ROOT, "urn:ngsi-ld:category:2");
        verify(auditRecordRepository).findKnownEntityHashesByEntityIds(new String[]{"urn:ngsi-ld:product-offering-price:1"});
        assertThat(writtenCaptor.getValue()).containsExactly(
                new ImplicitRetrievedEntity("urn:ngsi-ld:product-offering-price:1", "product-offering-price", child));
    }

    @Test
    void itShouldSkipASubentityWhoseContentIsUnknown() {
        String child = entity("urn:ngsi-ld:product-offering-price:1", "product-offering-price");
        when(auditRecordRepository.findEntityIdsWithConsumerPublished()).thenReturn(Flux.just(ROOT));
        when(brokerPublisherService.findEntitiesAndItsSubentitiesById(eq(PROCESS_ID), any())).thenReturn(Flux.just(child));
        // A hash the node saw, but not the current content: maybe a local edit not notified yet
        when(auditRecordRepository.findKnownEntityHashesByEntityIds(any()))
                .thenReturn(Flux.just(known("urn:ngsi-ld:product-offering-price:1", "previousHash"),
                        known("urn:ngsi-ld:product-offering-price:other", sha256(child))));

        try (LogCapture logCapture = LogCapture.of(ImplicitRetrievedBackfillJobImpl.class)) {
            StepVerifier.create(job(500, true).run(PROCESS_ID))
                    .expectNext(new ImplicitRetrievedBackfillSummary(1, 1, 0, 1, 0, 0, 0))
                    .verifyComplete();

            assertThat(logCapture.messages(Level.INFO)).anySatisfy(line -> assertThat(line)
                    .contains("[backfill] ProcessID: processId - Skipped product-offering-price urn:ngsi-ld:product-offering-price:1"));
        }
        verifyNoInteractions(auditRecordService);
    }

    @Test
    void itShouldCountAsUpToDateTheSubentitiesNotWritten() {
        String first = entity("urn:ngsi-ld:product-offering-price:1", "product-offering-price");
        String second = entity("urn:ngsi-ld:product-offering-price:2", "product-offering-price");
        when(auditRecordRepository.findEntityIdsWithConsumerPublished()).thenReturn(Flux.just(ROOT));
        when(brokerPublisherService.findEntitiesAndItsSubentitiesById(eq(PROCESS_ID), any())).thenReturn(Flux.just(first, second));
        when(auditRecordRepository.findKnownEntityHashesByEntityIds(any()))
                .thenReturn(Flux.just(known("urn:ngsi-ld:product-offering-price:1", sha256(first)),
                        known("urn:ngsi-ld:product-offering-price:2", sha256(second))));
        when(auditRecordService.saveImplicitConsumerRetrieved(eq(PROCESS_ID), any())).thenReturn(Mono.just(1));

        try (LogCapture logCapture = LogCapture.of(ImplicitRetrievedBackfillJobImpl.class)) {
            StepVerifier.create(job(500, true).run(PROCESS_ID))
                    .expectNext(new ImplicitRetrievedBackfillSummary(1, 2, 0, 0, 1, 1, 0))
                    .verifyComplete();

            assertThat(logCapture.messages(Level.INFO)).anySatisfy(line -> assertThat(line)
                    .contains("[backfill] ProcessID: processId - END completed roots=1 subentities=2 skippedByType=0 "
                            + "skippedByUnknownHash=0 upToDate=1 inserted=1 failed=0 duration="));
        }
    }

    @Test
    void itShouldWriteInChunksOfTheBatchSize() throws Exception {
        List<String> children = List.of(
                entity("urn:ngsi-ld:product-offering-price:1", "product-offering-price"),
                entity("urn:ngsi-ld:product-offering-price:2", "product-offering-price"),
                entity("urn:ngsi-ld:product-offering-price:3", "product-offering-price"));
        stubKnownChildren(children);
        when(auditRecordService.saveImplicitConsumerRetrieved(eq(PROCESS_ID), writtenCaptor.capture()))
                .thenAnswer(invocation -> Mono.just(invocation.<List<?>>getArgument(1).size()));

        StepVerifier.create(job(2, true).run(PROCESS_ID))
                .expectNext(new ImplicitRetrievedBackfillSummary(1, 3, 0, 0, 0, 3, 0))
                .verifyComplete();

        assertThat(writtenCaptor.getAllValues()).extracting(List::size).containsExactly(2, 1);
    }

    @Test
    void aFailedChunkShouldNotStopTheOthersAndShouldFailTheRunWithItsSummary() throws Exception {
        List<String> children = List.of(
                entity("urn:ngsi-ld:product-offering-price:1", "product-offering-price"),
                entity("urn:ngsi-ld:product-offering-price:2", "product-offering-price"),
                entity("urn:ngsi-ld:product-offering-price:3", "product-offering-price"));
        stubKnownChildren(children);
        when(auditRecordService.saveImplicitConsumerRetrieved(eq(PROCESS_ID), any()))
                .thenReturn(Mono.error(new IllegalStateException("value too long for type character varying(256)")))
                .thenReturn(Mono.just(1));

        try (LogCapture logCapture = LogCapture.of(ImplicitRetrievedBackfillJobImpl.class)) {
            StepVerifier.create(job(2, true).run(PROCESS_ID))
                    .expectErrorSatisfies(error -> assertThat(error)
                            .isInstanceOf(ImplicitRetrievedBackfillException.class)
                            .hasMessage("2 of 3 subentities could not be recorded")
                            .extracting(e -> ((ImplicitRetrievedBackfillException) e).getSummary())
                            .isEqualTo(new ImplicitRetrievedBackfillSummary(1, 3, 0, 0, 0, 1, 2)))
                    .verify();

            assertThat(logCapture.messages(Level.ERROR))
                    .filteredOn(line -> line.contains("value too long"))
                    .hasSize(2)
                    .anySatisfy(line -> assertThat(line).contains("urn:ngsi-ld:product-offering-price:1"))
                    .anySatisfy(line -> assertThat(line).contains("urn:ngsi-ld:product-offering-price:2"));
            assertThat(logCapture.messages(Level.INFO))
                    .anySatisfy(line -> assertThat(line).contains(" - END incomplete roots=1 subentities=3 "));
        }
        verify(auditRecordService, times(2)).saveImplicitConsumerRetrieved(eq(PROCESS_ID), any());
    }

    @Test
    void aFailedReadOfTheAuditRecordsShouldCountTheChunkAsFailed() {
        String child = entity("urn:ngsi-ld:product-offering-price:1", "product-offering-price");
        when(auditRecordRepository.findEntityIdsWithConsumerPublished()).thenReturn(Flux.just(ROOT));
        when(brokerPublisherService.findEntitiesAndItsSubentitiesById(eq(PROCESS_ID), any())).thenReturn(Flux.just(child));
        when(auditRecordRepository.findKnownEntityHashesByEntityIds(any())).thenReturn(Flux.error(new IllegalStateException("db down")));

        StepVerifier.create(job(500, true).run(PROCESS_ID))
                .expectErrorSatisfies(error -> assertThat(((ImplicitRetrievedBackfillException) error).getSummary().failed())
                        .isEqualTo(1))
                .verify();

        verifyNoInteractions(auditRecordService);
    }

    @Test
    void aFailedReadOfTheBrokerShouldFailTheRunAndStillLogTheSummary() {
        when(auditRecordRepository.findEntityIdsWithConsumerPublished()).thenReturn(Flux.just(ROOT));
        when(brokerPublisherService.findEntitiesAndItsSubentitiesById(eq(PROCESS_ID), any()))
                .thenReturn(Flux.error(new IllegalStateException("broker down")));

        try (LogCapture logCapture = LogCapture.of(ImplicitRetrievedBackfillJobImpl.class)) {
            StepVerifier.create(job(500, true).run(PROCESS_ID))
                    .expectErrorMessage("broker down")
                    .verify();

            assertThat(logCapture.messages(Level.INFO))
                    .anySatisfy(line -> assertThat(line).contains(" - END failed roots=1 subentities=0 "));
        }
        verifyNoInteractions(auditRecordService);
    }

    private void stubKnownChildren(List<String> children) {
        when(auditRecordRepository.findEntityIdsWithConsumerPublished()).thenReturn(Flux.just(ROOT));
        when(brokerPublisherService.findEntitiesAndItsSubentitiesById(eq(PROCESS_ID), any())).thenReturn(Flux.fromIterable(children));
        List<AuditRecord> known = children.stream()
                .map(child -> known(idOf(child), sha256(child)))
                .toList();
        when(auditRecordRepository.findKnownEntityHashesByEntityIds(any())).thenAnswer(invocation -> {
            List<String> ids = Arrays.asList(invocation.<String[]>getArgument(0));
            return Flux.fromIterable(known).filter(auditRecord -> ids.contains(auditRecord.getEntityId()));
        });
    }

    private ImplicitRetrievedBackfillJobImpl job(int batchSize, boolean enabled) {
        return new ImplicitRetrievedBackfillJobImpl(auditRecordRepository, auditRecordService, brokerPublisherService,
                OBJECT_MAPPER, new AuditProperties(batchSize), enabled);
    }

    private static String entity(String id, String type) {
        return "{\"id\":\"" + id + "\",\"type\":\"" + type + "\",\"name\":{\"type\":\"Property\",\"value\":\"x\"}}";
    }

    private static AuditRecord known(String entityId, String entityHash) {
        return AuditRecord.builder().entityId(entityId).entityHash(entityHash).build();
    }

    private static String idOf(String body) {
        try {
            return OBJECT_MAPPER.readTree(body).path("id").asText();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String sha256(String body) {
        try {
            return ApplicationUtils.calculateSHA256(body);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
