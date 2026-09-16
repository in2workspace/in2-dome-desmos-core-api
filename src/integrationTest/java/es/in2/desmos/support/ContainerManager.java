package es.in2.desmos.support;

import es.in2.desmos.domain.utils.EndpointsConstants;
import org.slf4j.LoggerFactory;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.Testcontainers;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.containers.wait.strategy.WaitAllStrategy;
import org.testcontainers.containers.wait.strategy.WaitStrategy;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.List;

@SuppressWarnings("resource")
@org.testcontainers.junit.jupiter.Testcontainers
public class ContainerManager {

    private static final ContainerManager INSTANCE = new ContainerManager();

    // Both nodes share one Docker network so node A and node B can address each
    // other by alias. Two-node replication is impossible on isolated networks.
    private static final Network NETWORK = Network.newNetwork();

    // Node A is the in-process @SpringBootTest JVM, not a container -- but node B (a
    // container) still needs a fixed, known address to call it back on, and that
    // address must be baked into node A's config (api.externalDomain, notification
    // endpoints) *before* the Spring context that will actually bind to it exists.
    // Reserved once, up front, so every static field below can reference it.
    private static final int NODE_A_PORT = reservePort();
    private static final String NODE_A_EXTERNAL_DOMAIN = "http://host.testcontainers.internal:" + NODE_A_PORT;

    // Node B gets the same treatment for the reverse direction: node A (also not a
    // container) needs a stable URL for node B's own dataLocation/entities callbacks,
    // so node B's host port is fixed rather than left to Testcontainers' random choice.
    private static final int NODE_B_PORT = reservePort();

    private static final VerifierStub VERIFIER = VerifierStub.start();

    // An arbitrary DLT address a DLT-notification test can present as the sender.
    // BlockchainListenerServiceImpl.checkIfParticipantExistsInTrustedList rejects any
    // notification whose ethereumAddress isn't in the receiving node's trusted list, and
    // that list is fetched once at startup (refreshed only on AccessNodeScheduler's 5-minute
    // cron) -- so it must be registered before node B's container starts, not per-test.
    private static final String TRUSTED_DLT_SENDER_ADDRESS = "0x9c1a2f4e6b8d0a3c5f7e9b1d3a5c7e9f1b3d5a7c";

    static {
        // Must run before any container that resolves host.testcontainers.internal is
        // created -- it configures the special-purpose container Testcontainers uses to
        // proxy traffic from the Docker network back to these host ports.
        Testcontainers.exposeHostPorts(NODE_A_PORT, VERIFIER.port());
        VERIFIER.setTrustedDltAddresses(java.util.Set.of(TRUSTED_DLT_SENDER_ADDRESS));
    }

    private static final GenericContainer<?> scorpioContainerA;
    private static final GenericContainer<?> postgisContainerA;
    private static final GenericContainer<?> blockchainAdapterContainerA;
    private static final PostgreSQLContainer<?> postgresContainerA;

    private static final GenericContainer<?> desmosContainerB;
    private static final GenericContainer<?> scorpioContainerB;
    private static final GenericContainer<?> postgisContainerB;
    private static final GenericContainer<?> blockchainAdapterContainerB;
    private static final PostgreSQLContainer<?> postgresContainerB;

    //Remember update the version
    private final static String pathVersion = "v2";
    private final static String dltAdapterNotificationEndpoint = "/api/"+ pathVersion + EndpointsConstants.DLT_ADAPTER_NOTIFICATION;
    private final static String brokerNotificationEndpoint="/api/"+pathVersion + EndpointsConstants.CONTEXT_BROKER_NOTIFICATION;
    private static final String SCORPIO_IMAGE = "scorpiobroker/all-in-one-runner:java-6.0.2";

