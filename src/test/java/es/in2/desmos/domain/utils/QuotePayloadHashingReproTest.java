package es.in2.desmos.domain.utils;

import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import es.in2.desmos.domain.models.BlockchainTxPayload;
import es.in2.desmos.infrastructure.configs.ApiConfig;
import es.in2.desmos.infrastructure.configs.EndpointsConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.Map;
import java.util.UUID;

import static org.mockito.Mockito.lenient;

/*
 * Repro test for the "Quote replication" issue reported by BAE/Luca, WITHOUT Testcontainers/Docker.
 *
 * Unlike QuotePublishWorkflowReproTest (which needs a full 2-node Docker environment),
 * this test isolates just the JSON hashing/serialization step (BlockchainTxPayloadFactory)
 * using the REAL ObjectMapper configured in DesmosApiApplication (JsonMapper with
 * SORT_PROPERTIES_ALPHABETICALLY), fed with the exact Quote payload shared by the client -
 * including the nested QuoteItem, attachment, and a note with id/text set to null.
 *
 * If this fails, we've found a structural bug in how Quote entities get hashed/serialized
 * before being notarized on the blockchain, with no need for Docker at all.
 */
@ExtendWith(MockitoExtension.class)
class QuotePayloadHashingReproTest {

    private static final String QUOTE_JSON = """
            {
                "id": "urn:ngsi-ld:quote:9aa2f09d-c035-42c4-a4b5-52a1caf25d83",
                "type": "quote",
                "href": "urn:ngsi-ld:quote:9aa2f09d-c035-42c4-a4b5-52a1caf25d83",
                "category": "tailored",
                "description": "Test 26/08",
                "externalId": "0000",
                "quoteDate": "2026-08-26T06:56:24.231150881Z",
                "requestedQuoteCompletionDate": "2026-08-28T23:59:59Z",
                "quoteItem": [
                    {
                        "action": "add",
                        "quantity": 1,
                        "state": "pending",
                        "note": [
                            {
                                "id": null,
                                "text": null
                            }
                        ],
                        "productOffering": {
                            "id": "urn:ngsi-ld:product-offering:984e6f2c-15fd-4e45-ab27-c9f7b6190b98",
                            "@type": "ProductOfferingRef"
                        },
                        "@type": "QuoteItem"
                    }
                ],
                "relatedParty": [
                    {
                        "id": "urn:ngsi-ld:organization:eb6647da-84f2-4645-8d9f-c2905775b561",
                        "href": "urn:ngsi-ld:organization:eb6647da-84f2-4645-8d9f-c2905775b561",
                        "name": "urn:ngsi-ld:organization:eb6647da-84f2-4645-8d9f-c2905775b561",
                        "role": "Seller",
                        "@referredType": "organization"
                    },
                    {
                        "id": "urn:ngsi-ld:organization:eb6647da-84f2-4645-8d9f-c2905775b561",
                        "href": "urn:ngsi-ld:organization:eb6647da-84f2-4645-8d9f-c2905775b561",
                        "name": "did:elsi:VATIT-12622480155",
                        "role": "SellerOperator",
                        "@referredType": "organization"
                    },
                    {
                        "id": "urn:ngsi-ld:organization:95fdc12e-6889-4f08-8ff8-296b10e8e781",
                        "href": "urn:ngsi-ld:organization:95fdc12e-6889-4f08-8ff8-296b10e8e781",
                        "name": "VATIT-05724831002",
                        "role": "Buyer",
                        "@type": "RelatedParty",
                        "@referredType": "organization"
                    },
                    {
                        "id": "urn:ngsi-ld:organization:df924e5d-e8c8-4ea4-aca8-edaf5acdc109",
                        "href": "urn:ngsi-ld:organization:df924e5d-e8c8-4ea4-aca8-edaf5acdc109",
                        "name": "did:elsi:VATSB-12345678J",
                        "role": "BuyerOperator",
                        "@referredType": "organization"
                    }
                ],
                "state": "inProgress"
            }
            """;

    // The real ObjectMapper bean, exactly as configured in DesmosApiApplication - NOT a mock.
    private final ObjectMapper realObjectMapper = JsonMapper.builder()
            .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
            .build();

    private final String processId = UUID.randomUUID().toString();

    @Mock
    private ApiConfig apiConfig;

    @Mock
    private EndpointsConfig endpointsConfig;

    @Test
    void realQuotePayload_doesNotBreakHashingOrSerialization() throws Exception {
        // Swap in the real ObjectMapper (InjectMocks would otherwise inject a Mockito mock for it)
        var factory = new BlockchainTxPayloadFactory(realObjectMapper, apiConfig, endpointsConfig);

        lenient().when(apiConfig.getExternalDomain()).thenReturn("https://dome-sbx.cloudeng.it");
        lenient().when(apiConfig.organizationIdHash()).thenReturn("381d18e478b9ae6e67b1bf48c9f3bcaf246d53c4311bfe81f46e63aa18167c89");
        lenient().when(apiConfig.getCurrentEnvironment()).thenReturn("test");
        lenient().when(endpointsConfig.getEntitiesEndpoint()).thenReturn("/api/v2" + EndpointsConstants.GET_ENTITY);

        @SuppressWarnings("unchecked")
        Map<String, Object> quoteDataMap = realObjectMapper.readValue(QUOTE_JSON, Map.class);

        String previousHash = "5077272d496c8afd1af9d3740f9e5f11837089b5952d577eff4c20509e6e199e";

        Mono<BlockchainTxPayload> resultMono = factory.buildBlockchainTxPayload(processId, quoteDataMap, previousHash);

        StepVerifier.create(resultMono)
                .assertNext(payload -> {
                    org.junit.jupiter.api.Assertions.assertNotNull(payload.entityId());
                    org.junit.jupiter.api.Assertions.assertEquals("quote", payload.eventType());
                })
                .verifyComplete();
    }

}
