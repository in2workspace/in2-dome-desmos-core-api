CREATE INDEX IF NOT EXISTS idx_audit_records_entity_status_created
ON desmos.audit_records (entity_id, status, created_at DESC);