package es.in2.desmos.support;

import es.in2.desmos.infrastructure.configs.ApiConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Makes node A's (in-process) JVM able to reach services advertised under
 * {@code host.testcontainers.internal} -- the hostname Testcontainers injects into
 * <em>containers</em> so they can call back out to the Docker host. The host JVM itself
 * cannot resolve that name, but it doesn't need to: it IS the host. This bean rewrites
 * just the host part of any outbound request to {@code localhost}, leaving everything
 * else -- scheme, port, path -- untouched.
 * <p>
 * This exists specifically for {@code verifier.url}: {@code VerifierServiceImpl} compares
 * the configured URL against a token's {@code iss} claim as a literal string, so node A and
 * node B (a container) must be configured with the exact same URL even though they reach
 * it over different routes. Import this in any replication test whose
 * {@code verifier.url} / {@code access-node.trustedAccessNodesList} points at
 * {@code host.testcontainers.internal}.
 */
@TestConfiguration
public class HostRewritingWebClientTestConfig {

    private static final String CONTAINER_TO_HOST_ALIAS = "host.testcontainers.internal";

    @Bean
    @Primary
    public WebClient hostRewritingWebClient(ApiConfig apiConfig) {
        ExchangeFilterFunction rewriteContainerHostAlias = (request, next) -> {
            if (CONTAINER_TO_HOST_ALIAS.equals(request.url().getHost())) {
                var rewrittenUrl = UriComponentsBuilder.fromUri(request.url())
                        .host("localhost")
                        .build(true)
                        .toUri();
                return next.exchange(ClientRequest.from(request).url(rewrittenUrl).build());
            }
            return next.exchange(request);
        };
        return WebClient.builder()
                .codecs(configurer -> configurer.defaultCodecs()
                        .maxInMemorySize((int) apiConfig.getMaxInMemorySize().toBytes()))
                .filter(rewriteContainerHostAlias)
                .build();
    }
}
