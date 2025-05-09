package es.in2.desmos.infrastructure.controllers;

import es.in2.desmos.domain.models.BlockchainNotification;
import es.in2.desmos.domain.models.BrokerNotification;
import es.in2.desmos.domain.services.blockchain.BlockchainListenerService;
import es.in2.desmos.domain.services.broker.BrokerListenerService;
import es.in2.desmos.domain.utils.EndpointsConstants;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationControllerTests {

    @Mock
    private BrokerListenerService brokerListenerService;

    @Mock
    private BlockchainListenerService blockchainListenerService;

    @InjectMocks
    private NotificationController notificationController;

    @Test
    void testPostBrokerNotification() {
        // Arrange
        BrokerNotification brokerNotification = BrokerNotification.builder()
                .id("id")
                .type("type")
                .data(List.of(Map.of("id", "value")))
                .subscriptionId("subscriptionId")
                .notifiedAt("notifiedAt")
                .build();
        when(brokerListenerService.processBrokerNotification(anyString(), any(BrokerNotification.class)))
                .thenReturn(Mono.empty());
        // Act
        WebTestClient.bindToController(notificationController)
                .build()
                .post()
                .uri(EndpointsConstants.CONTEXT_BROKER_NOTIFICATION)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(brokerNotification)
                .exchange()
                .expectStatus().isAccepted();
    }

    @Test
    void testPostDltNotification() {
        // Arrange
        BlockchainNotification blockchainNotification = BlockchainNotification.builder()
                .id(5478474)
                .publisherAddress("http://blockchain-testnode.infra.svc.cluster.local:8545/")
                .eventType("ProductOffering")
                .timestamp(684485648)
                .dataLocation("http://localhost:8080/ngsi-ld/v1/entities/" +
                        "urn:ngsi-ld:ProductOffering:38088145-aef3-440e-ab93-a33bc9bfce69" +
                        "?hl=03ac674216f3e15c761ee1a5e255f067953623c8b388b4459e13f978d7c846f4")
                .relevantMetadata(List.of("0xdd98910dbc7831753bab3da302ce5bf9d73ac13961913c2e774de8e737867f0d",
                        "0x947cccb1a978e374a4b36550389768d405bf5b81817175ab9023b5e3d96ab966"))
                .entityId("0x6f6468ded8276d009ab1b6c578c2b922053acd6b5a507f36d408d3f7c9ae91d0")
                .previousEntityHashLink("0x98d9658d98764dbe135b316f52a98116b4b02f9d7e57212aa86335c42a58539a")
                .ethereumAddress("0x1234567890abcdef1234567890abcdef12345678")
                .build();
        when(blockchainListenerService.processBlockchainNotification(anyString(), any(BlockchainNotification.class)))
                .thenReturn(Mono.empty());
        // Act
        WebTestClient.bindToController(notificationController)
                .build()
                .post()
                .uri(EndpointsConstants.DLT_ADAPTER_NOTIFICATION)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(blockchainNotification)
                .exchange()
                .expectStatus().isAccepted();
    }

    @Test
    void whenBrokerNotificationIsInvalid_thenBadRequest() {
        // Arrange an invalid BrokerNotification object (e.g., missing required fields according to validation annotations)
        BrokerNotification invalidNotification = BrokerNotification.builder().build();
        // Act and Assert
        WebTestClient.bindToController(notificationController)
                .build()
                .post()
                .uri(EndpointsConstants.CONTEXT_BROKER_NOTIFICATION)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(invalidNotification)
                .exchange()
                .expectStatus().isBadRequest(); // Expect a 400 Bad Request due to validation failure
    }

    @Test
    void whenBlockchainNotificationIsInvalid_thenBadRequest() {
        // Arrange an invalid BlockchainNotification object
        BlockchainNotification invalidNotification = BlockchainNotification.builder().build();
        // Act and Assert
        WebTestClient.bindToController(notificationController)
                .build()
                .post()
                .uri(EndpointsConstants.DLT_ADAPTER_NOTIFICATION)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(invalidNotification)
                .exchange()
                .expectStatus().isBadRequest(); // Expect a 400 Bad Request due to validation failure
    }

}
