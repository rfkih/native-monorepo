-- finance-service V14 — error_log ops table (ADR 0005: error-inbox + webhook alerting).
--
-- DELIBERATE DEVIATIONS FROM STANDARD NATIVE TABLE CONVENTIONS — READ BEFORE EDITING:
--
--   1. NO ROW LEVEL SECURITY.  error_log is cross-tenant OPERATOR data, not tenant business data.
--      Ops must see every tenant's errors (and tenant-less errors, e.g. Kafka deserialisation
--      failures).  company_id is nullable CONTEXT captured for diagnostics — it is NOT an access
--      key and MUST NOT be used as an RLS predicate.  The mitigation that keeps this safe in
--      place of RLS is PII REDACTION AT WRITE TIME (ADR 0005 §Decision, CLAUDE.md HR-6):
--      redacted_message is sanitised before storage (NIK / bank / email / long-digit patterns
--      masked), so no tenant-sensitive data lands in the ops store.  Reviewed by security-engineer.
--      See ADR 0005 for the full rationale.
--
--   2. NO AUDITABLE COLUMNS (created_at/created_by/updated_at/updated_by/version).
--      error_log is infrastructure / ops, in the same class as outbox and processed_event (see V10):
--      it is written by a REQUIRES_NEW JdbcTemplate upsert that survives a rolled-back business
--      transaction and is never a JPA @Entity.  It has its own temporal columns (first_seen /
--      last_seen) that serve the same diagnostics role.
--
-- Both deviations are intentional, recorded in ADR 0005, and MUST NOT be "corrected" by a
-- future migration without first revisiting that ADR.

-- ---------------------------------------------------------------------------
-- error_log (ops — NOT Auditable, NOT RLS — see header)
-- ---------------------------------------------------------------------------
-- One row per unique error fingerprint across the lifetime of the service.  Written via an
-- INSERT … ON CONFLICT (fingerprint) DO UPDATE upsert that increments occurrence_count and
-- bumps last_seen on each repeat, keeping the table compact regardless of error volume.
CREATE TABLE error_log (
    id                  UUID          NOT NULL PRIMARY KEY,

    -- SHA-256 hex of (exception_class + normalised_message + source): the deduplication key.
    -- The UNIQUE constraint makes ON CONFLICT (fingerprint) unambiguous for the upsert.
    fingerprint         VARCHAR(64)   NOT NULL,

    exception_class     VARCHAR(255)  NOT NULL,

    -- PII-redacted (NIK / bank / email / long-digit patterns masked at write time before storage).
    redacted_message    TEXT          NOT NULL,

    -- The origin of the error, e.g. the DLT topic name for a Kafka DLT recoverer capture.
    source              VARCHAR(255)  NOT NULL,

    -- Nullable diagnostic context only — NOT an RLS predicate, NOT an access key (see header).
    -- NULL when the error precedes tenant resolution (e.g. Kafka deserialisation failure).
    company_id          VARCHAR(64)   NULL,

    -- W3C trace-id carried forward from the failed request / message, when available.
    trace_id            VARCHAR(64)   NULL,

    -- How many times this fingerprint has been seen since first_seen.
    occurrence_count    BIGINT        NOT NULL DEFAULT 1,

    -- Timestamp of the first occurrence (set on INSERT, never updated).
    first_seen          TIMESTAMPTZ   NOT NULL,

    -- Timestamp of the most recent occurrence (bumped on every upsert repeat).
    last_seen           TIMESTAMPTZ   NOT NULL,

    CONSTRAINT uq_error_log_fingerprint UNIQUE (fingerprint)
);

-- The primary read path: "show me recent errors" — the dashboard / alert query orders by
-- last_seen DESC, so index that column descending.
CREATE INDEX idx_error_log_last_seen ON error_log (last_seen DESC);
