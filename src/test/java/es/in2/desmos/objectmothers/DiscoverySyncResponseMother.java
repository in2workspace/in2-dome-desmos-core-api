package es.in2.desmos.objectmothers;

import com.fasterxml.jackson.core.JsonProcessingException;
import es.in2.desmos.domain.models.DiscoverySyncResponse;
import es.in2.desmos.domain.models.MVEntity4DataNegotiation;
import org.jetbrains.annotations.NotNull;
import org.json.JSONException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

public final class DiscoverySyncResponseMother {
    private DiscoverySyncResponseMother() {
    }

    public static @NotNull DiscoverySyncResponse list3And4(String contextBrokerExternalDomain) throws JSONException, NoSuchAlgorithmException, JsonProcessingException {
        List<MVEntity4DataNegotiation> mvEntities4DataNegotiation = new ArrayList<>();
        mvEntities4DataNegotiation.add(MVEntity4DataNegotiationMother.sample3());
        mvEntities4DataNegotiation.add(MVEntity4DataNegotiationMother.sample4());
        return new DiscoverySyncResponse(Mono.just(contextBrokerExternalDomain), Flux.fromIterable(mvEntities4DataNegotiation));
    }

    public static @NotNull DiscoverySyncResponse fromList(String contextBrokerExternalDomain, List<MVEntity4DataNegotiation> entities) {
        return new DiscoverySyncResponse(Mono.just(contextBrokerExternalDomain), Flux.fromIterable(entities));
    }
}
