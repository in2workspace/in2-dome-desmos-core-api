package es.in2.desmos.objectmothers;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import es.in2.desmos.domain.models.DiscoverySyncResponse;
import org.json.JSONException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.security.NoSuchAlgorithmException;

public final class DiscoveryResponseMother {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    private DiscoveryResponseMother() {
    }

    public static String scorpioJson2List() throws JsonProcessingException, JSONException, NoSuchAlgorithmException {
        Mono<String> externalDomain = Mono.just("http://external-domain.org");
        DiscoverySyncResponse discoverySyncResponse = new DiscoverySyncResponse(externalDomain, Flux.just(MVEntity4DataNegotiationMother.sample2()));

        return objectMapper.writeValueAsString(discoverySyncResponse);
    }

    public static String scorpioJson4List() throws JsonProcessingException, JSONException, NoSuchAlgorithmException {
        Mono<String> externalDomain = Mono.just("http://external-domain.org");
        DiscoverySyncResponse discoverySyncResponse = new DiscoverySyncResponse(externalDomain, Flux.just(MVEntity4DataNegotiationMother.sample4()));

        return objectMapper.writeValueAsString(discoverySyncResponse);
    }
}
