package es.in2.desmos.support;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.net.InetAddress;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Stands in for the real DOME verifier so the two-node replication harness can mint and
 * validate JWTs offline. Serves the three endpoints {@link es.in2.desmos.infrastructure.security.VerifierServiceImpl}
 * needs: OIDC discovery, JWKS, and the M2M token endpoint.
 * <p>
 * The stub does not validate the incoming client assertion at all -- it is a synthetic
 * credential, not a security boundary. It exists purely to make the JWT round trip
 * (mint on one side, verify on the other) work without any internet dependency.
 */
public final class VerifierStub {

    private static final String KEY_ID = "it-verifier-key";

    private final MockWebServer server;
    private final ECKey signingKey;
    private final AtomicReference<Set<String>> trustedDltAddresses = new AtomicReference<>(Set.of());

    private VerifierStub(MockWebServer server, ECKey signingKey) {
        this.server = server;
        this.signingKey = signingKey;
    }

    /**
     * Replaces the set of DLT addresses (as 0x-prefixed hex strings) served at
     * {@code /trusted_access_nodes_list.yaml}, in the decimal-string format
     * TrustFrameworkConfig.deserializeDltAddress expects (it parses with
     * {@code new BigInteger(s)}, base 10 -- a plain "0x..." value throws
     * NumberFormatException). AccessNodeScheduler re-polls this URL every 5
     * minutes, so a node already running will pick up a change without a restart.
     */
    public void setTrustedDltAddresses(Set<String> hexAddresses) {
        trustedDltAddresses.set(hexAddresses);
    }

    /**
     * Starts the stub bound to all interfaces (0.0.0.0) so it is reachable both from the
     * host JVM and, once wired up, from containers via the Testcontainers host-exposure
     * mechanism. {@code MockWebServer.start(int)} alone only binds loopback.
     */
    public static VerifierStub start() {
        try {
            ECKey key = new ECKeyGenerator(Curve.P_256)
                    .keyID(KEY_ID)
                    .keyUse(KeyUse.SIGNATURE)
                    .algorithm(JWSAlgorithm.ES256)
                    .generate();
            MockWebServer server = new MockWebServer();
            VerifierStub stub = new VerifierStub(server, key);
            server.setDispatcher(stub.dispatcher());
            server.start(InetAddress.getByName("0.0.0.0"), 0);
            return stub;
        } catch (JOSEException | IOException e) {
            throw new IllegalStateException("Could not start VerifierStub", e);
        }
    }

    public void stop() {
        try {
            server.shutdown();
        } catch (IOException e) {
            throw new IllegalStateException("Could not stop VerifierStub", e);
        }
    }

    /**
     * The base URL this stub is reachable at, e.g. {@code http://127.0.0.1:54321}. Pass this
     * verbatim as {@code verifier.url} -- it must be byte-identical on every node that needs
     * to validate a token minted here, since VerifierServiceImpl compares it against the
     * token's {@code iss} claim.
     */
    public String url() {
        // NOT server.getHostName(): the stub is bound to 0.0.0.0 (all interfaces) so both
        // the host JVM and containers can reach it, but "0.0.0.0" itself is a bind address,
        // not a routable destination -- connecting *to* it fails. host.testcontainers.internal
        // is the canonical, resolvable-from-containers alias Testcontainers maintains for the
        // Docker host; node A (the host JVM, which cannot resolve that name on its own) reaches
        // it via HostRewritingWebClientTestConfig instead. Both sides end up at the exact same
        // URL string, which VerifierServiceImpl requires for its "iss" claim comparison.
        return "http://host.testcontainers.internal:" + server.getPort();
    }

    /** The stub's own bound port, needed to expose it to containers via Testcontainers.exposeHostPorts. */
    public int port() {
        return server.getPort();
    }

    /** URL to hand to {@code access-node.trustedAccessNodesList}. */
    public String trustedAccessNodesListUrl() {
        return url() + "/trusted_access_nodes_list.yaml";
    }

    private String trustedAccessNodesListYaml() {
        List<String> lines = trustedDltAddresses.get().stream()
                .map(hex -> "  - name: it-node\n    dlt_address: \"" + hexToDecimal(hex) + "\"")
                .toList();
        return "organizations:\n" + (lines.isEmpty() ? "" : String.join("\n", lines) + "\n");
    }

    private static java.math.BigInteger hexToDecimal(String hexAddress) {
        return new java.math.BigInteger(hexAddress.replaceFirst("^0x", ""), 16);
    }

    /**
     * Mints a fresh, valid access token as the stub's own OIDC token endpoint would.
     * {@code subject} becomes both the {@code sub} claim and the returned token's holder.
     */
    public String mintAccessToken(String subject) {
        try {
            Instant now = Instant.now();
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .issuer(url())
                    .subject(subject)
                    .audience(url())
                    .issueTime(Date.from(now))
                    .expirationTime(Date.from(now.plusSeconds(3600)))
                    .jwtID(UUID.randomUUID().toString())
                    .build();
            SignedJWT jwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.ES256).keyID(KEY_ID).build(),
                    claims);
            jwt.sign(new ECDSASigner(signingKey));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException("Could not mint access token", e);
        }
    }

    private Dispatcher dispatcher() {
        return new Dispatcher() {
            @Override
            public MockResponse dispatch(@NotNull RecordedRequest request) {
                String path = request.getPath() == null ? "" : request.getPath();
                if (path.startsWith("/.well-known/openid-configuration")) {
                    return jsonResponse("""
                            {"token_endpoint": "%s/token", "jwks_uri": "%s/jwks"}
                            """.formatted(url(), url()));
                }
                if (path.startsWith("/jwks")) {
                    return jsonResponse(new JWKSet(signingKey.toPublicJWK()).toString());
                }
                if (path.startsWith("/token")) {
                    // A real verifier would validate the client_assertion (VP token) in the
                    // request body; this stub trusts any request and always mints a token.
                    String accessToken = mintAccessToken("it-client");
                    return jsonResponse("""
                            {"access_token": "%s", "token_type": "Bearer", "expires_in": "3600"}
                            """.formatted(accessToken));
                }
                if (path.startsWith("/trusted_access_nodes_list.yaml")) {
                    return new MockResponse()
                            .setHeader("Content-Type", "text/yaml")
                            .setBody(trustedAccessNodesListYaml());
                }
                return new MockResponse().setResponseCode(404);
            }
        };
    }

    private static MockResponse jsonResponse(String body) {
        return new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(body);
    }
}
