package es.in2.desmos.support;

import es.in2.desmos.domain.models.BlockchainNotification;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Everything a replication test needs to poke at node B (the containerized instance) from
 * outside: its public HTTP surface and its audit table, which -- unlike node A's -- is only
 * reachable over its mapped Postgres host port, never through a Spring bean.
 */
public final class NodeBProbe {

    private NodeBProbe() {
    }

    public record AuditRow(String status, String trader) {
    }

    /** POSTs a DLT notification to node B's public {@code /api/v2/notifications/dlt} endpoint. */
    public static void postDltNotification(BlockchainNotification notification) {
        WebClient.create()
                .post()
                .uri(ContainerManager.getBaseUriDesmosB() + "/api/v2/notifications/dlt")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(notification)
                .retrieve()
                .toBodilessEntity()
                .block(Duration.ofSeconds(10));
    }

    /**
     * Same as {@link #postDltNotification}, but returns the raw HTTP status code instead of
     * throwing on a non-2xx response -- needed for negative scenarios (e.g. the 401 trust gate)
     * where the failure response itself is the thing under test.
     */
    public static int postDltNotificationForStatus(BlockchainNotification notification) {
        try {
            return WebClient.create()
                    .post()
                    .uri(ContainerManager.getBaseUriDesmosB() + "/api/v2/notifications/dlt")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(notification)
                    .retrieve()
                    .toBodilessEntity()
                    .block(Duration.ofSeconds(10))
                    .getStatusCode()
                    .value();
        } catch (WebClientResponseException e) {
            return e.getStatusCode().value();
        }
    }

    public static String getEntityFromBroker(String entityId) {
        return ScorpioProbe.getEntity(ContainerManager.getBaseUriForScorpioB(), entityId);
    }

    public static String getEntityFromBrokerOrNull(String entityId) {
        return ScorpioProbe.getEntityOrNull(ContainerManager.getBaseUriForScorpioB(), entityId);
    }

    /** Node B's audit trail for an entity, ordered oldest-first, read straight from its Postgres. */
    public static List<AuditRow> auditRowsFor(String entityId) {
        List<AuditRow> rows = new ArrayList<>();
        try (Connection connection = DriverManager.getConnection(
                ContainerManager.getJdbcUrlForPostgresB(),
                ContainerManager.getPostgresBUsername(),
                ContainerManager.getPostgresBPassword());
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT status, trader FROM desmos.audit_records WHERE entity_id = ? ORDER BY created_at")) {
            statement.setString(1, entityId);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    rows.add(new AuditRow(resultSet.getString("status"), resultSet.getString("trader")));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read node B's audit trail for " + entityId, e);
        }
        return rows;
    }
}
