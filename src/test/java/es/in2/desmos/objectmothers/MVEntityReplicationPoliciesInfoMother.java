package es.in2.desmos.objectmothers;

import com.fasterxml.jackson.core.JsonProcessingException;
import es.in2.desmos.domain.models.MVEntity4DataNegotiation;
import es.in2.desmos.domain.models.MVEntityReplicationPoliciesInfo;
import org.jetbrains.annotations.NotNull;
import org.json.JSONException;

import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.List;

public final class MVEntityReplicationPoliciesInfoMother {

    private MVEntityReplicationPoliciesInfoMother() {
    }

    public static @NotNull MVEntityReplicationPoliciesInfo sample1() throws JSONException, NoSuchAlgorithmException, JsonProcessingException {
        MVEntity4DataNegotiation sample1 = MVEntity4DataNegotiationMother.sample1();
        return new MVEntityReplicationPoliciesInfo(
                sample1.id(),
                sample1.type(),
                sample1.lifecycleStatus(),
                sample1.startDateTime(),
                sample1.endDateTime()
        );
    }

    public static @NotNull MVEntityReplicationPoliciesInfo replicationValidFields() {
        return new MVEntityReplicationPoliciesInfo(
                "entity-1",
                "type",
                "Launched",
                Instant.now().minusSeconds(3600).toString(),
                Instant.now().plusSeconds(3600000).toString()
        );
    }

    public static @NotNull MVEntityReplicationPoliciesInfo replicationNullLifecycleStatus() {
        return new MVEntityReplicationPoliciesInfo(
                "entity-1",
                "type",
                null,
                Instant.now().minusSeconds(3600).toString(),
                Instant.now().plusSeconds(3600000).toString()
        );
    }

    public static @NotNull MVEntityReplicationPoliciesInfo replicationValidFieldsAndNullStartDateTime() {
        return new MVEntityReplicationPoliciesInfo(
                "entity-1",
                "type",
                "Launched",
                null,
                Instant.now().plusSeconds(3600000).toString()
        );
    }

    public static @NotNull MVEntityReplicationPoliciesInfo replicationValidFieldsAndNullEndDateTime() {
        return new MVEntityReplicationPoliciesInfo(
                "entity-1",
                "type",
                "Launched",
                Instant.now().minusSeconds(3600).toString(),
                null
        );
    }

    public static @NotNull MVEntityReplicationPoliciesInfo replicationInvalidLifecycleStatus() {
        return new MVEntityReplicationPoliciesInfo(
                "entity-1",
                "type",
                "InvalidLifecycleStatus",
                Instant.now().minusSeconds(3600).toString(),
                Instant.now().plusSeconds(360000000).toString()
        );
    }

    public static @NotNull MVEntityReplicationPoliciesInfo replicationInvalidFutureStartDateTime() {
        return new MVEntityReplicationPoliciesInfo(
                "entity-1",
                "type",
                "Launched",
                Instant.now().plusSeconds(3600).toString(),
                "endDateTime"
        );
    }

    public static @NotNull MVEntityReplicationPoliciesInfo replicationInvalidPastEndDateTime() {
        return new MVEntityReplicationPoliciesInfo(
                "entity-1",
                "type",
                "Launched",
                Instant.now().minusSeconds(360000).toString(),
                Instant.now().minusSeconds(7200).toString()
        );
    }

    public static @NotNull List<MVEntityReplicationPoliciesInfo> mvReplicableList() {
        return List.of(
                replicationValidFields(),
                replicationValidFieldsAndNullStartDateTime(),
                replicationValidFieldsAndNullEndDateTime()
        );
    }

    public static @NotNull List<MVEntityReplicationPoliciesInfo> mvNotReplicableList() {
        return List.of(
                replicationInvalidLifecycleStatus(),
                replicationInvalidFutureStartDateTime(),
                replicationInvalidPastEndDateTime()
        );
    }
}
