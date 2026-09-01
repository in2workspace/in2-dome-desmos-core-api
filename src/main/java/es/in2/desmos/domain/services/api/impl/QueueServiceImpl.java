package es.in2.desmos.domain.services.api.impl;

import es.in2.desmos.domain.models.EventQueue;
import es.in2.desmos.domain.services.api.QueueService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Service
@RequiredArgsConstructor
public class QueueServiceImpl implements QueueService {

    // unicast (not multicast): there is exactly one real consumer per queue (the corresponding
    // Publish/Subscribe workflow), and unlike multicast, unicast buffers events emitted before that
    // consumer subscribes instead of silently dropping them - which matters because at application
    // startup, notifications can arrive and be enqueued before ApplicationRunner finishes wiring up
    // the workflow's subscription.
    private final Sinks.Many<EventQueue> sink = Sinks.many().unicast().onBackpressureBuffer();
    private final PriorityBlockingQueue<EventQueue> queue = new PriorityBlockingQueue<>();

    private final AtomicBoolean paused = new AtomicBoolean(false);
    private final Queue<EventQueue> buffer = new ConcurrentLinkedQueue<>();

    @Override
    public Mono<Void> enqueueEvent(EventQueue event) {
        if (paused.get()) {
            buffer.offer(event);
            return Mono.empty();
        }

        if (queue.offer(event)) {
            log.debug("Event added to queue - queue: {}", queue);
            emitNext();
        }
        return Mono.empty();
    }

    private synchronized void emitNext() {
        EventQueue eventQueue = queue.poll();
        if (eventQueue != null) {
            log.debug("Emitting event from queue - queue: {}", eventQueue);
            Sinks.EmitResult result = sink.tryEmitNext(eventQueue);
            if (result.isFailure()) {
                log.error("Failed to emit event ({}), re-queueing instead of dropping it - queue: {}", result, eventQueue);
                if (!queue.offer(eventQueue)) {
                    log.error("Failed to re-queue event after emission failure, event has been lost - queue: {}", eventQueue);
                }
            }
        }
    }

    @Override
    public Flux<EventQueue> getEventStream() {
        return sink.asFlux();
    }

    public void pause() {
        paused.set(true);
        log.debug("Queue paused.");
    }

    public void resume() {
        paused.set(false);
        log.debug("Queue resumed.");
        // Procesar todos los eventos almacenados en buffer
        processBufferedEvents();
    }

    private synchronized void processBufferedEvents() {
        EventQueue eventQueue;
        while ((eventQueue = buffer.poll()) != null) {
            if (queue.offer(eventQueue)) {
                log.debug("Re-processing buffered event - queue: {}", eventQueue);
                emitNext();
            }
        }
    }

}
