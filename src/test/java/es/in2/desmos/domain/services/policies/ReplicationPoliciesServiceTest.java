package es.in2.desmos.domain.services.policies;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import es.in2.desmos.domain.models.Id;
import es.in2.desmos.domain.models.MVEntityReplicationPoliciesInfo;
import es.in2.desmos.domain.models.ReplicationDecision;
import es.in2.desmos.domain.services.policies.impl.ReplicationPoliciesServiceImpl;
import es.in2.desmos.objectmothers.MVEntityReplicationPoliciesInfoMother;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import org.slf4j.LoggerFactory;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class ReplicationPoliciesServiceTest {

    @InjectMocks
    private ReplicationPoliciesServiceImpl replicationPoliciesService;

    @Test
    void itShouldBeReplicableWhenAllFieldsAreValid() {
        String processId = "process-123";
        MVEntityReplicationPoliciesInfo mvEntity = MVEntityReplicationPoliciesInfoMother.replicationValidFields();

        Mono<Boolean> result = replicationPoliciesService.isMVEntityReplicable(processId, mvEntity);

        StepVerifier.create(result)
                .assertNext(isReplicable -> assertThat(isReplicable).isTrue())
                .verifyComplete();
    }

    @Test
    void itShouldBeReplicableWhenStartDateTimeIsNullAndOtherFieldsAreValid() {
        String processId = "process-123";
        MVEntityReplicationPoliciesInfo mvEntity = MVEntityReplicationPoliciesInfoMother.replicationValidFieldsAndNullStartDateTime();

        Mono<Boolean> result = replicationPoliciesService.isMVEntityReplicable(processId, mvEntity);

        StepVerifier.create(result)
                .assertNext(isReplicable -> assertThat(isReplicable).isTrue())
                .verifyComplete();
    }

    @Test
    void itShouldBeReplicableWhenEndDateTimeIsNullAndOtherFieldsAreValid() {
        String processId = "process-123";
        MVEntityReplicationPoliciesInfo mvEntity = MVEntityReplicationPoliciesInfoMother.replicationValidFieldsAndNullEndDateTime();

        Mono<Boolean> result = replicationPoliciesService.isMVEntityReplicable(processId, mvEntity);

        StepVerifier.create(result)
                .assertNext(isReplicable -> assertThat(isReplicable).isTrue())
                .verifyComplete();
    }

    @Test
    void itShouldBeNotReplicableWhenLifecycleStatusIsInvalid() {
        String processId = "process-123";
        MVEntityReplicationPoliciesInfo mvEntity = MVEntityReplicationPoliciesInfoMother.replicationInvalidLifecycleStatus();

        Mono<Boolean> result = replicationPoliciesService.isMVEntityReplicable(processId, mvEntity);

        StepVerifier.create(result)
                .assertNext(isReplicable -> assertThat(isReplicable).isFalse())
                .verifyComplete();
    }

    @Test
    void itShouldBeNotReplicableWhenLifecycleStatusIsNull() {
        String processId = "process-123";
        MVEntityReplicationPoliciesInfo mvEntity = MVEntityReplicationPoliciesInfoMother
                .replicationNullLifecycleStatus();

        Mono<Boolean> result = replicationPoliciesService.isMVEntityReplicable(processId, mvEntity);

        StepVerifier.create(result)
                .assertNext(isReplicable -> assertThat(isReplicable).isFalse())
                .verifyComplete();
    }

    @Test
    void itShouldBeReplicableWhenTypeIsUsageSpecificationEvenWithNullLifecycleStatus() {
        String processId = "process-123";
        MVEntityReplicationPoliciesInfo mvEntity = MVEntityReplicationPoliciesInfoMother
                .replicationUsageSpecificationWithNullLifecycleStatus();

        Mono<Boolean> result = replicationPoliciesService.isMVEntityReplicable(processId, mvEntity);

        StepVerifier.create(result)
                .assertNext(isReplicable -> assertThat(isReplicable).isTrue())
                .verifyComplete();
    }

    @Test
    void itShouldBeNotReplicableWhenStartDateTimeIsInTheFuture() {
        String processId = "process-123";
        MVEntityReplicationPoliciesInfo mvEntity = MVEntityReplicationPoliciesInfoMother.replicationInvalidFutureStartDateTime();


        Mono<Boolean> result = replicationPoliciesService.isMVEntityReplicable(processId, mvEntity);

        StepVerifier.create(result)
                .assertNext(isReplicable -> assertThat(isReplicable).isFalse())
                .verifyComplete();
    }

    @Test
    void itShouldBeNotReplicableWhenEndDateTimeIsInThePast() {
        String processId = "process-123";
        MVEntityReplicationPoliciesInfo mvEntity = MVEntityReplicationPoliciesInfoMother.replicationInvalidPastEndDateTime();

        Mono<Boolean> result = replicationPoliciesService.isMVEntityReplicable(processId, mvEntity);

        StepVerifier.create(result)
                .assertNext(isReplicable -> assertThat(isReplicable).isFalse())
                .verifyComplete();
    }

    @Test
    void itShouldReturnReplicableIds() {
        String processId = "process-123";
        var mvReplicableList = MVEntityReplicationPoliciesInfoMother.mvReplicableList();


        Flux<MVEntityReplicationPoliciesInfo> mvReplicationPoliciesInfoFlux =
                Flux.concat(
                        Flux.fromIterable(mvReplicableList),
                        Flux.fromIterable(MVEntityReplicationPoliciesInfoMother.mvNotReplicableList())
                );


        List<Id> expectedIds = mvReplicableList.stream()
                    .map(mv -> new Id(mv.id()))
                    .toList();

        var result = replicationPoliciesService.filterReplicableMvEntitiesList(processId, mvReplicationPoliciesInfoFlux);

        StepVerifier.create(result)
                .expectNextSequence(expectedIds)
                .verifyComplete();
    }

    @Test
    void itShouldExplainWhichPolicyRejectedTheEntity() {
        StepVerifier.create(replicationPoliciesService.evaluate("process-123",
                        MVEntityReplicationPoliciesInfoMother.replicationInvalidLifecycleStatus()))
                .assertNext(decision -> {
                    assertThat(decision.replicable()).isFalse();
                    assertThat(decision.reason()).startsWith("policy GP_1: lifecycleStatus=");
                })
                .verifyComplete();

        StepVerifier.create(replicationPoliciesService.evaluate("process-123",
                        MVEntityReplicationPoliciesInfoMother.replicationInvalidPastEndDateTime()))
                .assertNext(decision -> {
                    assertThat(decision.replicable()).isFalse();
                    assertThat(decision.reason()).startsWith("policy GP_2: validFor start=");
                })
                .verifyComplete();

        StepVerifier.create(replicationPoliciesService.evaluate("process-123",
                        MVEntityReplicationPoliciesInfoMother.replicationValidFields()))
                .expectNext(ReplicationDecision.REPLICABLE)
                .verifyComplete();
    }

    @Test
    void itShouldLogTheNotReplicableEntitiesOfABatchGroupedByReason() {
        String pastStart = Instant.now().minusSeconds(360000).toString();
        String pastEnd = Instant.now().minusSeconds(7200).toString();
        Flux<MVEntityReplicationPoliciesInfo> batch = Flux.just(
                new MVEntityReplicationPoliciesInfo("urn:ngsi-ld:product-offering:1", "product-offering", "Launched", null, null),
                new MVEntityReplicationPoliciesInfo("urn:ngsi-ld:product-offering:2", "product-offering", "Active", null, null),
                new MVEntityReplicationPoliciesInfo("urn:ngsi-ld:product-offering:3", "product-offering", "Active", null, null),
                new MVEntityReplicationPoliciesInfo("urn:ngsi-ld:product-offering:4", "product-offering", "Launched", pastStart, pastEnd));

        Logger logger = (Logger) LoggerFactory.getLogger(ReplicationPoliciesServiceImpl.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            StepVerifier.create(replicationPoliciesService.filterReplicableMvEntitiesList("process-123", batch))
                    .expectNext(new Id("urn:ngsi-ld:product-offering:1"))
                    .verifyComplete();
        } finally {
            logger.detachAppender(appender);
        }

        assertThat(appender.list).filteredOn(event -> event.getLevel() == Level.INFO)
                .extracting(ILoggingEvent::getFormattedMessage)
                .containsExactly("ProcessID: process-123 - Replication policies - product-offering: 1 of 4 replicable; "
                        + "not replicable: policy GP_1: lifecycleStatus=Active (2), policy GP_2: validFor (1)");
        assertThat(appender.list).filteredOn(event -> event.getLevel() == Level.WARN)
                .extracting(ILoggingEvent::getFormattedMessage)
                .containsExactly(
                        "ProcessID: process-123 - Replication policies - product-offering: policy GP_1: lifecycleStatus=Active (2): "
                                + "[urn:ngsi-ld:product-offering:2, urn:ngsi-ld:product-offering:3]",
                        "ProcessID: process-123 - Replication policies - product-offering: policy GP_2: validFor (1): "
                                + "[urn:ngsi-ld:product-offering:4 (start=" + pastStart + ", end=" + pastEnd + ")]");
    }
}
