package es.in2.desmos.domain.services.api.impl;

import es.in2.desmos.domain.models.EventQueue;
import es.in2.desmos.objectmothers.EventQueueMother;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Sinks;

import java.util.Queue;
import java.util.concurrent.PriorityBlockingQueue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class QueueServiceImplTests {

    @Test
    void itShouldReQueueTheEventInsteadOfDroppingItWhenEmissionFails() {
        QueueServiceImpl queueService = new QueueServiceImpl();

        @SuppressWarnings("unchecked")
        Sinks.Many<EventQueue> sinkMock = mock(Sinks.Many.class);
        when(sinkMock.tryEmitNext(any())).thenReturn(Sinks.EmitResult.FAIL_TERMINATED);
        ReflectionTestUtils.setField(queueService, "sink", sinkMock);

        EventQueue event = EventQueueMother.basicEventQueue("event 1");

        queueService.enqueueEvent(event).block();

        verify(sinkMock).tryEmitNext(event);

        @SuppressWarnings("unchecked")
        Queue<EventQueue> internalQueue = (PriorityBlockingQueue<EventQueue>) ReflectionTestUtils.getField(queueService, "queue");

        assertThat(internalQueue).contains(event);
    }

}
