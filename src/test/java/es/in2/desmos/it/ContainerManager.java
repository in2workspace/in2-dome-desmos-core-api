package es.in2.desmos.it;

import es.in2.desmos.domain.utils.EndpointsConstants;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@SuppressWarnings("resource")
@Testcontainers
public class ContainerManager {

    private static final ContainerManager INSTANCE = new ContainerManager();
    private static final Network testNetworkA = Network.newNetwork();
    private static final GenericContainer<?> scorpioContainerA;
    private static final GenericContainer<?> postgisContainerA;
    private static final GenericContainer<?> blockchainAdapterContainerA;
    private static final PostgreSQLContainer<?> postgresContainerA;

    private static final Network testNetworkB = Network.newNetwork();
    private static final GenericContainer<?> desmosContainerB;
    private static final GenericContainer<?> scorpioContainerB;
    private static final GenericContainer<?> postgisContainerB;
    private static final GenericContainer<?> blockchainAdapterContainerB;
    private static final PostgreSQLContainer<?> postgresContainerB;

    private static String dltAdapterNotificationEndpoint;
    private static String brokerNotificationEndpoint;

    static {
        // Node A
        postgresContainerA = new PostgreSQLContainer<>("postgres:latest")
                .withDatabaseName("it_db")
                .withUsername("postgres")
                .withPassword("postgres")
                .withNetwork(testNetworkA)
                .withNetworkAliases("postgres-node-a");
        postgresContainerA.start();

        postgisContainerA = new GenericContainer<>(DockerImageName.parse("postgis/postgis"))
                .withExposedPorts(5432)
                .withEnv("POSTGRES_USER", "ngb")
                .withEnv("POSTGRES_PASSWORD", "ngb")
                .withEnv("POSTGRES_DB", "ngb")
                .withNetwork(testNetworkA)
                .withNetworkAliases("postgis-node-a");
        postgisContainerA.start();

        scorpioContainerA = new GenericContainer<>(DockerImageName.parse("scorpiobroker/all-in-one-runner:java-latest"))
                .withExposedPorts(9090)
                .withEnv("DBHOST", "postgis-node-a")
                .dependsOn(postgisContainerA)
                .withNetwork(testNetworkA)
                .withNetworkAliases("scorpio-node-a");
        scorpioContainerA.start();

        blockchainAdapterContainerA = new GenericContainer<>(DockerImageName.parse("quay.io/digitelts/dlt-adapter:1.5.1"))
                .withExposedPorts(8080)
                .withEnv("PRIVATE_KEY", "0x304d170fb355df65cc17ef7934404fe9baee73a1244380076436dec6fafb1e1f")
                .withEnv("DOME_EVENTS_CONTRACT_ADDRESS", "")
                .withEnv("RPC_ADDRESS", "http://blockchain-testnode.infra.svc.cluster.local:8545/")
                .withEnv("DOME_PRODUCTION_BLOCK_NUMBER", "0")
                .withEnv("ISS", "0x9eb763b0a6b7e617d56b85f1df943f176018c8eedb2dd9dd37c0bd77496833fe")
                .withNetwork(testNetworkA)
                .withNetworkAliases("dlt-adapter-node-a")
                .waitingFor(Wait.forHttp(EndpointsConstants.HEALTH).forStatusCode(200));
        blockchainAdapterContainerA.start();
    }

