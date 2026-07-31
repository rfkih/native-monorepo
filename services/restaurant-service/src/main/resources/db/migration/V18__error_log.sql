-- restaurant-service V18 — error_log ops table (ADR 0005 pilot -> ADR 0009 fleet rollout:
-- error-inbox + webhook alerting), activated for the Phase 5 (ADR 0028) offline-replay stock-
-- discrepancy record (StockDeductionWriter#deductForLinesAllowingNegative writes here when a
-- replayed offline sale oversells tracked stock).
--
-- DELIBERATE DEVIATIONS FROM STANDARD NATIVE TABLE CONVENTIONS — READ BEFORE EDITING:
--   1. NO ROW LEVEL SECURITY. error_log is cross-tenant OPERATOR data, not tenant business data.
--      Ops must see every tenant's errors (and tenant-less errors, e.g. Kafka deserialisation
--      failures). company_id is nullable CONTEXT for diagnostics — NOT an access key, NOT an RLS
--      predicate. The mitigation in place of RLS is PII REDACTION AT WRITE TIME (ADR 0005, HR-6):
--      redacted_message is sanitised before storage (NIK / bank / email / long-digit patterns
--      masked). See ADR 0005 for the full rationale.
--   2. NO AUDITABLE COLUMNS (created_at/by, updated_at/by, version). error_log is infrastructure /
--      ops (same class as outbox / processed_event): written by a REQUIRES_NEW JdbcTemplate upsert
--      that survives a rolled-back business transaction, never a JPA @Entity. Its first_seen /
--      last_seen columns serve the diagnostics role.
-- Both deviations are intentional, recorded in ADR 0005/0009, and MUST NOT be "corrected" by a
-- future migration without revisiting those ADRs.
CREATE TABLE error_log (
    id                  UUID          NOT NULL PRIMARY KEY,
    fingerprint         VARCHAR(64)   NOT NULL,
    exception_class     VARCHAR(255)  NOT NULL,
    redacted_message    TEXT          NOT NULL,
    source              VARCHAR(255)  NOT NULL,
    company_id          VARCHAR(64)   NULL,
    trace_id            VARCHAR(64)   NULL,
    occurrence_count    BIGINT        NOT NULL DEFAULT 1,
    first_seen          TIMESTAMPTZ   NOT NULL,
    last_seen           TIMESTAMPTZ   NOT NULL,
    CONSTRAINT uq_error_log_fingerprint UNIQUE (fingerprint)
);
CREATE INDEX idx_error_log_last_seen ON error_log (last_seen DESC);
