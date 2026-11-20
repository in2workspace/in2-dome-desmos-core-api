-- findMostRecentAuditRecord (ORDER BY created_at DESC LIMIT 1) has no entity filter, so the V2 index cannot serve it
-- and every read of the last audit record scanned the whole table. Plain CREATE INDEX (inside Flyway's transaction):
-- it blocks INSERTs while it builds, which only matters if an older replica keeps writing during the deploy.
CREATE INDEX IF NOT EXISTS idx_audit_records_created_at
ON desmos.audit_records (created_at DESC);
