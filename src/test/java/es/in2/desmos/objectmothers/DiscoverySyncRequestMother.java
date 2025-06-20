package es.in2.desmos.objectmothers;

import com.fasterxml.jackson.core.JsonProcessingException;
import es.in2.desmos.domain.models.DiscoverySyncRequest;
import es.in2.desmos.domain.models.MVEntity4DataNegotiation;
import org.jetbrains.annotations.NotNull;
import org.json.JSONException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

public final class DiscoverySyncRequestMother {
    private DiscoverySyncRequestMother() {
    }

    public static @NotNull DiscoverySyncRequest list1And2() throws JSONException, NoSuchAlgorithmException, JsonProcessingException {
        String issuer = "https://my-domain.org";
        List<MVEntity4DataNegotiation> mVEntity4DataNegotiationIds = new ArrayList<>();
        mVEntity4DataNegotiationIds.add(MVEntity4DataNegotiationMother.sample1());
        mVEntity4DataNegotiationIds.add(MVEntity4DataNegotiationMother.sample2());

        return new DiscoverySyncRequest(Mono.just(issuer), Flux.fromIterable(mVEntity4DataNegotiationIds));
    }

    public static @NotNull DiscoverySyncRequest scorpioFullList(String issuer) throws JSONException, NoSuchAlgorithmException, JsonProcessingException {
        List<MVEntity4DataNegotiation> mVEntity4DataNegotiationIds =
                List.of(
                        MVEntity4DataNegotiationMother.sample1(),
                        MVEntity4DataNegotiationMother.sample2(),
                        MVEntity4DataNegotiationMother.sample3(),
                        MVEntity4DataNegotiationMother.sample4());

        return new DiscoverySyncRequest(Mono.just(issuer), Flux.fromIterable(mVEntity4DataNegotiationIds));
    }
}
