package es.in2.desmos.domain.models;

public record MVEntityReplicationPoliciesInfo(
        String id,
        String type,
        String lifecycleStatus,
        String startDateTime,
        String endDateTime) {
}
