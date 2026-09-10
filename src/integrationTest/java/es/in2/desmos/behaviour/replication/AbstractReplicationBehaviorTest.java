package es.in2.desmos.behaviour.replication;

import es.in2.desmos.domain.repositories.AuditRecordRepository;
import es.in2.desmos.infrastructure.controllers.NotificationController;
import es.in2.desmos.support.ContainerManager;
import es.in2.desmos.support.HostRewritingWebClientTestConfig;
import es.in2.desmos.support.QueueReadiness;
import es.in2.desmos.support.ReplicationAssertions;
import es.in2.desmos.support.ReplicationFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.awaitility.Awaitility.await;

/**
 * Shared base for every two-node replication behaviour test. Declaring
 * {@code @DynamicPropertySource} here, exactly once, is not just tidiness: Spring's test
 * context cache keys off the exact set of {@code Method} objects backing each
 * {@code @DynamicPropertySource} customizer, so two subclasses that each declared their own
 * (however textually identical) would get two separate Spring contexts -- both trying to bind
 * Netty to the same fixed {@link ContainerManager#getNodeAPort()}, and the second one failing
 * with "Port already in use". Sharing this base is what makes them reuse one cached context.
 * <p>
 * Every subclass gets: node A's Postgres/Scorpio/DLT-adapter wiring, a fixed, host-exposed
 * port so node B (a container) can call node A back, node A pointed at node B as an external
 * access node for P2P sync, and the DNS-rewriting WebClient so node A can reach the shared
 * verifier stub under the same URL string node B uses.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@Testcontainers
@Import(HostRewritingWebClientTestConfig.class)
abstract class AbstractReplicationBehaviorTest {

    @DynamicPropertySource
    static void setDynamicProperties(DynamicPropertyRegistry registry) {
        ContainerManager.postgresqlProperties(registry);
        ContainerManager.nodeAReplicationProperties(registry);
        ContainerManager.externalAccessNodesProperties(registry);
    }

    // Spring auto-subscribes ApplicationRunner.onApplicationReady()'s returned Mono *without*
    // blocking application startup on it -- so node A's initial P2P sync against node B (part of
    // that same startup chain) can still be running in the background when the very first @Test
    // method of whichever class happens to boot this shared context starts executing. That sync
    // pushes A's local entities of every type to B's P2P discovery endpoint, so a test seeding an
    // entity into A's broker while this window is open can see it land on B via P2P discovery --
    // independent of whatever DLT delivery that test is actually exercising (confirmed by
    // reproducing it: a negative DLT scenario saw its entity appear on B anyway, and node B's
    // logs showed a concurrent DataSyncController P2P discovery call from node A, not the DLT
    // path, responsible). Warm up once, process-wide, before any test body runs: node A's publish
    // queue is only authorized in the doOnComplete/doOnTerminate of that exact same startup
    // chain, so successfully publishing a disposable entity here proves the initial P2P sync has
    // already run to completion.
    private static final AtomicBoolean NODE_A_STARTUP_SETTLED = new AtomicBoolean(false);

    @Autowired
    private NotificationController warmupNotificationController;

    @Autowired
    private AuditRecordRepository warmupAuditRecordRepository;

    @BeforeEach
    void awaitNodeAStartupSettling() {
        if (!NODE_A_STARTUP_SETTLED.compareAndSet(false, true)) {
            return;
        }
        String entityId = "urn:ngsi-ld:category:" + UUID.randomUUID();
        await().atMost(Duration.ofSeconds(90))
                .pollInterval(Duration.ofSeconds(3))
                .until(() -> {
                    warmupNotificationController.postBrokerNotification(ReplicationFixtures.brokerNotificationForCategory(entityId))
                            .block(Duration.ofSeconds(10));
                    return QueueReadiness.pollWithPartialFallback(
                            Duration.ofSeconds(8), Duration.ofMillis(500),
                            () -> QueueReadiness.trailFor(warmupAuditRecordRepository, entityId),
                            ReplicationAssertions::hasReachedPublished);
                }, ReplicationAssertions::hasReachedPublished);
        warmupAuditRecordRepository.deleteAll().block();
    }
}
