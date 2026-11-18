package es.in2.desmos.domain.services.broker.adapter;

import es.in2.desmos.domain.models.BrokerEntityWithIdTypeLastUpdateAndVersion;
import es.in2.desmos.domain.services.broker.adapter.impl.OrionLdAdapter;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

class OrionLdAdapterTests {
    private final OrionLdAdapter orionLdAdapter = new OrionLdAdapter();

    @Test
    void itShouldFailWhenGetMvEntities4DataNegotiation() {
        String processId = "0";
        var result = orionLdAdapter.findAllIdTypeAndAttributesByType(processId, "ProductOffering", "lastUpdate", "version", "lifecycleStatus", "validFor", BrokerEntityWithIdTypeLastUpdateAndVersion.class);

        StepVerifier.create(result)
                .expectError(UnsupportedOperationException.class)
                .verify();
    }

    @Test
    void itShouldFailWhenGetEntityById() {
        StepVerifier.create(orionLdAdapter.getEntityById("0", "urn:entity:1"))
                .expectError(UnsupportedOperationException.class)
                .verify();
    }
}
