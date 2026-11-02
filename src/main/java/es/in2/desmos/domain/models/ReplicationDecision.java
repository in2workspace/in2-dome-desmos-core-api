package es.in2.desmos.domain.models;

/**
 * Result of applying the global replication policies to an entity.
 *
 * @param replicable - whether the entity can be replicated
 * @param reason     - why it cannot, e.g. "policy GP_1: lifecycleStatus=Active"; null when replicable
 */
public record ReplicationDecision(boolean replicable, String reason) {

    public static final ReplicationDecision REPLICABLE_DECISION = new ReplicationDecision(true, null);

    public static ReplicationDecision notReplicable(String reason) {
        return new ReplicationDecision(false, reason);
    }
}
