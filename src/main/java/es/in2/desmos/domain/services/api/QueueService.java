package es.in2.desmos.domain.services.api;

import es.in2.desmos.domain.models.EventQueue;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface QueueService {

    Mono<Void> enqueueEvent(EventQueue event);

    Flux<EventQueue> getEventStream();

    void pause();

    void resume();

    /**
     * Number of events waiting to be consumed: queued, buffered while paused, or emitted but not yet
     * requested by the consumer. Only used for logging.
     */
    long pendingEvents();

}
