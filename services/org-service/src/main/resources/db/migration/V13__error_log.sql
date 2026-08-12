-- org-service V13 — error_log ops table (ADR 0005 pilot → ADR 0009 fleet rollout:
-- error-inbox + webhook alerting).
--
-- WHY org-service gets this table LATE (ADR 0040 gap closure, 2026-08-12): org-service is a PURE
-- PRODUCER (it emits CompanyCreated via the outbox + Debezium CDC and runs NO Kafka consumer / DLT),
-- so the fleet error-inbox rollout — which was wired into the DLT recoverer path — skipped it. But
-- the shared ApiExceptionHandler (libs/security, ADR 0040) persists every unexpected HTTP 500 to
-- error_log so a user-quoted `reference` resolves to a row. Without this table org-service 500s
-- returned a reference that lived only in the server log, not error_log (the accepted gap in ADR
-- 0040). This migration + the error-inbox auto-config split (ErrorInboxWriter now activates on a
-- JDBC-only classpath, no kafka-clients required) closes it. org-service's error_log is therefore
-- fed ONLY by the HTTP-500 path — there is no ConsumeErrorRecorder here.
--
-- DELIBERATE DEVIATIONS FROM STANDARD NATIVE TABLE CONVENTIONS — READ BEFORE EDITING:
--   1. NO ROW LEVEL SECURITY. error_log is cross-tenant OPERATOR data, not tenant business data.
--      Ops must see every tenant's errors (and tenant-less errors, e.g. a 500 raised before the
--      tenant is bound). company_id is nullable CONTEXT for diagnostics — NOT an access key, NOT an
--      RLS predicate. The mitigation in place of RLS is PII REDACTION AT WRITE TIME (ADR 0005,
--      HR-6): redacted_message is sanitised before storage (NIK / bank / email / long-digit
--      patterns masked). See ADR 0005 for the full rationale.
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
