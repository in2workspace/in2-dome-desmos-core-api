package es.in2.desmos.infrastructure.configs.properties;

import es.in2.desmos.support.ContainerManager;
import es.in2.desmos.objectmothers.UrlMother;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Testcontainers
@SpringBootTest
@TestPropertySource(properties = {"external-access-nodes.urls=https://example1.org, https://example2.org"})
class ExternalAccessNodesPropertiesTests {
    @Autowired
    private ExternalAccessNodesProperties externalAccessNodesProperties;

    @DynamicPropertySource
    static void setDynamicProperties(DynamicPropertyRegistry registry) {
        ContainerManager.postgresqlProperties(registry);
    }

    @Test
    void itShouldReturnExternalAccessNodesUrls() {
        String expected = UrlMother.commaSeparatedExample1And2Urls();

        String result = externalAccessNodesProperties.urls();

        System.out.println("Result: " + result);

        assertEquals(expected, result);
    }
}