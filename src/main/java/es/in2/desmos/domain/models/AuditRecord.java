package es.in2.desmos.domain.models;

import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Transient;
import org.springframework.data.domain.Persistable;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.sql.Timestamp;
import java.util.UUID;

@Getter
@Setter
@ToString
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table(name = "audit_records", schema = "desmos")
public class AuditRecord implements Persistable<UUID> {

    @Id
    @Column("id")
    private UUID id;

    @Column("process_id")
    private String processId;

    @Column("created_at")
    private Timestamp createdAt;

    @Column("entity_id")
    private String entityId;

    @Column("entity_type")
    private String entityType;

    @Column("entity_hash")
    private String entityHash;

    @Column("entity_hashlink")
    private String entityHashLink;

    @Column("data_location")
    private String dataLocation;

    @Column("status")
    private AuditRecordStatus status;

    @Column("trader")
    private AuditRecordTrader trader;

    @Column("hash")
    private String hash;

    @Column("hashlink")
    private String hashLink;

    @Transient
    private boolean newTransaction;

    @Override
    public UUID getId() {
        return this.id;
    }

    @Override
    public boolean isNew() {
        return this.newTransaction || id == null;
    }

}