    // Distinct from node A's (getSecurityPrivateKey()) so the two nodes are clearly
    // separate identities; both are arbitrary secp256r1 keys used only within this
    // test harness.
    private static final String NODE_B_SECURITY_PRIVATE_KEY =
            "0x2bff61edb2b0b8a6be9f99cc7ef8c1e6f8f2d5a4b3c1e0d9a8b7c6d5e4f3a2b1";

    // A well-formed sample JWT (HS256, unrelated to any real signing key): only its
    // "sub" claim is ever read, by M2MAccessTokenProvider.getVCinJWTDecodedFromBase64.
    // It is never itself verified by anyone, so both nodes can safely share it.
    private static final String SAMPLE_LEAR_CREDENTIAL_BASE64 =
            "ZXlKaGJHY2lPaUpJVXpJMU5pSXNJblI1Y0NJNklrcFhWQ0o5LmV5SnpkV0lpT2lJeE1qTTBOVFkzT0Rrd0lpd2libUZ0WlNJNklrcHZhRzRnUkc5bElpd2lhV0YwSWpveE5URTJNak01TURJeWZRLlNmbEt4d1JKU01lS0tGMlFUNGZ3cE1lSmYzNlBPazZ5SlZfYWRRc3N3NWM=";

    // The Postgres image restarts its server process once during initialization
    // (initdb, then a real start), so "database system is ready to accept
    // connections" appears twice in the log before the container is truly ready.
    // This is the same pattern Testcontainers' own PostgreSQLContainer waits on.
    private static final WaitStrategy POSTGIS_WAIT_STRATEGY =
            Wait.forLogMessage(".*database system is ready to accept connections.*\\s", 2)
                    .withStartupTimeout(Duration.ofMinutes(3));

    // Verified empirically: the all-in-one-runner exposes a genuine Quarkus
    // SmallRye health check on /q/health (not just an open port), and it only
    // returns 200 once Flyway migrations and the HTTP listener are both up.
    private static final WaitStrategy SCORPIO_WAIT_STRATEGY =
            Wait.forHttp("/q/health").forStatusCode(200)
                    .withStartupTimeout(Duration.ofMinutes(3));

    private static final WaitStrategy DLT_ADAPTER_WAIT_STRATEGY =
            Wait.forHttp(EndpointsConstants.HEALTH).forStatusCode(200)
                    .withStartupTimeout(Duration.ofMinutes(3));

    // /health alone is not enough for node B: actuator binds, and answers 200,
    // *before* ApplicationRunner's broker/blockchain subscription and initial P2P
    // sync steps run -- and any of those failing calls System.exit (see
    // ApplicationRunner.finishApplication), killing the container after /health
    // already looked fine. "Queues have been authorized and enabled" (no trailing
    // punctuation -- verified against ApplicationRunner's actual log.info call) is
    // the log line ApplicationRunner emits only once every startup step has
    // actually run its course, success or not (see initializeDataSync's
    // doOnTerminate, which fires on error too).
    private static final WaitStrategy DESMOS_NODE_WAIT_STRATEGY = new WaitAllStrategy()
            .withStrategy(Wait.forHttp(EndpointsConstants.HEALTH).forStatusCode(200))
            .withStrategy(Wait.forLogMessage(".*Queues have been authorized and enabled.*\\s", 1))
            .withStartupTimeout(Duration.ofMinutes(3));

