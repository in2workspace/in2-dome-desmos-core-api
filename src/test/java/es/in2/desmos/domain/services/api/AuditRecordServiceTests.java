package es.in2.desmos.domain.services.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import es.in2.desmos.domain.models.*;
import es.in2.desmos.domain.repositories.AuditRecordRepository;
import es.in2.desmos.domain.services.api.impl.AuditChainWriter;
import es.in2.desmos.domain.services.api.impl.AuditRecordServiceImpl;
import es.in2.desmos.domain.services.broker.BrokerPublisherService;
import es.in2.desmos.domain.utils.ApplicationUtils;
import es.in2.desmos.infrastructure.configs.ApiConfig;
import es.in2.desmos.infrastructure.configs.EndpointsConfig;
import es.in2.desmos.objectmothers.AuditRecordMother;
import es.in2.desmos.objectmothers.EntityMother;
import es.in2.desmos.objectmothers.MVAuditServiceEntity4DataNegotiationMother;
import org.json.JSONException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.security.NoSuchAlgorithmException;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuditRecordServiceTests {

    @Mock
    private AuditRecordRepository auditRecordRepository;

    @Mock
    private BlockchainTxPayload blockchainTxPayload;

    @Mock
    private BrokerPublisherService brokerPublisherService;

    @Mock
    private ApiConfig apiConfig;

    @Mock
    private EndpointsConfig endpointsConfig;

    @SuppressWarnings("CanBeFinal")
    @Spy
    private static ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private AuditChainWriter auditChainWriter;

    private AuditRecordServiceImpl auditRecordService;

    @Captor
    private ArgumentCaptor<List<AuditRecord>> draftsCaptor;

    @BeforeEach
    void setUp() {
        auditRecordService = new AuditRecordServiceImpl(objectMapper, auditRecordRepository, brokerPublisherService,
                apiConfig, endpointsConfig, auditChainWriter);
    }

    @Test
    void itShouldAppendACreatedOrPublishedDraftFromABrokerNotification() throws Exception {
        String processId = "processId";
        String sampleDataLocation = "http://localhost:8080/ngsi-ld/v1/entities/" +
                "urn:ngsi-ld:ProductOffering:38088145-aef3-440e-ab93-a33bc9bfce69" +
                "?hl=03ac674216f3e15c761ee1a5e255f067953623c8b388b4459e13f978d7c846f4";
        Map<String, Object> dataMap = new HashMap<>();
        dataMap.put("id", "entityId");
        dataMap.put("type", "entityType");
        when(blockchainTxPayload.dataLocation()).thenReturn(sampleDataLocation);
        when(auditChainWriter.append(eq(processId), any(), eq("broker notification"))).thenReturn(Mono.empty());

        StepVerifier.create(auditRecordService.buildAndSaveAuditRecordFromBrokerNotification(processId, dataMap,
                        AuditRecordStatus.CREATED, blockchainTxPayload))
                .verifyComplete();

        verify(auditChainWriter).append(eq(processId), draftsCaptor.capture(), eq("broker notification"));
        verifyNoInteractions(auditRecordRepository);
        AuditRecord draft = singleUnchainedDraft();
        assertThat(draft.getEntityId()).isEqualTo("entityId");
        assertThat(draft.getEntityType()).isEqualTo("entityType");
        assertThat(draft.getStatus()).isEqualTo(AuditRecordStatus.CREATED);
        assertThat(draft.getTrader()).isEqualTo(AuditRecordTrader.PRODUCER);
        assertThat(draft.getEntityHash()).isEqualTo(ApplicationUtils.calculateSHA256(objectMapper.writeValueAsString(dataMap)));
        assertThat(draft.getEntityHashLink()).isEqualTo("03ac674216f3e15c761ee1a5e255f067953623c8b388b4459e13f978d7c846f4");
        assertThat(draft.getDataLocation()).isEqualTo(sampleDataLocation);
    }

    @Test
    void itShouldAppendAReceivedDraftFromABrokerNotificationWithoutPayload() {
        String processId = "processId";
        Map<String, Object> dataMap = new HashMap<>();
        dataMap.put("id", "entityId");
        dataMap.put("type", "entityType");
        when(auditChainWriter.append(eq(processId), any(), eq("broker notification"))).thenReturn(Mono.empty());

        StepVerifier.create(auditRecordService.buildAndSaveAuditRecordFromBrokerNotification(processId, dataMap,
                        AuditRecordStatus.RECEIVED, null))
                .verifyComplete();

        verify(auditChainWriter).append(eq(processId), draftsCaptor.capture(), eq("broker notification"));
        AuditRecord draft = singleUnchainedDraft();
        assertThat(draft.getStatus()).isEqualTo(AuditRecordStatus.RECEIVED);
        assertThat(draft.getTrader()).isEqualTo(AuditRecordTrader.PRODUCER);
        assertThat(draft.getEntityHash()).isEmpty();
        assertThat(draft.getEntityHashLink()).isEmpty();
        assertThat(draft.getDataLocation()).isEmpty();
    }

    @Test
    void itShouldFailWithoutWritingWhenTheBrokerEntityCannotBeHashed() throws Exception {
        Map<String, Object> dataMap = new HashMap<>();
        dataMap.put("id", "entityId");
        dataMap.put("type", "entityType");
        when(objectMapper.writeValueAsString(any())).thenThrow(JsonProcessingException.class);

        StepVerifier.create(auditRecordService.buildAndSaveAuditRecordFromBrokerNotification("processId", dataMap,
                        AuditRecordStatus.CREATED, blockchainTxPayload))
                .expectError(JsonProcessingException.class)
                .verify();

        verifyNoInteractions(auditChainWriter);
    }

    @Test
    void itShouldNotWriteBeforeTheNotificationAuditIsSubscribed() {
        Map<String, Object> dataMap = new HashMap<>();
        dataMap.put("id", "entityId");
        dataMap.put("type", "entityType");
        BlockchainNotification blockchainNotification = BlockchainNotification.builder()
                .dataLocation("http://localhost:8080/ngsi-ld/v1/entities/urn:e:1?hl=abc").eventType("entityType").build();

        auditRecordService.buildAndSaveAuditRecordFromBrokerNotification("processId", dataMap, AuditRecordStatus.RECEIVED, null);
        auditRecordService.buildAndSaveAuditRecordFromBlockchainNotification("processId", blockchainNotification, null, AuditRecordStatus.RECEIVED);
        auditRecordService.saveImplicitConsumerRetrieved("processId", List.of(new ImplicitRetrievedEntity("urn:e:1", "entityType", "{}")));

        verifyNoInteractions(auditChainWriter, auditRecordRepository);
    }

    @Test
    void itShouldPropagateTheWriterErrorFromANotification() {
        Map<String, Object> dataMap = new HashMap<>();
        dataMap.put("id", "entityId");
        dataMap.put("type", "entityType");
        RuntimeException error = new RuntimeException("lock timeout");
        when(auditChainWriter.append(any(), any(), any())).thenReturn(Mono.error(error));

        StepVerifier.create(auditRecordService.buildAndSaveAuditRecordFromBrokerNotification("processId", dataMap,
                        AuditRecordStatus.RECEIVED, null))
                .expectErrorMatches(error::equals)
                .verify();
    }

    // A notification draft: the writer sets createdAt, hash and hashLink inside the chain lock
    private AuditRecord singleUnchainedDraft() {
        assertThat(draftsCaptor.getValue()).hasSize(1);
        AuditRecord draft = draftsCaptor.getValue().get(0);
        assertThat(draft.getId()).isNotNull();
        assertThat(draft.getProcessId()).isEqualTo("processId");
        assertThat(draft.isNewTransaction()).isTrue();
        assertThat(draft.getCreatedAt()).isNull();
        assertThat(draft.getHash()).isNull();
        assertThat(draft.getHashLink()).isNull();
        return draft;
    }


    @Test
    void itShouldAppendOneDraftPerEntityFromDataSync() throws JSONException, NoSuchAlgorithmException, JsonProcessingException {
        String processId = "0";
        AuditRecordStatus status = AuditRecordStatus.RETRIEVED;
        List<MVAuditServiceEntity4DataNegotiation> entities = MVAuditServiceEntity4DataNegotiationMother.sample3and4();
        when(auditChainWriter.append(eq(processId), any(), eq("p2p sync"))).thenReturn(Mono.empty());

        StepVerifier.create(auditRecordService.buildAndSaveAuditRecordsFromDataSync(processId, entities, status))
                .verifyComplete();

        verify(auditChainWriter).append(eq(processId), draftsCaptor.capture(), eq("p2p sync"));
        verifyNoInteractions(auditRecordRepository);
        List<AuditRecord> drafts = draftsCaptor.getValue();
        assertThat(drafts).hasSize(entities.size());
        for (int i = 0; i < entities.size(); i++) {
            AuditRecord expected = AuditRecordMother.createAuditRecordFromMVAuditServiceEntity4DataNegotiation(entities.get(i), status);
            expected.setCreatedAt(null);
            expected.setHash(null);
            expected.setHashLink(null);
            assertThat(drafts.get(i))
                    .usingRecursiveComparison()
                    .ignoringFields("id")
                    .isEqualTo(expected);
            assertThat(drafts.get(i).getId()).isNotNull();
        }
    }

    @Test
    void itShouldPropagateTheWriterErrorFromDataSync() throws JSONException, NoSuchAlgorithmException, JsonProcessingException {
        RuntimeException error = new RuntimeException("db down");
        when(auditChainWriter.append(any(), any(), any())).thenReturn(Mono.error(error));

        StepVerifier.create(auditRecordService.buildAndSaveAuditRecordsFromDataSync("0",
                        MVAuditServiceEntity4DataNegotiationMother.sample3and4(), AuditRecordStatus.RECEIVED))
                .expectErrorMatches(error::equals)
                .verify();
    }

    @Test
    void itShouldAppendARetrievedOrPublishedDraftFromABlockchainNotification() throws Exception {
        String processId = "processId";
        String entityId = "urn:ngsi-ld:ProductOffering:38088145-aef3-440e-ab93-a33bc9bfce69";
        String sampleDataLocation = "http://localhost:8080/ngsi-ld/v1/entities/" + entityId +
                "?hl=03ac674216f3e15c761ee1a5e255f067953623c8b388b4459e13f978d7c846f4";
        BlockchainNotification blockchainNotification = BlockchainNotification.builder()
                .dataLocation(sampleDataLocation)
                .eventType("entityType")
                .build();
        String retrievedBrokerEntity = "sampleData";
        when(auditChainWriter.append(eq(processId), any(), eq("blockchain notification"))).thenReturn(Mono.empty());

        StepVerifier.create(auditRecordService.buildAndSaveAuditRecordFromBlockchainNotification(processId,
                        blockchainNotification, retrievedBrokerEntity, AuditRecordStatus.RETRIEVED))
                .verifyComplete();

        verify(auditChainWriter).append(eq(processId), draftsCaptor.capture(), eq("blockchain notification"));
        verifyNoInteractions(auditRecordRepository);
        AuditRecord draft = singleUnchainedDraft();
        assertThat(draft.getEntityId()).isEqualTo(entityId);
        assertThat(draft.getEntityType()).isEqualTo("entityType");
        assertThat(draft.getStatus()).isEqualTo(AuditRecordStatus.RETRIEVED);
        assertThat(draft.getTrader()).isEqualTo(AuditRecordTrader.CONSUMER);
        assertThat(draft.getEntityHash()).isEqualTo(ApplicationUtils.calculateSHA256(retrievedBrokerEntity));
        assertThat(draft.getEntityHashLink()).isEqualTo("03ac674216f3e15c761ee1a5e255f067953623c8b388b4459e13f978d7c846f4");
        assertThat(draft.getDataLocation()).isEqualTo(sampleDataLocation);
    }

    @Test
    void itShouldAppendAReceivedDraftFromABlockchainNotificationWithoutEntity() {
        String processId = "processId";
        String entityId = "urn:ngsi-ld:ProductOffering:38088145-aef3-440e-ab93-a33bc9bfce69";
        String dataLocation = "http://localhost:8080/ngsi-ld/v1/entities/" + entityId
                + "?hl=dbff5341acad5e2a58db4efd5e72e2d9a0a843a28e02b1183c68162d0a3a3de6";
        BlockchainNotification blockchainNotification = BlockchainNotification.builder()
                .dataLocation(dataLocation).build();
        when(auditChainWriter.append(eq(processId), any(), eq("blockchain notification"))).thenReturn(Mono.empty());

        StepVerifier.create(auditRecordService.buildAndSaveAuditRecordFromBlockchainNotification(processId,
                        blockchainNotification, null, AuditRecordStatus.RECEIVED))
                .verifyComplete();

        verify(auditChainWriter).append(eq(processId), draftsCaptor.capture(), eq("blockchain notification"));
        AuditRecord draft = singleUnchainedDraft();
        assertThat(draft.getEntityId()).isEqualTo(entityId);
        assertThat(draft.getStatus()).isEqualTo(AuditRecordStatus.RECEIVED);
        assertThat(draft.getTrader()).isEqualTo(AuditRecordTrader.CONSUMER);
        assertThat(draft.getEntityHash()).isEmpty();
        assertThat(draft.getEntityHashLink()).isEmpty();
        assertThat(draft.getDataLocation()).isEqualTo(dataLocation);
    }

    @Test
    void testFetchLatestProducerEntityHashLinkByEntityId() {
        //Arrange
        String processId = "processId";
        String entityId = "entityId";
        AuditRecord auditRecord = AuditRecord.builder()
                .entityHash("entityHash")
                .entityHashLink("entityHashLink")
                .build();
        when(auditRecordService.getLastPublishedAuditRecordForProducerByEntityId(processId, entityId)).thenReturn(Mono.just(auditRecord));
        // Act
        String actualEntityHashLink = auditRecordService.fetchLatestProducerEntityHashLinkByEntityId(processId, entityId).block();
        // Assert
        assertEquals(auditRecord.getEntityHashLink(), actualEntityHashLink);
    }

    @Test
    void testFindLatestConsumerPublishedAuditRecordByEntityId() {
        // Arrange
        String processId = "processId";
        String entityId = "entityId";
        AuditRecord auditRecord = new AuditRecord();
        auditRecord.setEntityHash("entityHash");
        when(auditRecordRepository.findLastPublishedConsumerAuditRecordByEntityId(entityId)).thenReturn(Mono.just(auditRecord));
        // Act
        AuditRecord actualAuditRecord = auditRecordService.findLatestConsumerPublishedAuditRecordByEntityId(processId, entityId).block();
        // Assert
        assertEquals(auditRecord, actualAuditRecord);
    }

    @Test
    void testFindLatestConsumerPublishedAuditRecord() {
        // Arrange
        String processId = "processId";
        AuditRecord auditRecord = new AuditRecord();
        auditRecord.setEntityHash("entityHash");
        when(auditRecordRepository.findLastPublishedConsumerAuditRecord()).thenReturn(Mono.just(auditRecord));
        // Act
        AuditRecord actualAuditRecord = auditRecordService.findLatestConsumerPublishedAuditRecord(processId).block();
        // Assert
        assertEquals(auditRecord, actualAuditRecord);
    }

    @Test
    void testFindLatestAuditRecordForEntity() {
        // Arrange
        String processId = "processId";
        String entityId = "entityId";
        AuditRecord auditRecord = new AuditRecord();
        auditRecord.setEntityHash("entityHash");
        when(auditRecordRepository.findMostRecentRetrievedOrDeletedByEntityId(entityId)).thenReturn(Mono.just(auditRecord));
        // Act
        AuditRecord actualAuditRecord = auditRecordService.findMostRecentRetrievedOrDeletedByEntityId(processId, entityId).block();
        // Assert
        assertEquals(auditRecord, actualAuditRecord);
    }

    @Test
    void itShouldReturnExistingAuditRecordIfEntityHasAuditRecordAndHashIsEqualsToCurrentEntityHash() throws JSONException, NoSuchAlgorithmException, JsonProcessingException {
        String processId = "0";
        var productOffering3Mono = Mono.just(EntityMother.PRODUCT_OFFERING_3);
        var productOffering4Mono = Mono.just(EntityMother.PRODUCT_OFFERING_4);
        when(brokerPublisherService.getEntityById(eq(processId), any()))
                .thenReturn(productOffering3Mono)
                .thenReturn(productOffering4Mono);

        when(auditRecordRepository.findMostRecentRetrievedOrDeletedByEntityIds(any()))
                .thenReturn(Flux.empty());

        when(auditRecordRepository.findMostRecentPublishedAuditRecordsByEntityIds(any()))
                .thenReturn(Flux.just(AuditRecordMother.list3And4().get(0), AuditRecordMother.list3And4().get(1)));

        when(auditChainWriter.append(eq(processId), any(), eq("unregistered or outdated entity")))
                .thenReturn(Mono.empty());

        var expectedAuditEntities = MVAuditServiceEntity4DataNegotiationMother.sample3and4();
        var entityIdsFlux = Flux.fromIterable(List.of(expectedAuditEntities.get(0).id(), expectedAuditEntities.get(1).id()));

        Flux<MVAuditServiceEntity4DataNegotiation> resultFlux =
                auditRecordService.findCreateOrUpdateAuditRecordsByEntityIds(processId, expectedAuditEntities.get(0).type(), entityIdsFlux);

        StepVerifier
                .create(resultFlux.collectList())
                .assertNext(result ->
                        assertThat(result).isEqualTo(expectedAuditEntities))
                .verifyComplete();

        verify(auditChainWriter).append(processId, List.of(), "unregistered or outdated entity");
    }

    @Test
    void itShouldNotCreateAuditRecordIfEntityHasAuditRecordAndHashIsEqualsToCurrentEntityHash() throws JSONException, NoSuchAlgorithmException, JsonProcessingException {
        String processId = "0";
        var productOffering3Mono = Mono.just(EntityMother.PRODUCT_OFFERING_3);
        var productOffering4Mono = Mono.just(EntityMother.PRODUCT_OFFERING_4);
        when(brokerPublisherService.getEntityById(eq(processId), any()))
                .thenReturn(productOffering3Mono)
                .thenReturn(productOffering4Mono);

        when(auditRecordRepository.findMostRecentRetrievedOrDeletedByEntityIds(any()))
                .thenReturn(Flux.empty());

        when(auditRecordRepository.findMostRecentPublishedAuditRecordsByEntityIds(any()))
                .thenReturn(
                        Flux.just(
                                AuditRecordMother.list3And4().get(0),
                                AuditRecordMother.list3And4().get(1)));

        when(auditChainWriter.append(eq(processId), any(), eq("unregistered or outdated entity")))
                .thenReturn(Mono.empty());

        var expectedAuditEntities = MVAuditServiceEntity4DataNegotiationMother.sample3and4();
        var entityIdsMono = Flux.fromIterable(List.of(expectedAuditEntities.get(0).id(), expectedAuditEntities.get(1).id()));

        Flux<MVAuditServiceEntity4DataNegotiation> resultMono =
                auditRecordService.findCreateOrUpdateAuditRecordsByEntityIds(processId, expectedAuditEntities.get(0).type(), entityIdsMono);

        StepVerifier
                .create(resultMono.collectList())
                .expectNextCount(1)
                .verifyComplete();

        verify(auditRecordRepository, never()).save(any());
        verify(auditChainWriter).append(processId, List.of(), "unregistered or outdated entity");
        verifyNoMoreInteractions(auditRecordRepository);
    }

    @Test
    void itShouldReturnNewAuditRecordIfEntityHasAuditRecordAndHashIsNotEqualsToCurrentEntityHash() throws JSONException, NoSuchAlgorithmException, JsonProcessingException {
        String processId = "0";
        var productOffering3Mono = Mono.just(EntityMother.PRODUCT_OFFERING_3);
        var productOffering4Mono = Mono.just(EntityMother.PRODUCT_OFFERING_4);
        when(brokerPublisherService.getEntityById(eq(processId), any()))
                .thenReturn(productOffering3Mono)
                .thenReturn(productOffering4Mono);

        when(auditRecordRepository.findMostRecentRetrievedOrDeletedByEntityIds(any()))
                .thenReturn(Flux.empty());

        when(auditRecordRepository.findMostRecentPublishedAuditRecordsByEntityIds(any()))
                .thenReturn(
                        Flux.just(
                                AuditRecordMother.list3OtherHashTraderProducerAnd4().get(0),
                                AuditRecordMother.list3OtherHashTraderProducerAnd4().get(1)));

        when(auditChainWriter.append(eq(processId), any(), eq("unregistered or outdated entity")))
                .thenReturn(Mono.empty());

        var expectedAuditEntities = MVAuditServiceEntity4DataNegotiationMother.sample3and4NewHashlink();
        var entityIdsMono = Flux.fromIterable(List.of(expectedAuditEntities.get(0).id(), expectedAuditEntities.get(1).id()));

        Flux<MVAuditServiceEntity4DataNegotiation> resultMono =
                auditRecordService.findCreateOrUpdateAuditRecordsByEntityIds(processId, expectedAuditEntities.get(0).type(), entityIdsMono);

        StepVerifier
                .create(resultMono.collectList())
                .assertNext(result ->
                        assertThat(result).containsExactlyInAnyOrderElementsOf(expectedAuditEntities))
                .verifyComplete();

        verify(auditChainWriter).append(eq(processId), draftsCaptor.capture(), eq("unregistered or outdated entity"));
        assertThat(draftsCaptor.getValue()).singleElement()
                .satisfies(draft -> {
                    assertThat(draft.getEntityId()).isEqualTo(expectedAuditEntities.get(0).id());
                    assertThat(draft.getEntityHashLink()).isEqualTo(expectedAuditEntities.get(0).hashlink());
                    assertThat(draft.getStatus()).isEqualTo(AuditRecordStatus.PUBLISHED);
                    assertThat(draft.getTrader()).isEqualTo(AuditRecordTrader.PRODUCER);
                    assertThat(draft.getProcessId()).isEqualTo(processId);
                });
    }

    @Test
    void itShouldCreateNewAuditRecordWithTraderConsumerAndDataLocationEmptyIfEntityHasAuditRecordAndHashIsNotEqualsToCurrentEntityHashAndTraderIsConsumer() throws JSONException, NoSuchAlgorithmException, JsonProcessingException {

        String processId = "0";
        var expectedAuditEntities = MVAuditServiceEntity4DataNegotiationMother.sample3and4NewHashlink();

       Flux<String> entityIdsMono = Flux.fromIterable(List.of(expectedAuditEntities.get(0).id(), expectedAuditEntities.get(1).id()));

        when(brokerPublisherService.getEntityById(eq(processId), any()))
                .thenReturn(Mono.just(EntityMother.PRODUCT_OFFERING_3))
                .thenReturn(Mono.just(EntityMother.PRODUCT_OFFERING_4));

        when(auditRecordRepository.findMostRecentRetrievedOrDeletedByEntityIds(any()))
                .thenReturn(Flux.empty());

        when(auditRecordRepository.findMostRecentPublishedAuditRecordsByEntityIds(any()))
                .thenReturn(
                        Flux.just(
                                AuditRecordMother.list3OtherHashWithTraderConsumerAnd4().get(0),
                                AuditRecordMother.list3OtherHashWithTraderConsumerAnd4().get(1)));

        when(auditChainWriter.append(eq(processId), any(), eq("unregistered or outdated entity")))
                .thenReturn(Mono.empty());

        Flux<MVAuditServiceEntity4DataNegotiation> resultMono =
                auditRecordService.findCreateOrUpdateAuditRecordsByEntityIds(processId, expectedAuditEntities.get(0).type(), entityIdsMono);

        StepVerifier
                .create(resultMono.collectList())
                .expectNextCount(1)
                .verifyComplete();

        verify(auditChainWriter).append(eq(processId), draftsCaptor.capture(), eq("unregistered or outdated entity"));
        verifyNoMoreInteractions(auditRecordRepository);

        assertThat(draftsCaptor.getValue()).hasSize(1);
        var auditRecordSaved = draftsCaptor.getValue().get(0);
        assertThat(auditRecordSaved)
                .usingRecursiveComparison()
                .comparingOnlyFields("entityId", "entityType", "entityHash", "entityHashLink", "trader", "dataLocation")
                .isEqualTo(
                        AuditRecord
                                .builder()
                                .entityId(expectedAuditEntities.get(0).id())
                                .entityType(expectedAuditEntities.get(0).type())
                                .entityHash(expectedAuditEntities.get(0).hash())
                                .entityHashLink(expectedAuditEntities.get(0).hashlink())
                                .trader(AuditRecordTrader.CONSUMER)
                                .dataLocation(""));
    }

    @Test
    void itShouldCreateAndReturnAuditRecordIfEntityHasNotAuditRecord() throws JSONException, NoSuchAlgorithmException, JsonProcessingException {

        String processId = "0";
        var expectedAuditEntities = MVAuditServiceEntity4DataNegotiationMother.sample3EqualsHashAndHashlinkAnd4();

        Flux<String> entityIdsMono = Flux.fromIterable(List.of(expectedAuditEntities.get(0).id(), expectedAuditEntities.get(1).id()));

        when(brokerPublisherService.getEntityById(eq(processId), any()))
                .thenReturn(Mono.just(EntityMother.PRODUCT_OFFERING_3))
                .thenReturn(Mono.just(EntityMother.PRODUCT_OFFERING_4));

        when(auditRecordRepository.findMostRecentRetrievedOrDeletedByEntityIds(any()))
                .thenReturn(Flux.empty());

        when(auditRecordRepository.findMostRecentPublishedAuditRecordsByEntityIds(any()))
                .thenReturn(Flux.just(AuditRecordMother.list3And4().get(1)));

        when(apiConfig.getExternalDomain())
                .thenReturn("http://my-external-domain.org");

        when(auditChainWriter.append(eq(processId), any(), eq("unregistered or outdated entity")))
                .thenReturn(Mono.empty());

        Flux<MVAuditServiceEntity4DataNegotiation> resultMono =
                auditRecordService.findCreateOrUpdateAuditRecordsByEntityIds(processId, expectedAuditEntities.get(0).type(), entityIdsMono);

        StepVerifier
                .create(resultMono.collectList())
                .assertNext(result ->
                        assertThat(result).containsExactlyInAnyOrderElementsOf(expectedAuditEntities))
                .verifyComplete();

        verify(auditChainWriter).append(eq(processId), draftsCaptor.capture(), eq("unregistered or outdated entity"));
        verifyNoMoreInteractions(auditRecordRepository);

        assertThat(draftsCaptor.getValue()).hasSize(1);
        var auditRecordSaved = draftsCaptor.getValue().get(0);
        assertThat(auditRecordSaved)
                .satisfies(auditRecord -> {
                    assertThat(auditRecord.getDataLocation())
                            .isNotNull()
                            .isNotBlank();

                    assertThat(auditRecordSaved)
                            .usingRecursiveComparison()
                            .comparingOnlyFields("entityId", "entityType", "entityHash", "entityHashLink", "trader")
                            .isEqualTo(
                                    AuditRecord
                                            .builder()
                                            .entityId(expectedAuditEntities.get(0).id())
                                            .entityType(expectedAuditEntities.get(0).type())
                                            .entityHash(expectedAuditEntities.get(0).hash())
                                            .entityHashLink(expectedAuditEntities.get(0).hash())
                                            .trader(AuditRecordTrader.PRODUCER));
                });
    }

    @Test
    void itShouldAnnounceWithoutAuditingAsProducerAnEntityThatOnlyHasAConsumerRetrievedRecord() throws JSONException, NoSuchAlgorithmException, JsonProcessingException {
        String processId = "0";
        var expectedAuditEntities = MVAuditServiceEntity4DataNegotiationMother.sample3EqualsHashAndHashlinkAnd4();
        Flux<String> entityIds = Flux.fromIterable(List.of(expectedAuditEntities.get(0).id(), expectedAuditEntities.get(1).id()));
        when(brokerPublisherService.getEntityById(eq(processId), any()))
                .thenReturn(Mono.just(EntityMother.PRODUCT_OFFERING_3))
                .thenReturn(Mono.just(EntityMother.PRODUCT_OFFERING_4));
        // Entity 3 has no PUBLISHED record, only the implicit RETRIEVED of a subentity written from another node
        when(auditRecordRepository.findMostRecentRetrievedOrDeletedByEntityIds(any()))
                .thenReturn(Flux.just(retrievedRecord(expectedAuditEntities.get(0).id(), "hashOfTheRetrievedBody")));
        when(auditRecordRepository.findMostRecentPublishedAuditRecordsByEntityIds(any()))
                .thenReturn(Flux.just(AuditRecordMother.list3And4().get(1)));
        when(auditChainWriter.append(eq(processId), any(), eq("unregistered or outdated entity")))
                .thenReturn(Mono.empty());

        StepVerifier
                .create(auditRecordService.findCreateOrUpdateAuditRecordsByEntityIds(processId, expectedAuditEntities.get(0).type(), entityIds)
                        .collectList())
                .assertNext(result ->
                        assertThat(result).containsExactlyInAnyOrderElementsOf(expectedAuditEntities))
                .verifyComplete();

        verify(auditChainWriter).append(eq(processId), draftsCaptor.capture(), eq("unregistered or outdated entity"));
        assertThat(draftsCaptor.getValue()).as("no PRODUCER record for the entity retrieved from another node").isEmpty();
        verifyNoInteractions(apiConfig);
    }

    @Test
    void itShouldAuditAsProducerAnEntityWhoseLastRetrievedRecordIsNotConsumer() throws JSONException, NoSuchAlgorithmException, JsonProcessingException {
        String processId = "0";
        var expectedAuditEntities = MVAuditServiceEntity4DataNegotiationMother.sample3EqualsHashAndHashlinkAnd4();
        Flux<String> entityIds = Flux.fromIterable(List.of(expectedAuditEntities.get(0).id(), expectedAuditEntities.get(1).id()));
        when(brokerPublisherService.getEntityById(eq(processId), any()))
                .thenReturn(Mono.just(EntityMother.PRODUCT_OFFERING_3))
                .thenReturn(Mono.just(EntityMother.PRODUCT_OFFERING_4));
        AuditRecord producerRecord = retrievedRecord(expectedAuditEntities.get(0).id(), "someHash").toBuilder()
                .trader(AuditRecordTrader.PRODUCER)
                .build();
        when(auditRecordRepository.findMostRecentRetrievedOrDeletedByEntityIds(any()))
                .thenReturn(Flux.just(producerRecord));
        when(auditRecordRepository.findMostRecentPublishedAuditRecordsByEntityIds(any()))
                .thenReturn(Flux.just(AuditRecordMother.list3And4().get(1)));
        when(apiConfig.getExternalDomain())
                .thenReturn("http://my-external-domain.org");
        when(auditChainWriter.append(eq(processId), any(), eq("unregistered or outdated entity")))
                .thenReturn(Mono.empty());

        StepVerifier
                .create(auditRecordService.findCreateOrUpdateAuditRecordsByEntityIds(processId, expectedAuditEntities.get(0).type(), entityIds)
                        .collectList())
                .assertNext(result ->
                        assertThat(result).containsExactlyInAnyOrderElementsOf(expectedAuditEntities))
                .verifyComplete();

        verify(auditChainWriter).append(eq(processId), draftsCaptor.capture(), eq("unregistered or outdated entity"));
        assertThat(draftsCaptor.getValue())
                .singleElement()
                .satisfies(draft -> {
                    assertThat(draft.getEntityId()).isEqualTo(expectedAuditEntities.get(0).id());
                    assertThat(draft.getStatus()).isEqualTo(AuditRecordStatus.PUBLISHED);
                    assertThat(draft.getTrader()).isEqualTo(AuditRecordTrader.PRODUCER);
                });
    }

    @Test
    void itShouldAppendAnImplicitRetrievedDraftForASubentityWithoutRecords() throws Exception {
        String body = "{\"id\":\"urn:e:1\",\"type\":\"product-offering-price\"}";
        when(auditRecordRepository.findMostRecentRetrievedOrDeletedByEntityIds(new String[]{"urn:e:1"})).thenReturn(Flux.empty());
        when(auditChainWriter.append(eq("processId"), any(), eq("implicit subentity"))).thenReturn(Mono.empty());

        StepVerifier.create(auditRecordService.saveImplicitConsumerRetrieved("processId",
                        List.of(new ImplicitRetrievedEntity("urn:e:1", "product-offering-price", body))))
                .expectNext(1)
                .verifyComplete();

        verify(auditChainWriter).append(eq("processId"), draftsCaptor.capture(), eq("implicit subentity"));
        AuditRecord draft = singleUnchainedDraft();
        String entityHash = ApplicationUtils.calculateSHA256(body);
        assertThat(draft.getEntityId()).isEqualTo("urn:e:1");
        assertThat(draft.getEntityType()).isEqualTo("product-offering-price");
        assertThat(draft.getEntityHash()).isEqualTo(entityHash);
        assertThat(draft.getEntityHashLink()).isEqualTo(entityHash);
        assertThat(draft.getDataLocation()).isEmpty();
        assertThat(draft.getStatus()).isEqualTo(AuditRecordStatus.RETRIEVED);
        assertThat(draft.getTrader()).isEqualTo(AuditRecordTrader.CONSUMER);
    }

    @Test
    void itShouldNotAppendAnImplicitRetrievedDraftWhenTheLastRecordHasTheSameHash() throws Exception {
        String body = "{\"id\":\"urn:e:1\"}";
        when(auditRecordRepository.findMostRecentRetrievedOrDeletedByEntityIds(new String[]{"urn:e:1"}))
                .thenReturn(Flux.just(retrievedRecord("urn:e:1", ApplicationUtils.calculateSHA256(body))));

        StepVerifier.create(auditRecordService.saveImplicitConsumerRetrieved("processId",
                        List.of(new ImplicitRetrievedEntity("urn:e:1", "type", body))))
                .expectNext(0)
                .verifyComplete();

        verifyNoInteractions(auditChainWriter);
    }

    @Test
    void itShouldAppendAnImplicitRetrievedDraftWhenTheLastRecordHasAnotherHash() {
        when(auditRecordRepository.findMostRecentRetrievedOrDeletedByEntityIds(new String[]{"urn:e:1"}))
                .thenReturn(Flux.just(retrievedRecord("urn:e:1", "anotherHash")));
        when(auditChainWriter.append(eq("processId"), any(), eq("implicit subentity"))).thenReturn(Mono.empty());

        StepVerifier.create(auditRecordService.saveImplicitConsumerRetrieved("processId",
                        List.of(new ImplicitRetrievedEntity("urn:e:1", "type", "{\"id\":\"urn:e:1\"}"))))
                .expectNext(1)
                .verifyComplete();

        verify(auditChainWriter).append(eq("processId"), draftsCaptor.capture(), eq("implicit subentity"));
        assertThat(singleUnchainedDraft().getEntityId()).isEqualTo("urn:e:1");
    }

    @Test
    void itShouldAppendInOneWriteOnlyTheImplicitRetrievedDraftsWhoseHashChanged() throws Exception {
        String unchangedBody = "{\"id\":\"urn:e:1\"}";
        String changedBody = "{\"id\":\"urn:e:2\",\"v\":2}";
        String newBody = "{\"id\":\"urn:e:3\"}";
        when(auditRecordRepository.findMostRecentRetrievedOrDeletedByEntityIds(new String[]{"urn:e:1", "urn:e:2", "urn:e:3"}))
                .thenReturn(Flux.just(
                        retrievedRecord("urn:e:1", ApplicationUtils.calculateSHA256(unchangedBody)),
                        retrievedRecord("urn:e:2", "previousHash")));
        when(auditChainWriter.append(eq("processId"), any(), eq("implicit subentity"))).thenReturn(Mono.empty());

        StepVerifier.create(auditRecordService.saveImplicitConsumerRetrieved("processId", List.of(
                        new ImplicitRetrievedEntity("urn:e:1", "type", unchangedBody),
                        new ImplicitRetrievedEntity("urn:e:2", "type", changedBody),
                        new ImplicitRetrievedEntity("urn:e:3", "type", newBody))))
                .expectNext(2)
                .verifyComplete();

        verify(auditChainWriter, times(1)).append(eq("processId"), draftsCaptor.capture(), eq("implicit subentity"));
        assertThat(draftsCaptor.getValue())
                .extracting(AuditRecord::getEntityId, AuditRecord::getEntityHash)
                .containsExactly(
                        tuple("urn:e:2", ApplicationUtils.calculateSHA256(changedBody)),
                        tuple("urn:e:3", ApplicationUtils.calculateSHA256(newBody)));
    }

    @Test
    void itShouldPropagateTheWriterErrorFromAnImplicitRetrieved() {
        RuntimeException error = new RuntimeException("lock timeout");
        when(auditRecordRepository.findMostRecentRetrievedOrDeletedByEntityIds(any())).thenReturn(Flux.empty());
        when(auditChainWriter.append(any(), any(), any())).thenReturn(Mono.error(error));

        StepVerifier.create(auditRecordService.saveImplicitConsumerRetrieved("processId",
                        List.of(new ImplicitRetrievedEntity("urn:e:1", "type", "{}"))))
                .expectErrorMatches(error::equals)
                .verify();
    }

    @Test
    void itShouldDoNothingForAnEmptyListOfImplicitRetrievedEntities() {
        StepVerifier.create(auditRecordService.saveImplicitConsumerRetrieved("processId", List.of()))
                .expectNext(0)
                .verifyComplete();

        verifyNoInteractions(auditChainWriter, auditRecordRepository);
    }

    private static AuditRecord retrievedRecord(String entityId, String entityHash) {
        return AuditRecord.builder()
                .entityId(entityId)
                .entityHash(entityHash)
                .status(AuditRecordStatus.RETRIEVED)
                .trader(AuditRecordTrader.CONSUMER)
                .build();
    }
}