    static {
        // Node B
        postgresContainerB = new PostgreSQLContainer<>("postgres:latest")
                .withDatabaseName("it_db")
                .withUsername("postgres")
                .withPassword("postgres")
                .withNetwork(testNetworkB)
                .withNetworkAliases("postgres-node-b")
                .withInitScript("db/populate/Create_Postgre_B_AuditRecords.sql");
        postgresContainerB.start();

        postgisContainerB = new GenericContainer<>(DockerImageName.parse("postgis/postgis"))
                .withExposedPorts(5432)
                .withEnv("POSTGRES_USER", "ngb")
                .withEnv("POSTGRES_PASSWORD", "ngb")
                .withEnv("POSTGRES_DB", "ngb")
                .withNetwork(testNetworkB)
                .withNetworkAliases("postgis-node-b");
        postgisContainerB.start();

        scorpioContainerB = new GenericContainer<>(DockerImageName.parse("scorpiobroker/all-in-one-runner:java-latest"))
                .withExposedPorts(9090)
                .withEnv("DBHOST", "postgis-node-b")
                .dependsOn(postgisContainerB)
                .withNetwork(testNetworkB)
                .withNetworkAliases("scorpio-node-b");
        scorpioContainerB.start();

        blockchainAdapterContainerB = new GenericContainer<>(DockerImageName.parse("quay.io/digitelts/dlt-adapter:1.5.1"))
                .withExposedPorts(8080)
                .withEnv("PRIVATE_KEY", "0x304d170fb355df65cc17ef7934404fe9baee73a1244380076436dec6fafb1e1f")
                .withEnv("DOME_EVENTS_CONTRACT_ADDRESS", "")
                .withEnv("RPC_ADDRESS", "http://blockchain-testnode.infra.svc.cluster.local:8545/")
                .withEnv("DOME_PRODUCTION_BLOCK_NUMBER", "0")
                .withEnv("ISS", "0x9eb763b0a6b7e617d56b85f1df943f176018c8eedb2dd9dd37c0bd77496833fe")
                .withNetwork(testNetworkB)
                .withNetworkAliases("dlt-adapter-node-b")
                .waitingFor(Wait.forHttp(EndpointsConstants.HEALTH).forStatusCode(200));
        blockchainAdapterContainerB.start();

        desmosContainerB = new GenericContainer<>(DockerImageName.parse("in2workspace/in2-desmos-api:v1.0.0-snapshot"))
                .withExposedPorts(8080)
                .withEnv("SPRING_PROFILES_ACTIVE", "test")
                .withEnv("LOGGING_LEVEL_ES_IN2_DESMOS", "DEBUG")
                .withEnv("SPRING_R2DBC_URL", "r2dbc:postgresql://postgres-node-b:5432/it_db")
                .withEnv("SPRING_R2DBC_USERNAME", "postgres")
                .withEnv("SPRING_R2DBC_PASSWORD", "postgres")
                .withEnv("SPRING_FLYWAY_URL", "jdbc:postgresql://postgres-node-b:5432/it_db")
                .withEnv("OPERATOR_ORGANIZATION_IDENTIFIER", "VATES-S9999999E")
                .withEnv("DLT_ADAPTER_PROVIDER", "digitelts")
                .withEnv("DLT_ADAPTER_INTERNAL_DOMAIN", "http://dlt-adapter-node-b:8080")
                .withEnv("DLT_ADAPTER_EXTERNAL_DOMAIN", "http://dlt-adapter-node-b:8080")
                .withEnv("TX_SUBSCRIPTION_NOTIFICATION_ENDPOINT", "http://desmos-node-b:8080" + dltAdapterNotificationEndpoint)
                .withEnv("TX_SUBSCRIPTION_ENTITY_TYPES", "ProductOffering,Category,Catalogue")
                .withEnv("BROKER_PROVIDER", "scorpio")
                .withEnv("BROKER_INTERNAL_DOMAIN", "http://scorpio-node-b:9090")
                .withEnv("BROKER_EXTERNAL_DOMAIN", "http://scorpio-node-b:9090")
                .withEnv("NGSI_SUBSCRIPTION_NOTIFICATION_ENDPOINT", "http://desmos-node-b:8080" + brokerNotificationEndpoint)
                .withEnv("NGSI_SUBSCRIPTION_ENTITY_TYPES", "ProductOffering,Category,Catalogue")
                .dependsOn(blockchainAdapterContainerB)
                .dependsOn(scorpioContainerB)
                .dependsOn(postgresContainerB)
                .withNetwork(testNetworkB)
                .withNetworkAliases("desmos-node-b");
        desmosContainerB.start();
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

    public static String getSecurityPrivateKey() {
        return "0x1aff50dca1ac463a5af99a858c2eef7517b8e46d3bf84723ff6dcfead7dc8db6";
    }

}