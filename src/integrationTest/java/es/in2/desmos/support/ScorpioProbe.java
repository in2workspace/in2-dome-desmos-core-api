package es.in2.desmos.support;

import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;

/**
 * Collapses the repeated "GET an NGSI-LD entity straight from a Scorpio broker" call that both
 * {@code PublishFlowBehaviorTest}-style (producer) and {@code SubscribeFlowBehaviorTest}-style
 * (consumer) tests need, on either node.
 */
public final class ScorpioProbe {

    private ScorpioProbe() {
    }

    /** Fetches the entity's canonical JSON from a broker; throws if it isn't there. */
    public static String getEntity(String brokerBaseUri, String entityId) {
        return WebClient.create()
                .get()
                .uri(brokerBaseUri + "/ngsi-ld/v1/entities/" + entityId)
                .retrieve()
                .bodyToMono(String.class)
                .block(Duration.ofSeconds(5));
    }

    /** Same as {@link #getEntity}, but returns {@code null} instead of throwing on a 404. */
    public static String getEntityOrNull(String brokerBaseUri, String entityId) {
        try {
            return getEntity(brokerBaseUri, entityId);
        } catch (WebClientResponseException.NotFound e) {
            return null;
        }
    }
}
