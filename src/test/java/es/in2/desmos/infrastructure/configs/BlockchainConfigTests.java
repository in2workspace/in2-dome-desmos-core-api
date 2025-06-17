package es.in2.desmos.infrastructure.configs;

import es.in2.desmos.domain.utils.EndpointsConstants;
import es.in2.desmos.infrastructure.configs.properties.TxSubscriptionProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BlockchainConfigTests {

    @Mock
    private TxSubscriptionProperties txSubscriptionProperties;

    @InjectMocks
    private BlockchainConfig blockchainConfig;

    private final List<String> ENTITIES_LIST = List.of("individual",
            "organization",
            "catalog",
            "product-offering",
            "product-offering-price",
            "product-specification",
            "service-specification",
            "resource-specification",
            "category",
            "product-order",
            "product",
            "usage",
            "usage-specification",
            "applied-customer-bill-rate",
            "customer-bill");

    @Test
    void getNotificationEndpointReturnsCorrectEndpoint() {
        // Arrange
        String expectedEndpoint = "https://example.com" + EndpointsConstants.DLT_ADAPTER_NOTIFICATION;
        when(txSubscriptionProperties.notificationEndpoint()).thenReturn(expectedEndpoint);
        // Act
        String actualEndpoint = blockchainConfig.getNotificationEndpoint();
        // Assert
        assertEquals(expectedEndpoint, actualEndpoint, "The notification endpoint should match the mock value");
    }

    @Test
    void getEntityTypesReturnsCorrectEntities() {
        // Act
        List<String> actualEntityTypes = blockchainConfig.getEntityTypes();
        // Assert
        assertEquals(ENTITIES_LIST, actualEntityTypes, "The entity types should match the expected values");
    }

}