    static {
        // Node A
        postgresContainerA = new PostgreSQLContainer<>("postgres:latest")
                .withDatabaseName("it_db")
                .withUsername("postgres")
                .withPassword("postgres")
                // The default max_connections=100 is not enough: every one of the ~15 integration
                // test classes outside the replication package declares its own
                // @DynamicPropertySource, and Spring's test context cache keys off that method
                // identity -- so each gets its own Spring context (and its own R2DBC pool plus a
                // Flyway JDBC connection at startup), all against this same shared container, all
                // potentially cached and alive at once for the life of the test JVM. Confirmed via
                // repeated full-suite runs failing with "FATAL: sorry, too many clients already"
                // in whichever of those classes happened to boot its context last.
                .withCommand("postgres", "-c", "max_connections=300")
                .withNetwork(NETWORK)
                .withNetworkAliases("postgres-node-a");
        postgisContainerA = new GenericContainer<>(DockerImageName.parse("postgis/postgis"))
                .withExposedPorts(5432)
                .withEnv("POSTGRES_USER", "ngb")
                .withEnv("POSTGRES_PASSWORD", "ngb")
                .withEnv("POSTGRES_DB", "ngb")
                .withNetwork(NETWORK)
                .withNetworkAliases("postgis-node-a")
                .waitingFor(POSTGIS_WAIT_STRATEGY);
        scorpioContainerA = new GenericContainer<>(DockerImageName.parse(SCORPIO_IMAGE))
                .withExposedPorts(9090)
                .withEnv("DBHOST", "postgis-node-a")
                .dependsOn(postgisContainerA)
                .withNetwork(NETWORK)
                .withNetworkAliases("scorpio-node-a")
                .waitingFor(SCORPIO_WAIT_STRATEGY);
        blockchainAdapterContainerA = new GenericContainer<>(DockerImageName.parse("quay.io/digitelts/dlt-adapter:1.5.1"))
                .withExposedPorts(8080)
                .withEnv("PRIVATE_KEY", "0x304d170fb355df65cc17ef7934404fe9baee73a1244380076436dec6fafb1e1f")
                .withEnv("DOME_EVENTS_CONTRACT_ADDRESS", "")
                .withEnv("RPC_ADDRESS", "http://blockchain-testnode.infra.svc.cluster.local:8545/")
                .withEnv("DOME_PRODUCTION_BLOCK_NUMBER", "0")
                .withEnv("ISS", "0x9eb763b0a6b7e617d56b85f1df943f176018c8eedb2dd9dd37c0bd77496833fe")
                .withNetwork(NETWORK)
                .withNetworkAliases("dlt-adapter-node-a")
                .waitingFor(DLT_ADAPTER_WAIT_STRATEGY);

        // Node B
        postgresContainerB = new PostgreSQLContainer<>("postgres:latest")
                .withDatabaseName("it_db")
                .withUsername("postgres")
                .withPassword("postgres")
                .withNetwork(NETWORK)
                .withNetworkAliases("postgres-node-b")
                .withInitScript("db/populate/Create_Postgre_B_AuditRecords.sql");
        postgisContainerB = new GenericContainer<>(DockerImageName.parse("postgis/postgis"))
                .withExposedPorts(5432)
                .withEnv("POSTGRES_USER", "ngb")
                .withEnv("POSTGRES_PASSWORD", "ngb")
                .withEnv("POSTGRES_DB", "ngb")
                .withNetwork(NETWORK)
                .withNetworkAliases("postgis-node-b")
                .waitingFor(POSTGIS_WAIT_STRATEGY);
        scorpioContainerB = new GenericContainer<>(DockerImageName.parse(SCORPIO_IMAGE))
                .withExposedPorts(9090)
                .withEnv("DBHOST", "postgis-node-b")
                .dependsOn(postgisContainerB)
                .withNetwork(NETWORK)
                .withNetworkAliases("scorpio-node-b")
                .waitingFor(SCORPIO_WAIT_STRATEGY);
        blockchainAdapterContainerB = new GenericContainer<>(DockerImageName.parse("quay.io/digitelts/dlt-adapter:1.5.1"))
                .withExposedPorts(8080)
                .withEnv("PRIVATE_KEY", "0x304d170fb355df65cc17ef7934404fe9baee73a1244380076436dec6fafb1e1f")
                .withEnv("DOME_EVENTS_CONTRACT_ADDRESS", "")
                .withEnv("RPC_ADDRESS", "http://blockchain-testnode.infra.svc.cluster.local:8545/")
                .withEnv("DOME_PRODUCTION_BLOCK_NUMBER", "0")
                .withEnv("ISS", "0x9eb763b0a6b7e617d56b85f1df943f176018c8eedb2dd9dd37c0bd77496833fe")
                .withNetwork(NETWORK)
                .withNetworkAliases("dlt-adapter-node-b")
                .waitingFor(DLT_ADAPTER_WAIT_STRATEGY);
        desmosContainerB = new GenericContainer<>(DockerImageName.parse(resolveDesmosImage()))
                .withExposedPorts(8080)
                .withEnv("LOGGING_LEVEL_ES_IN2_DESMOS", "DEBUG")
                .withEnv("SPRING_R2DBC_URL", "r2dbc:postgresql://postgres-node-b:5432/it_db")
                .withEnv("SPRING_R2DBC_USERNAME", "postgres")
                .withEnv("SPRING_R2DBC_PASSWORD", "postgres")
                .withEnv("SPRING_FLYWAY_URL", "jdbc:postgresql://postgres-node-b:5432/it_db")
                .withEnv("OPERATOR_ORGANIZATION_IDENTIFIER", "VATES-S9999999E")
                .withEnv("API_EXTERNAL_DOMAIN", "http://localhost:" + NODE_B_PORT)
                .withEnv("DLT_ADAPTER_PROVIDER", "digitelts")
                .withEnv("DLT_ADAPTER_INTERNAL_DOMAIN", "http://dlt-adapter-node-b:8080")
                .withEnv("DLT_ADAPTER_EXTERNAL_DOMAIN", "http://dlt-adapter-node-b:8080")
                .withEnv("TX_SUBSCRIPTION_NOTIFICATION_ENDPOINT", "http://desmos-node-b:8080" + dltAdapterNotificationEndpoint)
                .withEnv("BROKER_PROVIDER", "scorpio")
                .withEnv("BROKER_INTERNAL_DOMAIN", "http://scorpio-node-b:9090")
                .withEnv("BROKER_EXTERNAL_DOMAIN", "http://scorpio-node-b:9090")
                .withEnv("NGSI_SUBSCRIPTION_NOTIFICATION_ENDPOINT", "http://desmos-node-b:8080" + brokerNotificationEndpoint)
                // Deliberately NOT EXTERNAL_ACCESS_NODES_URLS=NODE_A_EXTERNAL_DOMAIN. Node B's
                // container is a static, JVM-wide singleton that starts (and runs its own
                // ApplicationRunner.onApplicationReady, including the initial P2P sync) before
                // ANY test's Spring context exists -- so node A is never listening yet when B
                // would try to reach it. That sync failure is not just logged and ignored: since
                // ApplicationRunner.isQueueAuthorizedForEmit is set true only inside
                // initializeDataSync's doOnComplete, and neither the backoffice endpoint's
                // resync nor DataSyncScheduler's daily job ever re-invoke
                // ApplicationRunner.initializeQueueProcessing, a failed *initial* sync
                // permanently disables both the publish and subscribe queue consumers for the
                // life of the process -- confirmed by zero "Starting the Publish/Subscribe
                // Workflow" log lines ever appearing. A genuine production robustness gap
                // (a real node whose configured peer is briefly unreachable at boot would hit
                // the same permanent degradation), filed as an observation rather than fixed
                // here since it's outside this change's scope. Leaving external-access-nodes.urls
                // unset keeps node B's own initial sync trivially successful (nothing to
                // contact), which is all any current replication test needs; a future
                // B-initiated round-trip scenario should trigger B's sync on demand via its own
                // /backoffice/v2/actions/sync, the same way P2PDataSyncBehaviorTest drives A's.
                .withEnv("VERIFIER_URL", VERIFIER.url())
                .withEnv("ACCESS_NODE_TRUSTED_ACCESS_NODES_LIST", VERIFIER.trustedAccessNodesListUrl())
                .withEnv("SECURITY_PRIVATE_KEY", NODE_B_SECURITY_PRIVATE_KEY)
                .withEnv("SECURITY_LEAR_CREDENTIAL_MACHINE_IN_BASE64", SAMPLE_LEAR_CREDENTIAL_BASE64)
                .dependsOn(blockchainAdapterContainerB)
                .dependsOn(scorpioContainerB)
                .dependsOn(postgresContainerB)
                .withNetwork(NETWORK)
                .withNetworkAliases("desmos-node-b")
                .withLogConsumer(new Slf4jLogConsumer(LoggerFactory.getLogger("desmos-node-b")))
                .waitingFor(DESMOS_NODE_WAIT_STRATEGY);
        // Fixed host port, mirroring NODE_A_PORT: node A (also not a container) needs a
        // stable address for node B's dataLocation/entities callbacks, computed above
        // and baked into API_EXTERNAL_DOMAIN before the container even starts.
        desmosContainerB.setPortBindings(List.of(NODE_B_PORT + ":8080"));

        // Start node A's infra, node B's infra and node B itself concurrently rather
        // than one dependsOn chain at a time -- this is the single biggest lever on
        // total startup time for a ~9-container topology.
        Startables.deepStart(
                postgresContainerA, postgisContainerA, scorpioContainerA, blockchainAdapterContainerA,
                postgresContainerB, postgisContainerB, scorpioContainerB, blockchainAdapterContainerB,
                desmosContainerB
        ).join();
    }

