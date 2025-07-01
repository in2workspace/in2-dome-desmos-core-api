package es.in2.desmos.domain.events;

import com.fasterxml.jackson.core.JsonProcessingException;
import es.in2.desmos.domain.models.DataNegotiationEvent;
import es.in2.desmos.domain.models.MVEntity4DataNegotiation;
import es.in2.desmos.objectmothers.MVEntity4DataNegotiationMother;
import org.json.JSONException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import reactor.core.publisher.Mono;

import java.security.NoSuchAlgorithmException;
import java.util.List;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DataNegotiationEventPublisherTests {
    @InjectMocks
    private DataNegotiationEventPublisher dataNegotiationEventPublisher;

    @Mock
    private ApplicationEventPublisher applicationEventPublisher;

    @Test
    void itShouldPublicateEvent() throws JSONException, NoSuchAlgorithmException, JsonProcessingException {

        Mono<String> issuer = Mono.just("https://example.org");
        Mono<List<MVEntity4DataNegotiation>> externalEntityIds = MVEntity4DataNegotiationMother.list1And2().collectList(); //TODO: revisar esto
        Mono<List<MVEntity4DataNegotiation>> internalEntityIds = MVEntity4DataNegotiationMother.list3And4().collectList();

        String processId = "0";
        DataNegotiationEvent dataNegotiationEvent = new DataNegotiationEvent(processId, issuer, externalEntityIds, internalEntityIds);
        dataNegotiationEventPublisher.publishEvent(dataNegotiationEvent);

        verify(applicationEventPublisher, times(1)).publishEvent(dataNegotiationEvent);
        verifyNoMoreInteractions(applicationEventPublisher);
    }
}
