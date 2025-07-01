package es.in2.desmos.application.workflows.jobs;

import com.fasterxml.jackson.core.JsonProcessingException;
import es.in2.desmos.application.workflows.jobs.impl.P2PDataSyncJobImpl;
import es.in2.desmos.domain.events.DataNegotiationEventPublisher;
import es.in2.desmos.domain.models.*;
import es.in2.desmos.domain.services.api.impl.AuditRecordServiceImpl;
import es.in2.desmos.domain.services.broker.impl.BrokerPublisherServiceImpl;
import es.in2.desmos.domain.services.policies.ReplicationPoliciesService;
import es.in2.desmos.domain.services.sync.DiscoverySyncWebClient;
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


        String externalDomain = "http://external-domain.org";
        DiscoverySyncResponse discoverySyncResponse3 = new DiscoverySyncResponse(Mono.just(externalDomain),
                Flux.just(MVEntity4DataNegotiationMother.sample3()));

        DiscoverySyncResponse discoverySyncResponse4 = new DiscoverySyncResponse(Mono.just(externalDomain),
                Flux.just(MVEntity4DataNegotiationMother.sample4()));

        when(discoverySyncWebClient.makeRequest(eq(processId), any(), any()))
                .thenReturn(Mono.just(discoverySyncResponse3))
                .thenReturn(Mono.just(discoverySyncResponse4));

        List<String> urlExternalAccessNodesList = UrlMother.example1And2urlsList();
        when(externalAccessNodesConfig.getExternalAccessNodesUrls()).thenReturn(Mono.just(urlExternalAccessNodesList));

        when(dataNegotiationJob.negotiateDataSyncWithMultipleIssuers(eq(processId), any(), any())).thenReturn(Mono.empty());

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

        var result = p2PDataSyncJob.synchronizeData(processId);

        StepVerifier
                .create(result)
                .verifyComplete();

        verify(brokerPublisherService, times(1)).findAllIdTypeAndAttributesByType(processId, MVEntity4DataNegotiationMother.PRODUCT_OFFERING_TYPE_NAME, "lastUpdate", "version", "lifecycleStatus", "validFor", BrokerEntityWithIdTypeLastUpdateAndVersion.class);
        verifyNoMoreInteractions(brokerPublisherService);

        //Should be number of times as BROKER_ENTITY_TYPES lenght is
        verify(auditRecordService, atLeastOnce()).findCreateOrUpdateAuditRecordsByEntityIds(eq(processId), any(), any());
        verifyNoMoreInteractions(auditRecordService);
        verify(externalAccessNodesConfig, atLeastOnce()).getExternalAccessNodesUrls();
        verifyNoMoreInteractions(externalAccessNodesConfig);

        verify(discoverySyncWebClient, atLeastOnce()).makeRequest(eq(processId), any(), any());
        verifyNoMoreInteractions(discoverySyncWebClient);

        verify(dataNegotiationJob, atLeastOnce()).negotiateDataSyncWithMultipleIssuers(eq(processId), any(), any());
        verifyNoMoreInteractions(dataNegotiationJob);
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
        verify(auditRecordService, times(15)).findCreateOrUpdateAuditRecordsByEntityIds(eq(processId), any(), any());
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
        Mono<List<Entity>> localEntitiesMono = Mono.just(expectedLocalEntities);

        String processId = "0";
        when(brokerPublisherService.findEntitiesAndItsSubentitiesByIdInBase64(eq(processId), any(), any())).thenReturn(localEntitiesMono);

        Mono<List<Entity>> result = p2PDataSyncJob.getLocalEntitiesByIdInBase64(processId, idsMono);

        System.out.println(expectedLocalEntities);

        StepVerifier
                .create(result)
                .assertNext(x -> assertThat(x).isEqualTo(expectedLocalEntities))
                .verifyComplete();

        verify(brokerPublisherService, times(1)).findEntitiesAndItsSubentitiesByIdInBase64(eq(processId), any(), any());
        verifyNoMoreInteractions(brokerPublisherService);
    }
}