    private static int reservePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException("Could not reserve a free port", e);
        }
    }

    /**
     * Which image node B (the containerized Desmos instance) runs.
     * Defaults to building from the working tree (see {@code buildDesmosTestImage} in
     * build.gradle); pass {@code -Pdesmos.image=<ref>} to point at a published tag instead.
     */
    private static String resolveDesmosImage() {
        String override = System.getProperty("desmos.image");
        if (override != null && !override.isBlank()) {
            return override;
        }
        return "in2workspace/in2-desmos-api:v2.0.19";
    }

    public static ContainerManager getInstance() {
        return INSTANCE;
    }

    @DynamicPropertySource
    public static void postgresqlProperties(DynamicPropertyRegistry registry) {
        // Node A
        registry.add("spring.r2dbc.url", () -> String.format("r2dbc:pool:postgresql://%s:%s/it_db",
                postgresContainerA.getHost(),
                postgresContainerA.getFirstMappedPort()));
        registry.add("spring.r2dbc.username", postgresContainerA::getUsername);
        registry.add("spring.r2dbc.password", postgresContainerA::getPassword);
        registry.add("spring.flyway.url", postgresContainerA::getJdbcUrl);
        registry.add("broker.internalDomain", ContainerManager::getBaseUriForScorpioA);
        registry.add("dlt-adapter.externalDomain", ContainerManager::getBaseUriBlockchainAdapterA);
    }

    @DynamicPropertySource
    public static void externalAccessNodesProperties(DynamicPropertyRegistry registry) {
        registry.add("external-access-nodes.urls", ContainerManager::getBaseUriDesmosB);
    }

    @DynamicPropertySource
    public static void securityProperties(DynamicPropertyRegistry registry) {
        registry.add("security.privateKey", ContainerManager::getSecurityPrivateKey);
    }

    /**
     * Everything a replication test needs on top of {@link #postgresqlProperties} to make
     * node A reachable FROM node B (a container) and able to mint/validate JWTs against the
     * same {@link VerifierStub} node B uses. Fixes {@code server.port} to the pre-reserved
     * {@link #NODE_A_PORT} -- {@code RANDOM_PORT}/{@code DEFINED_PORT} without a known port
     * would leave {@link #NODE_A_EXTERNAL_DOMAIN} stale, since containers must resolve
     * host.testcontainers.internal to a specific port decided before they were created.
     * <p>
     * Requires {@code @SpringBootTest(webEnvironment = WebEnvironment.DEFINED_PORT)} and
     * {@code @Import(HostRewritingWebClientTestConfig.class)} on the test class -- without
     * the latter, node A cannot resolve host.testcontainers.internal for its own outbound
     * calls to {@code verifier.url}.
     */
    @DynamicPropertySource
    public static void nodeAReplicationProperties(DynamicPropertyRegistry registry) {
        registry.add("server.port", () -> NODE_A_PORT);
        registry.add("api.externalDomain", () -> NODE_A_EXTERNAL_DOMAIN);
        registry.add("ngsi-subscription.notificationEndpoint",
                () -> NODE_A_EXTERNAL_DOMAIN + "/api/" + pathVersion + EndpointsConstants.CONTEXT_BROKER_NOTIFICATION);
        registry.add("tx-subscription.notificationEndpoint",
                () -> NODE_A_EXTERNAL_DOMAIN + "/api/" + pathVersion + EndpointsConstants.DLT_ADAPTER_NOTIFICATION);
        registry.add("verifier.url", VERIFIER::url);
        // NOT VERIFIER.trustedAccessNodesListUrl() (the host.testcontainers.internal form):
        // TrustFrameworkConfig.getAccessNodesListContent() calls apiConfig.webClient()
        // directly -- a same-class Java method call, not field/constructor injection -- so
        // it always gets ApiConfig's own literal webClient() bean and never
        // HostRewritingWebClientTestConfig's @Primary override. Unlike verifier.url there is
        // no cross-node string-equality requirement on this property (TrustFrameworkConfig
        // never compares it against anything), so node A can simply be given a URL it can
        // resolve unaided; node B keeps using the container-facing form.
        registry.add("access-node.trustedAccessNodesList",
                () -> "http://localhost:" + VERIFIER.port() + "/trusted_access_nodes_list.yaml");
        registry.add("security.learCredentialMachineInBase64", () -> SAMPLE_LEAR_CREDENTIAL_BASE64);
    }

    public static Network network() {
        return NETWORK;
    }

    public static VerifierStub verifierStub() {
        return VERIFIER;
    }

    /** A DLT address both nodes' trusted-list already accepts -- see the static block above. */
    public static String getTrustedDltSenderAddress() {
        return TRUSTED_DLT_SENDER_ADDRESS;
    }

    public static String getNodeAExternalDomain() {
        return NODE_A_EXTERNAL_DOMAIN;
    }

    public static int getNodeAPort() {
        return NODE_A_PORT;
    }

    /**
     * Node A's own address as seen from the host JVM (i.e. from test code running outside any
     * container). Distinct from {@link #getNodeAExternalDomain()}, which is the
     * {@code host.testcontainers.internal} form only containers can resolve.
     */
    public static String getNodeALocalBaseUrl() {
        return "http://localhost:" + NODE_A_PORT;
    }

    public static String getBaseUriForScorpioA() {
        return "http://" + scorpioContainerA.getHost() + ":" + scorpioContainerA.getMappedPort(9090);
    }

    public static String getBaseUriBlockchainAdapterA() {
        return "http://" + blockchainAdapterContainerA.getHost() + ":" + blockchainAdapterContainerA.getMappedPort(8080);
    }

    public static String getBaseUriForScorpioB() {
        return "http://" + scorpioContainerB.getHost() + ":" + scorpioContainerB.getMappedPort(9090);
    }

    public static String getBaseUriDesmosB() {
        return "http://" + desmosContainerB.getHost() + ":" + desmosContainerB.getMappedPort(8080);
    }

    public static String getJdbcUrlForPostgresB() {
        return postgresContainerB.getJdbcUrl();
    }

    public static String getPostgresBUsername() {
        return postgresContainerB.getUsername();
    }

    public static String getPostgresBPassword() {
        return postgresContainerB.getPassword();
    }

    public static String getSecurityPrivateKey() {
        return "0x1aff50dca1ac463a5af99a858c2eef7517b8e46d3bf84723ff6dcfead7dc8db6";
    }

}
