package es.in2.desmos.application.workflows;

import reactor.core.publisher.Flux;

public interface PublishWorkflow {
    Flux<Void> startPublishWorkflow();
}
