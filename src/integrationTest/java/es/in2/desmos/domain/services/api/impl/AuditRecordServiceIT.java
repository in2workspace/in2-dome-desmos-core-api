package es.in2.desmos.domain.services.api.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import es.in2.desmos.domain.models.AuditRecord;
import es.in2.desmos.domain.models.AuditRecordStatus;
import es.in2.desmos.domain.models.MVAuditServiceEntity4DataNegotiation;
import es.in2.desmos.domain.models.MVEntity4DataNegotiation;
import es.in2.desmos.domain.services.api.AuditRecordService;
import es.in2.desmos.support.ContainerManager;
import es.in2.desmos.objectmothers.AuditRecordMother;
import es.in2.desmos.objectmothers.MVAuditServiceEntity4DataNegotiationMother;
import es.in2.desmos.objectmothers.MVEntity4DataNegotiationMother;
import org.json.JSONException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.security.NoSuchAlgorithmException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AuditRecordServiceIT {
    @Autowired
    AuditRecordService auditRecordService;

    @DynamicPropertySource
    private static void setDynamicProperties(DynamicPropertyRegistry registry) {
        ContainerManager.postgresqlProperties(registry);
    }

    @BeforeEach
    void setUp() {
        InitializeAuditRecord(auditRecordService);
    }

    @Test
    void itShouldBuildAndSaveAuditRecordFromDataSync() throws JSONException, NoSuchAlgorithmException, JsonProcessingException {
        String processId = "0";
        String issuer = "http://example.org";
        MVAuditServiceEntity4DataNegotiation mvAuditServiceEntity4DataNegotiationMother = MVAuditServiceEntity4DataNegotiationMother.sample1();
        AuditRecordStatus status = AuditRecordStatus.RETRIEVED;

        Mono<Void> result = auditRecordService.buildAndSaveAuditRecordFromDataSync(processId, issuer, mvAuditServiceEntity4DataNegotiationMother, status);

        StepVerifier
                .create(result)
                .verifyComplete();

        assertAuditRecordEntityIsExpected(mvAuditServiceEntity4DataNegotiationMother.id(), MVEntity4DataNegotiationMother.sample1());
    }

    private void assertAuditRecordEntityIsExpected(String entityId, MVEntity4DataNegotiation expectedMVEntity4DataNegotiation) {
        await().atMost(10, TimeUnit.SECONDS).ignoreExceptions().until(() -> {
            String processId = "0";
            Mono<AuditRecord> auditRecordMono = auditRecordService.findMostRecentRetrievedOrDeletedByEntityId(processId, entityId);

            StepVerifier
                    .create(auditRecordMono)
                    .consumeNextWith(auditRecord -> {
                        System.out.println("Entity audit record AuditRecord entity check: " + auditRecord);

                        AuditRecord expectedAuditRecord = AuditRecordMother.createAuditRecordFromMVEntity4DataNegotiation(expectedMVEntity4DataNegotiation, AuditRecordStatus.RETRIEVED);

                        assertThat(auditRecord)
                                .usingRecursiveComparison()
                                .ignoringFields("processId", "id", "createdAt", "hash", "hashLink", "newTransaction")
                                .isEqualTo(expectedAuditRecord);
                    })
                    .expectComplete()
                    .verify();

            return true;
        });
    }

    private void InitializeAuditRecord(AuditRecordService auditRecordService) {
        auditRecordService.fetchMostRecentAuditRecord().block();
    }
}
