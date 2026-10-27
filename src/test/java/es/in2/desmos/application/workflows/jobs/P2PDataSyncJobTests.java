package es.in2.desmos.application.workflows.jobs;

import com.fasterxml.jackson.core.JsonProcessingException;
import es.in2.desmos.application.workflows.jobs.impl.P2PDataSyncJobImpl;
import es.in2.desmos.domain.events.DataNegotiationEventPublisher;
import es.in2.desmos.domain.models.*;
import es.in2.desmos.domain.services.api.impl.AuditRecordServiceImpl;
import es.in2.desmos.domain.services.broker.impl.BrokerPublisherServiceImpl;
import es.in2.desmos.domain.services.policies.ReplicationPoliciesService;
import es.in2.desmos.domain.services.sync.DiscoverySyncWebClient;
import es.in2.desmos.domain.utils.ApplicationConstants;
import es.in2.desmos.infrastructure.configs.ApiConfig;
import es.in2.desmos.infrastructure.configs.ExternalAccessNodesConfig;
import es.in2.desmos.objectmothers.*;
import org.json.JSONArray;
import org.json.JSONException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class P2PDataSyncJobTests {
    @InjectMocks
    private P2PDataSyncJobImpl p2PDataSyncJob;

    @Mock
    private ExternalAccessNodesConfig externalAccessNodesConfig;

    @Mock
    private ApiConfig apiConfig;

    @Mock
    private BrokerPublisherServiceImpl brokerPublisherService;

    @Mock
    private AuditRecordServiceImpl auditRecordService;

    @Mock
    private DataNegotiationEventPublisher dataNegotiationEventPublisher;

    @Mock
    private DiscoverySyncWebClient discoverySyncWebClient;

    @Mock
    private DataNegotiationJob dataNegotiationJob;

    @Mock
    private ReplicationPoliciesService replicationPoliciesService;

    @Test
    void itShouldSynchronizeData() throws JSONException, NoSuchAlgorithmException, JsonProcessingException {
        String processId = "0";

        var brokerEntities = MVBrokerEntity4DataNegotiationMother.list3And4();
        var brokerCategories = MVBrokerEntity4DataNegotiationMother.listCategories();
        var brokerCatalogs = MVBrokerEntity4DataNegotiationMother.listCatalogs();

        when(brokerPublisherService.findAllIdTypeAndAttributesByType(
                eq(processId),
                anyString(),
                eq("lastUpdate"),
                eq("version"),
                eq("lifecycleStatus"),
                eq("validFor"),
                eq(BrokerEntityWithIdTypeLastUpdateAndVersion.class))
        ).thenAnswer(invocation -> {
            String entityType = invocation.getArgument(1);
            List<?> result = switch (entityType) {
                case MVEntity4DataNegotiationMother.PRODUCT_OFFERING_TYPE_NAME -> brokerEntities;
                case MVEntity4DataNegotiationMother.CATEGORY_TYPE_NAME -> brokerCategories;
                case MVEntity4DataNegotiationMother.CATALOG_TYPE_NAME -> brokerCatalogs;
                default -> Collections.emptyList();
            };

            return Flux.fromIterable(result);
        });

        when(auditRecordService.findCreateOrUpdateAuditRecordsByEntityIds(eq(processId), any(), any()))
                .thenAnswer(invocation -> {
                    String entityType = invocation.getArgument(1);
                    return switch (entityType) {
                        case MVEntity4DataNegotiationMother.PRODUCT_OFFERING_TYPE_NAME ->
                                Flux.fromIterable(MVAuditServiceEntity4DataNegotiationMother.sample3and4());
                        case MVEntity4DataNegotiationMother.CATEGORY_TYPE_NAME ->
                                Flux.fromIterable(MVAuditServiceEntity4DataNegotiationMother.listCategories());
                        case MVEntity4DataNegotiationMother.CATALOG_TYPE_NAME ->
                                Flux.fromIterable(MVAuditServiceEntity4DataNegotiationMother.listCatalogs());
                        default -> Flux.fromIterable(Collections.emptyList());
                    };
                });

        when(discoverySyncWebClient.makeRequest(eq(processId), any(), any(), any()))
                .thenReturn(Flux.just(MVEntity4DataNegotiationMother.sample3()))
                .thenReturn( Flux.just(MVEntity4DataNegotiationMother.sample4()));

        List<String> urlExternalAccessNodesList = UrlMother.example1And2urlsList();
        when(externalAccessNodesConfig.getExternalAccessNodesUrls()).thenReturn(Mono.just(urlExternalAccessNodesList));

        when(replicationPoliciesService.filterReplicableMvEntitiesList(
                eq(processId), any(Flux.class)))
                .thenAnswer(invocation -> {
                    Flux<MVEntityReplicationPoliciesInfo> fluxArg = invocation.getArgument(1);
                    return fluxArg.map(info -> new Id(info.id()));
                });

        when(dataNegotiationJob.negotiateDataSyncWithMultipleIssuers(eq(processId), any(), any()))
                .thenAnswer(invocation -> {
                    Mono<Map<Issuer, Flux<MVEntity4DataNegotiation>>> externalEntitiesMono = invocation.getArgument(1);
                    return externalEntitiesMono.flatMapMany(map -> Flux.merge(map.values()))
                            .then(); // Así se suscriben los Flux y se dispara makeRequest
                });

        StepVerifier
                .create(p2PDataSyncJob.synchronizeData(processId))
                .expectComplete()
                .verify();


        // Read only once: the genesis has nothing newer than the local entities, so they are not read again
        verify(brokerPublisherService, times(1)).findAllIdTypeAndAttributesByType(processId, MVEntity4DataNegotiationMother.PRODUCT_OFFERING_TYPE_NAME, "lastUpdate", "version", "lifecycleStatus", "validFor", BrokerEntityWithIdTypeLastUpdateAndVersion.class);
        verifyNoMoreInteractions(brokerPublisherService);

        //Should be number of times as BROKER_ENTITY_TYPES lenght is
        verify(auditRecordService, atLeastOnce()).findCreateOrUpdateAuditRecordsByEntityIds(eq(processId), any(), any());
        verifyNoMoreInteractions(auditRecordService);
        verify(externalAccessNodesConfig, atLeastOnce()).getExternalAccessNodesUrls();
        verifyNoMoreInteractions(externalAccessNodesConfig);

        verify(discoverySyncWebClient, atLeastOnce()).makeRequest(eq(processId), any(),any(), any());
        verifyNoMoreInteractions(discoverySyncWebClient);

        verify(dataNegotiationJob, atLeastOnce()).negotiateDataSyncWithMultipleIssuers(eq(processId), any(), any());
        verifyNoMoreInteractions(dataNegotiationJob);
    }

    @Test
    void itShouldMakeOneDiscoveryPerNodeAndNegotiateEachTypeWithItsOwnEntities() throws JSONException, NoSuchAlgorithmException, JsonProcessingException {
        String processId = "0";

        var brokerEntities = MVBrokerEntity4DataNegotiationMother.list3And4();
        var brokerCategories = MVBrokerEntity4DataNegotiationMother.listCategories();
        var brokerCatalogs = MVBrokerEntity4DataNegotiationMother.listCatalogs();

        when(brokerPublisherService.findAllIdTypeAndAttributesByType(
                eq(processId),
                anyString(),
                eq("lastUpdate"),
                eq("version"),
                eq("lifecycleStatus"),
                eq("validFor"),
                eq(BrokerEntityWithIdTypeLastUpdateAndVersion.class))
        ).thenAnswer(invocation -> {
            String entityType = invocation.getArgument(1);
            List<?> result = switch (entityType) {
                case MVEntity4DataNegotiationMother.PRODUCT_OFFERING_TYPE_NAME -> brokerEntities;
                case MVEntity4DataNegotiationMother.CATEGORY_TYPE_NAME -> brokerCategories;
                case MVEntity4DataNegotiationMother.CATALOG_TYPE_NAME -> brokerCatalogs;
                default -> Collections.emptyList();
            };
            return Flux.fromIterable(result);
        });

        when(auditRecordService.findCreateOrUpdateAuditRecordsByEntityIds(eq(processId), any(), any()))
                .thenAnswer(invocation -> {
                    String entityType = invocation.getArgument(1);
                    return switch (entityType) {
                        case MVEntity4DataNegotiationMother.PRODUCT_OFFERING_TYPE_NAME ->
                                Flux.fromIterable(MVAuditServiceEntity4DataNegotiationMother.sample3and4());
                        case MVEntity4DataNegotiationMother.CATEGORY_TYPE_NAME ->
                                Flux.fromIterable(MVAuditServiceEntity4DataNegotiationMother.listCategories());
                        case MVEntity4DataNegotiationMother.CATALOG_TYPE_NAME ->
                                Flux.fromIterable(MVAuditServiceEntity4DataNegotiationMother.listCatalogs());
                        default -> Flux.fromIterable(Collections.emptyList());
                    };
                });

        when(replicationPoliciesService.filterReplicableMvEntitiesList(eq(processId), any(Flux.class)))
                .thenAnswer(invocation -> {
                    Flux<MVEntityReplicationPoliciesInfo> fluxArg = invocation.getArgument(1);
                    return fluxArg.map(info -> new Id(info.id()));
                });

        when(externalAccessNodesConfig.getExternalAccessNodesUrls()).thenReturn(Mono.just(UrlMother.example1And2urlsList()));

        MVEntity4DataNegotiation externalProductOffering = MVEntity4DataNegotiationMother.sample3();
        MVEntity4DataNegotiation externalCategory = MVEntity4DataNegotiationMother.listCategories().blockFirst();
        List<Set<String>> sentTypesByRequest = new ArrayList<>();
        when(discoverySyncWebClient.makeRequest(eq(processId), any(), any(), any()))
                .thenAnswer(invocation -> {
                    Flux<MVEntity4DataNegotiation> sent = invocation.getArgument(3);
                    sentTypesByRequest.add(sent.map(MVEntity4DataNegotiation::type).collect(Collectors.toSet()).block());
                    return Flux.just(externalProductOffering, externalCategory);
                });

        List<Set<String>> externalTypesByNegotiation = new ArrayList<>();
        List<Set<String>> localTypesByNegotiation = new ArrayList<>();
        List<Integer> issuersByNegotiation = new ArrayList<>();
        when(dataNegotiationJob.negotiateDataSyncWithMultipleIssuers(eq(processId), any(), any()))
                .thenAnswer(invocation -> {
                    Mono<Map<Issuer, Flux<MVEntity4DataNegotiation>>> externalMono = invocation.getArgument(1);
                    Flux<MVEntity4DataNegotiation> localFlux = invocation.getArgument(2);
                    Map<Issuer, Flux<MVEntity4DataNegotiation>> externalByIssuer = externalMono.block();
                    issuersByNegotiation.add(externalByIssuer.size());
                    externalTypesByNegotiation.add(Flux.merge(externalByIssuer.values())
                            .map(MVEntity4DataNegotiation::type).collect(Collectors.toSet()).block());
                    localTypesByNegotiation.add(localFlux.map(MVEntity4DataNegotiation::type).collect(Collectors.toSet()).block());
                    return Mono.empty();
                });

        StepVerifier
                .create(p2PDataSyncJob.synchronizeData(processId))
                .verifyComplete();

        verify(discoverySyncWebClient, times(2)).makeRequest(eq(processId), any(), any(), any());
        assertThat(sentTypesByRequest).allSatisfy(sentTypes -> assertThat(sentTypes).containsExactlyInAnyOrder(
                MVEntity4DataNegotiationMother.PRODUCT_OFFERING_TYPE_NAME,
                MVEntity4DataNegotiationMother.CATEGORY_TYPE_NAME,
                MVEntity4DataNegotiationMother.CATALOG_TYPE_NAME));

        int typeCount = ApplicationConstants.ROOT_OBJECTS_LIST.size();
        verify(dataNegotiationJob, times(typeCount)).negotiateDataSyncWithMultipleIssuers(eq(processId), any(), any());
        assertThat(issuersByNegotiation).containsOnly(2);
        for (int i = 0; i < typeCount; i++) {
            String entityType = ApplicationConstants.ROOT_OBJECTS_LIST.get(i);
            assertThat(externalTypesByNegotiation.get(i)).isSubsetOf(entityType);
            assertThat(localTypesByNegotiation.get(i)).isSubsetOf(entityType);
        }
        int productOfferingIndex = ApplicationConstants.ROOT_OBJECTS_LIST.indexOf(MVEntity4DataNegotiationMother.PRODUCT_OFFERING_TYPE_NAME);
        assertThat(externalTypesByNegotiation.get(productOfferingIndex)).containsExactly(MVEntity4DataNegotiationMother.PRODUCT_OFFERING_TYPE_NAME);
        assertThat(localTypesByNegotiation.get(productOfferingIndex)).containsExactly(MVEntity4DataNegotiationMother.PRODUCT_OFFERING_TYPE_NAME);
    }

    @Test
    void itShouldNegotiateEachTypeWithTheLocalEntitiesStoredByThePreviousTypes() {
        String processId = "0";
        var brokerCategories = MVBrokerEntity4DataNegotiationMother.listCategories();
        AtomicInteger categoryReads = new AtomicInteger();
        // The first read (for the discovery) finds no categories; by the time categories are negotiated, an
        // earlier type has stored them as its subentities.
        when(brokerPublisherService.findAllIdTypeAndAttributesByType(
                eq(processId),
                anyString(),
                eq("lastUpdate"),
                eq("version"),
                eq("lifecycleStatus"),
                eq("validFor"),
                eq(BrokerEntityWithIdTypeLastUpdateAndVersion.class))
        ).thenAnswer(invocation -> {
            String entityType = invocation.getArgument(1);
            boolean categoriesStored = MVEntity4DataNegotiationMother.CATEGORY_TYPE_NAME.equals(entityType)
                    && categoryReads.incrementAndGet() > 1;
            return Flux.fromIterable(categoriesStored ? brokerCategories : Collections.emptyList());
        });
        when(auditRecordService.findCreateOrUpdateAuditRecordsByEntityIds(eq(processId), any(), any()))
                .thenAnswer(invocation -> MVEntity4DataNegotiationMother.CATEGORY_TYPE_NAME.equals(invocation.getArgument(1))
                        ? Flux.fromIterable(MVAuditServiceEntity4DataNegotiationMother.listCategories())
                        : Flux.empty());
        when(replicationPoliciesService.filterReplicableMvEntitiesList(eq(processId), any(Flux.class)))
                .thenAnswer(invocation -> {
                    Flux<MVEntityReplicationPoliciesInfo> fluxArg = invocation.getArgument(1);
                    return fluxArg.map(info -> new Id(info.id()));
                });
        when(externalAccessNodesConfig.getExternalAccessNodesUrls()).thenReturn(Mono.just(List.of("https://example1.org")));
        List<Integer> sentEntities = new ArrayList<>();
        when(discoverySyncWebClient.makeRequest(eq(processId), any(), any(), any()))
                .thenAnswer(invocation -> {
                    Flux<MVEntity4DataNegotiation> sent = invocation.getArgument(3);
                    sentEntities.add(sent.collectList().block().size());
                    return MVEntity4DataNegotiationMother.listCategories();
                });
        Map<String, Integer> localCountByType = new HashMap<>();
        when(dataNegotiationJob.negotiateDataSyncWithMultipleIssuers(eq(processId), any(), any()))
                .thenAnswer(invocation -> {
                    Flux<MVEntity4DataNegotiation> localFlux = invocation.getArgument(2);
                    localFlux.collectList().block().forEach(entity -> localCountByType.merge(entity.type(), 1, Integer::sum));
                    return Mono.empty();
                });

        StepVerifier
                .create(p2PDataSyncJob.synchronizeData(processId))
                .verifyComplete();

        assertThat(sentEntities).containsExactly(0);
        assertThat(localCountByType).containsEntry(MVEntity4DataNegotiationMother.CATEGORY_TYPE_NAME, brokerCategories.size());
    }

    @Test
    void itShouldIgnoreExternalEntitiesWithoutTypeAndKeepTheRestOfTheNode() throws JSONException, NoSuchAlgorithmException, JsonProcessingException {
        String processId = "0";
        MVEntity4DataNegotiation externalProductOffering = MVEntity4DataNegotiationMother.sample3();
        MVEntity4DataNegotiation withoutType = new MVEntity4DataNegotiation("urn:ngsi-ld:unknown:1", null, null,
                null, null, null, null, null, null);
        when(brokerPublisherService.findAllIdTypeAndAttributesByType(eq(processId), anyString(), eq("lastUpdate"),
                eq("version"), eq("lifecycleStatus"), eq("validFor"), eq(BrokerEntityWithIdTypeLastUpdateAndVersion.class)))
                .thenReturn(Flux.empty());
        when(auditRecordService.findCreateOrUpdateAuditRecordsByEntityIds(eq(processId), any(), any())).thenReturn(Flux.empty());
        when(replicationPoliciesService.filterReplicableMvEntitiesList(eq(processId), any(Flux.class))).thenReturn(Flux.empty());
        when(externalAccessNodesConfig.getExternalAccessNodesUrls()).thenReturn(Mono.just(List.of("https://example1.org")));
        when(discoverySyncWebClient.makeRequest(eq(processId), any(), any(), any()))
                .thenReturn(Flux.just(withoutType, externalProductOffering));
        List<String> externalTypes = new ArrayList<>();
        when(dataNegotiationJob.negotiateDataSyncWithMultipleIssuers(eq(processId), any(), any()))
                .thenAnswer(invocation -> {
                    Mono<Map<Issuer, Flux<MVEntity4DataNegotiation>>> externalMono = invocation.getArgument(1);
                    externalMono.block().values().forEach(flux ->
                            externalTypes.addAll(flux.map(MVEntity4DataNegotiation::type).collectList().block()));
                    return Mono.empty();
                });

        StepVerifier
                .create(p2PDataSyncJob.synchronizeData(processId))
                .verifyComplete();

        assertThat(externalTypes).containsExactly(MVEntity4DataNegotiationMother.PRODUCT_OFFERING_TYPE_NAME);
    }

    @Test
    void itShouldAnswerADiscoveryThatContainsEntitiesWithoutType() throws JSONException, NoSuchAlgorithmException, JsonProcessingException {
        String processId = "0";
        MVEntity4DataNegotiation withoutType = new MVEntity4DataNegotiation("urn:ngsi-ld:unknown:1", null, null,
                null, null, null, null, null, null);
        when(brokerPublisherService.findAllIdTypeAndAttributesByType(eq(processId), anyString(), eq("lastUpdate"),
                eq("version"), eq("lifecycleStatus"), eq("validFor"), eq(BrokerEntityWithIdTypeLastUpdateAndVersion.class)))
                .thenReturn(Flux.empty());
        when(auditRecordService.findCreateOrUpdateAuditRecordsByEntityIds(eq(processId), any(), any())).thenReturn(Flux.empty());
        when(replicationPoliciesService.filterReplicableMvEntitiesList(eq(processId), any(Flux.class))).thenReturn(Flux.empty());

        StepVerifier
                .create(p2PDataSyncJob.dataDiscovery(processId, Mono.just("https://example.org"),
                        Flux.just(withoutType, MVEntity4DataNegotiationMother.sample3())))
                .verifyComplete();
    }

    @Test
    void itShouldReturnInternalEntitiesWhenDiscovery() throws JSONException, NoSuchAlgorithmException, JsonProcessingException {
        Flux<MVEntity4DataNegotiation> internalEntities = Flux.concat(
                MVEntity4DataNegotiationMother.list3And4(),
                MVEntity4DataNegotiationMother.listCategories(),
                MVEntity4DataNegotiationMother.listCatalogs()
            );

        String processId = "0";

        var brokerEntities = MVBrokerEntity4DataNegotiationMother.list3And4();
        var brokerCategories = MVBrokerEntity4DataNegotiationMother.listCategories();
        var brokerCatalogs = MVBrokerEntity4DataNegotiationMother.listCatalogs();

        when(brokerPublisherService.findAllIdTypeAndAttributesByType(
                eq(processId),
                anyString(),
                eq("lastUpdate"),
                eq("version"),
                eq("lifecycleStatus"),
                eq("validFor"),
                eq(BrokerEntityWithIdTypeLastUpdateAndVersion.class))
        ).thenAnswer(invocation -> {
            String entityType = invocation.getArgument(1);
            List<?> result = switch (entityType) {
                case MVEntity4DataNegotiationMother.PRODUCT_OFFERING_TYPE_NAME -> brokerEntities;
                case MVEntity4DataNegotiationMother.CATEGORY_TYPE_NAME -> brokerCategories;
                case MVEntity4DataNegotiationMother.CATALOG_TYPE_NAME -> brokerCatalogs;
                default -> Collections.emptyList();
            };
            return Flux.fromIterable(result);
        });

        when(auditRecordService.findCreateOrUpdateAuditRecordsByEntityIds(eq(processId), any(), any()))
                .thenAnswer(invocation -> {
                    String entityType = invocation.getArgument(1);
                    return switch (entityType) {
                        case MVEntity4DataNegotiationMother.PRODUCT_OFFERING_TYPE_NAME ->
                                Flux.fromIterable(MVAuditServiceEntity4DataNegotiationMother.sample3and4());
                        case MVEntity4DataNegotiationMother.CATEGORY_TYPE_NAME ->
                                Flux.fromIterable(MVAuditServiceEntity4DataNegotiationMother.listCategories());
                        case MVEntity4DataNegotiationMother.CATALOG_TYPE_NAME ->
                                Flux.fromIterable(MVAuditServiceEntity4DataNegotiationMother.listCatalogs());
                        default -> Flux.fromIterable(Collections.emptyList());
                    };
                });

        when(replicationPoliciesService.filterReplicableMvEntitiesList(eq(processId), any(Flux.class)))
                .thenAnswer(invocation -> {
                    Flux<MVEntityReplicationPoliciesInfo> fluxArg = invocation.getArgument(1);

                    Set<String> productOfferingIds = MVBrokerEntity4DataNegotiationMother.list3And4().stream()
                            .map(BrokerEntityWithIdTypeLastUpdateAndVersion::getId)
                            .collect(Collectors.toSet());

                    Set<String> categoryIds = MVBrokerEntity4DataNegotiationMother.listCategories().stream()
                            .map(BrokerEntityWithIdTypeLastUpdateAndVersion::getId)
                            .collect(Collectors.toSet());

                    Set<String> catalogIds = MVBrokerEntity4DataNegotiationMother.listCatalogs().stream()
                            .map(BrokerEntityWithIdTypeLastUpdateAndVersion::getId)
                            .collect(Collectors.toSet());

                    return fluxArg
                            .filter(info -> {
                                String id = info.id();
                                return productOfferingIds.contains(id) || categoryIds.contains(id) || catalogIds.contains(id);
                            })
                            .map(info -> new Id(info.id()));
                });

        Flux<MVEntity4DataNegotiation> resultFlux = p2PDataSyncJob.dataDiscovery(
                processId,
                Mono.just("https://example.org"),
                MVEntity4DataNegotiationMother.list1And2());

        List<MVEntity4DataNegotiation> expected = internalEntities.collectList().block();

        StepVerifier.create(resultFlux.collectList())
                .assertNext(actual -> {
                    assertThat(actual).containsExactlyInAnyOrderElementsOf(expected);
                })
                .verifyComplete();

        verify(brokerPublisherService, times(1)).findAllIdTypeAndAttributesByType(processId, MVEntity4DataNegotiationMother.PRODUCT_OFFERING_TYPE_NAME, "lastUpdate", "version", "lifecycleStatus", "validFor", BrokerEntityWithIdTypeLastUpdateAndVersion.class);
        verifyNoMoreInteractions(brokerPublisherService);

        //Should be number of times as BROKER_ENTITY_TYPES lenght is
        verify(auditRecordService, times(ApplicationConstants.ROOT_OBJECTS_LIST.size())).findCreateOrUpdateAuditRecordsByEntityIds(eq(processId), any(), any());
        verifyNoMoreInteractions(auditRecordService);
    }

    @Test
    void itShouldReturnLocalEntitiesWhenPassingId() throws JSONException {
        Mono<List<Id>> idsMono = Mono.just(Arrays.stream(IdMother.entitiesRequest).toList());

        String entityRequestBrokerJson = BrokerDataMother.GET_ENTITY_REQUEST_BROKER_JSON;
        JSONArray expectedResponseJsonArray = new JSONArray(entityRequestBrokerJson);
        List<Entity> expectedLocalEntities = new ArrayList<>();
        for (int i = 0; i < expectedResponseJsonArray.length(); i++) {
            String entity = expectedResponseJsonArray.getString(i);

            expectedLocalEntities.add(new Entity(entity));
        }
        String processId = "0";
        when(brokerPublisherService.findEntitiesAndItsSubentitiesByIdInBase64(eq(processId), any(), any())).thenReturn(Flux.fromIterable(expectedLocalEntities));

        Flux<Entity> result = p2PDataSyncJob.getLocalEntitiesByIdInBase64(processId, idsMono);

        System.out.println(expectedLocalEntities);

        StepVerifier
                .create(result.collectList())
                .assertNext(x -> assertThat(x).isEqualTo(expectedLocalEntities))
                .verifyComplete();

        verify(brokerPublisherService, times(1)).findEntitiesAndItsSubentitiesByIdInBase64(eq(processId), any(), any());
        verifyNoMoreInteractions(brokerPublisherService);
    }
}
