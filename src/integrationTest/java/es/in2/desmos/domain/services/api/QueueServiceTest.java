package es.in2.desmos.domain.services.api;

import es.in2.desmos.support.ContainerManager;
import es.in2.desmos.objectmothers.EventQueueMother;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.test.StepVerifier;

@SpringBootTest
@Testcontainers
class QueueServiceTest {

    @Autowired
    private QueueService queueServiceImpl;

    @DynamicPropertySource
    static void setDynamicProperties(DynamicPropertyRegistry registry) {
        ContainerManager.postgresqlProperties(registry);
    }

    @Test
    void itShouldNotReturnEventsWhenPause() {
        var event1 = EventQueueMother.basicEventQueue("event 1");
        var event2 = EventQueueMother.basicEventQueue("event 2");
        var event3 = EventQueueMother.basicEventQueue("event 3");

        queueServiceImpl.pause();

        queueServiceImpl.enqueueEvent(event1).block();
        queueServiceImpl.enqueueEvent(event2).block();
        queueServiceImpl.enqueueEvent(event3).block();

        StepVerifier.create(queueServiceImpl.getEventStream())
                .expectSubscription()
                .thenAwait()
                .expectNoEvent(java.time.Duration.ofMillis(100));
    }

    @Test
    void itShouldReturnEventsWhenResumeAfterPause() {
        var event1 = EventQueueMother.basicEventQueue("event 1");
        var event2 = EventQueueMother.basicEventQueue("event 2");
        var event3 = EventQueueMother.basicEventQueue("event 3");

        queueServiceImpl.pause();

        queueServiceImpl.enqueueEvent(event1).block();
        queueServiceImpl.enqueueEvent(event2).block();
        queueServiceImpl.enqueueEvent(event3).block();

        queueServiceImpl.resume();

        StepVerifier.create(queueServiceImpl.getEventStream())
                .expectNext(event1)
                .expectNext(event2)
                .expectNext(event3);
    }

}