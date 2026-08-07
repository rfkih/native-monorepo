-- payment-service baseline (ADR 0045 — QRIS payment modes + PSP charge lifecycle).
--
-- The infra tables the service needs on day one, folded into ONE baseline (the loyalty-service
-- lesson: outbox WITH traceparent from the start; error_log folded in rather than a separate V2):
--
--   * outbox    — the transactional-outbox (rule 3). PaymentChargeSucceeded is written here in the
--                 SAME transaction as the charge's SUCCEEDED transition; Debezium ships it.
--   * error_log — the error-inbox ops table (ADR 0005/0009): webhook anomalies (unknown order,
--                 amount mismatch, late settlement after a local cancel) are PARKED here for a
--                 human, never dropped and never auto-captured.
--
-- DELIBERATE OMISSION: no processed_event. This service consumes nothing from Kafka (the signed
-- Midtrans webhook + the charge-status sync are its only inbound signals, and both are idempotent
-- via the charge's status-guarded transition). Add processed_event with the first consumer, if one
-- ever lands.
--
-- The business tables follow: V2 payment_settings, V3 payment_charge — each with the mandatory
-- Auditable columns + a FORCE ROW LEVEL SECURITY tenant policy (rules 4-5). The app connects as a
-- NON-superuser role (no BYPASSRLS) so the policies are genuinely enforced.

-- ---------------------------------------------------------------------------
-- transactional outbox (consumed by Debezium) — traceparent folded in from day one
-- ---------------------------------------------------------------------------
CREATE TABLE outbox (
    id             UUID          NOT NULL PRIMARY KEY,
    aggregate_type VARCHAR(255)  NOT NULL,
    aggregate_id   VARCHAR(255)  NOT NULL,
    event_type     VARCHAR(255)  NOT NULL,
    payload        BYTEA         NOT NULL,
    headers        TEXT          NULL,
    company_id     UUID          NOT NULL,
    occurred_at    TIMESTAMPTZ   NOT NULL,
    published_at   TIMESTAMPTZ   NULL,
    traceparent    VARCHAR(64)   NULL
);

-- The relay polls for UNPUBLISHED rows in occurrence order. A PARTIAL index (WHERE published_at IS
-- NULL) indexes only the rows the relay still has to ship, so it stays tiny.
CREATE INDEX idx_outbox_unpublished ON outbox (occurred_at, id) WHERE published_at IS NULL;

-- ---------------------------------------------------------------------------
-- error_log — the error-inbox ops table (ADR 0005 pilot -> ADR 0009 fleet rollout)
-- ---------------------------------------------------------------------------
-- DELIBERATE DEVIATIONS FROM STANDARD NATIVE TABLE CONVENTIONS — READ BEFORE EDITING:
--   1. NO ROW LEVEL SECURITY. error_log is cross-tenant OPERATOR data, not tenant business data.
--      Ops must see every tenant's errors (and tenant-less errors). company_id is nullable CONTEXT
--      for diagnostics — NOT an access key, NOT an RLS predicate. The mitigation in place of RLS is
--      PII REDACTION AT WRITE TIME (ADR 0005, HR-6): redacted_message is sanitised before storage.
--   2. NO AUDITABLE COLUMNS. error_log is infrastructure/ops (same class as outbox): written by a
--      REQUIRES_NEW JdbcTemplate upsert that survives a rolled-back business transaction, never a
--      JPA @Entity. Its first_seen / last_seen columns serve the diagnostics role.
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
