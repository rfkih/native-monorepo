# 0005. Error-inbox + webhook alerting for production-error visibility (pilot: finance)

- **Status:** Accepted (pilot) — extracted to `libs/error-inbox` + extended fleet-wide by [ADR 0009](0009-error-inbox-fleet-rollout.md)
- **Date:** 2026-06-20
- **Deciders:** rifki + Claude (pairing)
- **Related:** [ENGINEERING-STANDARDS §0 row 11](../ENGINEERING-STANDARDS.md) (the ≥-blackheart
  scorecard gap), CLAUDE.md HR-3 (outbox), HR-5 (RLS), HR-6 (PII), [ADR 0004](0004-openapi-docs-springdoc.md)
  (the pilot-then-rollout pattern this mirrors)

## Context
Gap #11 on the ≥-blackheart scorecard: blackheart has a **DB-backed error-inbox** (fingerprint
dedup + occurrence counts) with **alerting** (Telegram on first occurrence + milestones) and Grafana
dashboards. Native has JSON logs + RED metrics + outbox-lag, but **no durable error store and no
alert channel** — a silent failure goes unnoticed (this session a stuck `bootRun` ran ~18h unseen).

Constraints: **DB-per-service** (HR-1 → a per-service inbox, not a shared one); **no sync calls
between business services** (HR-2 → alerting can't be a sync call to another Native service, but an
outbound POST to an external ops URL is an integration, not a business-service call, and is
allowed — same class as the gateway→Keycloak JWKS fetch); **outbox-only publishing** (HR-3) does NOT
fit the error path, because an error usually means the business transaction rolled back, taking any
same-transaction outbox row with it; **RLS** (HR-5) and **PII** (HR-6) must be respected.

## Decision
Introduce a per-service **`error_log` ops table** and capture production errors to it, deduplicated,
then **alert via a configurable generic webhook**. Piloted in **finance-service** only (mirroring
ADR 0004); fleet rollout is a later ADR.

- **Capture point (pilot):** the Kafka **DLT recoverer** (`KafkaConfig`). A record routed to
  `<topic>.DLT` is a dropped money event — the exact "silent failure" class to surface. The recoverer
  records the error before publishing to the DLT.
- **Storage:** `error_log` keyed by a **`fingerprint`** (SHA-256 of exception class + normalized
  message + source); written via an **`INSERT … ON CONFLICT (fingerprint) DO UPDATE`** upsert that
  increments `occurrence_count` and bumps `last_seen`, in a **`REQUIRES_NEW`** transaction so it
  survives the rolled-back business transaction. Plain `JdbcTemplate`, **not a JPA `@Entity`** — it
  is infra/ops, so it is deliberately exempt from the `Auditable` and native-query/projection rules
  (it has its own `first_seen`/`last_seen`; mirrors how `outbox`/`processed_event` are treated).
- **Not RLS-scoped — deliberate exception to HR-5.** `error_log` is cross-tenant **operator** data,
  not tenant business data: ops must see every tenant's (and tenant-less, e.g. deserialize) errors.
  `company_id` is **nullable context, never an access key**. The mitigation that keeps this safe is
  **PII redaction (HR-6): the message is redacted before storage** (NIK/bank/email/long-digit
  patterns masked), so no tenant-sensitive data lands in the ops table. Reviewed by security-engineer.
- **Alerting:** on `occurrence_count` transitions to **1** and milestones **10/100/1000**, an
  **`@Async`** POST sends the alert JSON to `NATIVE_ALERT_WEBHOOK_URL` with **explicit connect/read
  timeouts** (also advances gap #12). **No-op when the URL is unset** (so dev/CI never call out).
  The alert JSON carries the **PII-redacted** message and the **real dedup fingerprint** (the
  `error_log` row key, for operator correlation) — **never the raw exception text**, so the egress
  path is held to the same HR-6 bar as storage (the writer returns the redacted message; the
  recorder puts only that on the wire).
- **Out of scope (follow-ups):** web-path `5xx` capture (fleet rollout, via an optional `ErrorSink`
  SPI in `libs/security`'s catch-all so other services opt in with one bean); centralizing transport
  ownership in **notification-service** (today a stub); Grafana dashboards (infra-gated, gap #13).

## Consequences
- Ops gains durable visibility + push alerts on the money-critical consume failures finance can hit;
  a DLT'd event is no longer silent.
- **Enforcement:** a Testcontainers test proves the upsert dedups (count increments, one row); a
  redaction test proves no PII reaches `redacted_message` — including **space/hyphen-separated**
  identifiers (formatted bank accounts / phone numbers), not just contiguous digit runs; an alert
  test proves the webhook fires once on first occurrence (and not on a benign repeat below a
  milestone); and a payload test proves the alert carries only the **redacted** message (not the
  raw exception text). The inbox upsert runs under a **bounded transaction timeout** so a slow DB
  cannot stall the consumer partition.
- **Cost / debt:** one non-RLS, non-Auditable table per service — justified above and fenced to ops
  data only; any future column that could carry tenant-identifying data must be redacted or this ADR
  revisited. The pilot is finance-only; the scorecard row #11 stays ⚠ until the fleet rollout +
  dashboards land (a follow-up ADR).
