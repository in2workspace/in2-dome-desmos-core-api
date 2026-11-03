package es.in2.desmos.support;

import es.in2.desmos.domain.models.AuditRecord;
import es.in2.desmos.domain.models.AuditRecordStatus;
import es.in2.desmos.domain.models.AuditRecordTrader;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Shape assertions repeated across the producer (node A) and consumer (node B) audit trails. */
public final class ReplicationAssertions {

    private ReplicationAssertions() {
    }

    public static boolean hasReachedPublished(List<AuditRecord> trail) {
        return trail.size() >= 3;
    }

    /**
     * True once the publication has been attempted: the trail has its CREATED row. In the integration environment
     * the DLT adapter has no blockchain behind it and rejects every publication, so a trail stops at CREATED.
     */
    public static boolean hasReachedCreated(List<AuditRecord> trail) {
        return trail.stream().anyMatch(auditRecord -> auditRecord.getStatus() == AuditRecordStatus.CREATED);
    }

    /** True only once the trail's last row is a CREATED/PRODUCER record -- never on an empty or in-progress trail. */
    public static boolean lastIsCreatedProducer(List<AuditRecord> trail) {
        if (trail.isEmpty()) {
            return false;
        }
        AuditRecord last = trail.get(trail.size() - 1);
        return last.getStatus() == AuditRecordStatus.CREATED && last.getTrader() == AuditRecordTrader.PRODUCER;
    }

    /**
     * Producer trail when the DLT adapter rejects the publication: RECEIVED and CREATED, and no PUBLISHED, since a
     * failed publication must not be recorded as published.
     */
    public static void assertProducerTrailWithRejectedPublication(List<AuditRecord> trail) {
        assertThat(trail)
                .as("producer audit trail")
                .extracting(AuditRecord::getStatus)
                .containsExactly(AuditRecordStatus.RECEIVED, AuditRecordStatus.CREATED);
        assertThat(trail)
                .allSatisfy(auditRecord -> assertThat(auditRecord.getTrader()).isEqualTo(AuditRecordTrader.PRODUCER));
    }

    /** True only once the trail's last row is a PUBLISHED/PRODUCER record -- never on an empty or in-progress trail. */
    public static boolean lastIsPublishedProducer(List<AuditRecord> trail) {
        if (trail.isEmpty()) {
            return false;
        }
        AuditRecord last = trail.get(trail.size() - 1);
        return last.getStatus() == AuditRecordStatus.PUBLISHED && last.getTrader() == AuditRecordTrader.PRODUCER;
    }

    /** Node A's own trail for a locally-owned entity: RECEIVED, CREATED, PUBLISHED, all as PRODUCER. */
    public static void assertProducerTrail(List<AuditRecord> trail) {
        assertThat(trail)
                .as("producer audit trail")
                .extracting(AuditRecord::getStatus)
                .containsExactly(AuditRecordStatus.RECEIVED, AuditRecordStatus.CREATED, AuditRecordStatus.PUBLISHED);
        assertThat(trail)
                .allSatisfy(auditRecord -> assertThat(auditRecord.getTrader()).isEqualTo(AuditRecordTrader.PRODUCER));

        AuditRecord published = trail.get(trail.size() - 1);
        assertThat(published.getDataLocation())
                .as("PUBLISHED record should carry a dataLocation pointing back at this node")
                .isNotBlank()
                .contains("?hl=");
    }

    /** Node B's trail for an entity delivered via DLT notification: RECEIVED, RETRIEVED, PUBLISHED, all as CONSUMER. */
    public static void assertConsumerTrail(List<NodeBProbe.AuditRow> rows) {
        assertThat(rows).allSatisfy(row ->
                assertThat(row.trader())
                        .as("every row for a DLT-delivered entity should be trader CONSUMER")
                        .isEqualToIgnoringCase("CONSUMER"));
        assertThat(rows)
                .extracting(NodeBProbe.AuditRow::status)
                .as("node B's consumer audit trail")
                .containsSubsequence("RECEIVED", "RETRIEVED", "PUBLISHED");
    }
}
