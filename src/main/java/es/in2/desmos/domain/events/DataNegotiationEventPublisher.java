package es.in2.desmos.domain.events;

import es.in2.desmos.domain.models.DataNegotiationEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class DataNegotiationEventPublisher {

    private final ApplicationEventPublisher applicationEventPublisher;

    public void publishEvent(DataNegotiationEvent dataNegotiationEvent) {
        log.debug("ProcessID: {} - Publishing data negotiation event", dataNegotiationEvent.processId());

        applicationEventPublisher.publishEvent(dataNegotiationEvent);
    }

}
