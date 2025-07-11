package es.in2.desmos.domain.services.policies;

import es.in2.desmos.domain.models.Id;
import es.in2.desmos.domain.models.MVEntityReplicationPoliciesInfo;
import es.in2.desmos.domain.services.policies.impl.ReplicationPoliciesServiceImpl;
import es.in2.desmos.objectmothers.MVEntityReplicationPoliciesInfoMother;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

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
}