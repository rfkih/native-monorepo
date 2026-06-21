# 0009. Extract the error-inbox into libs/error-inbox and roll it out fleet-wide

- **Status:** Accepted
- **Date:** 2026-06-21
- **Deciders:** rifki + Claude (pairing)
- **Related:** [ADR 0005](0005-error-inbox-and-alerting.md) (the finance pilot this extends),
  [ENGINEERING-STANDARDS §0 scorecard #11](../ENGINEERING-STANDARDS.md) + [§5](../ENGINEERING-STANDARDS.md),
  [CLAUDE.md HR-3/HR-6](../../CLAUDE.md)

## Context
[ADR 0005](0005-error-inbox-and-alerting.md) made a DLT'd money event a recorded, alerted failure
instead of a silent one — but **only in finance-service** (the pilot), explicitly deferring "fleet
rollout + Grafana dashboards" to a follow-up ADR. The competitive scorecard tracked the residual as
dimension **#11 (error observability): partial**. Every other event-CONSUMING service
(carwash, employee, entitlement, notification) still dropped a poison record to its `<topic>.DLT`
with no inbox row and no alert — a silent failure exactly where it matters (e.g. a poison
`AssignmentChanged` on employee, or `ConsolidationClosed` on notification). The pilot's machinery was
already service-agnostic apart from one hardcoded service name, so it was ready to extract.

## Decision
Extract the five service-agnostic pieces of the pilot — `ErrorMessageRedactor`, `ErrorInboxWriter`,
`AlertPayload`, `AlertWebhookClient`, `ConsumeErrorRecorder` — into a **new shared library
`libs/error-inbox`** with an `ErrorInboxAutoConfiguration` that registers them as beans (the
`REQUIRES_NEW` `errorInboxTransactionTemplate` + a `@ConditionalOnMissingBean` `Clock`). The service
label in the alert now comes from `spring.application.name` (not a hardcoded constant). Then adopt it
on **every event-consuming service**: finance is migrated onto the lib (its in-service copies
deleted), and **carwash, employee, entitlement, notification** each gain it.

A consuming service activates the inbox with three additive steps:
1. depend on `libs:error-inbox`;
2. add an `error_log` **Flyway migration** (per-service database);
3. wrap its existing DLT `DeadLetterPublishingRecoverer` in a `ConsumerRecordRecoverer` that calls
   `ConsumeErrorRecorder.record(rec, ex)` **before** the DLT publish.

**A new library, not `libs/observability`** — the stateless **gateway** depends on
`libs/observability` (for shared JSON logging) and must stay DB-free; putting JDBC/Kafka/RestClient
machinery there would leak a persistence stack into the gateway. `libs/error-inbox` is consumed only
by the services that consume events. The auto-config is `@ConditionalOnClass(JdbcTemplate,
ConsumerRecord)`, so a DB-only / non-Kafka module would never activate it.

**The `error_log` table keeps the ADR 0005 deviations unchanged** — NOT `Auditable`, NOT RLS-scoped
(cross-tenant OPERATOR data; `company_id` is nullable diagnostic context, never an access key), with
**PII redaction at write time** as the mitigation in place of RLS (HR-6). Each service's migration
header restates this. The deviations MUST NOT be "corrected" without revisiting ADR 0005/0009.

**Deliberate exclusions:** the pure-producer services **org** and **restaurant** (they emit events
via the outbox but consume none, so they have no DLT path to guard) and the reactive **gateway** (no
consumers, no DB).

## Consequences
- A DLT'd money/business event is now recorded (fingerprint-deduped `error_log` upsert) and
  milestone-alerted on **every** consuming service, not just finance — fingerprint dedup, occurrence
  counting, and PII-redacted alert egress identical across the fleet from one definition.
- **Scorecard #11 → ✅** for the DB-inbox + alerting half (Native ≥ blackheart: fleet-wide, with
  fingerprint dedup + PII redaction + milestone alerts). The **RED metrics / outbox-lag / Grafana
  dashboards** half of §5 remains a follow-up tied to **#13 (deployed & proven)** — it needs a real
  metrics/Grafana stack, not code.
- **One source of truth.** A fix or hardening to the redactor / dedup / alert path lands once in the
  lib and applies everywhere; the finance pilot's bespoke copies are gone.
- **Each service still owns its `error_log` migration and DLT wiring** — the lib deliberately does
  NOT ship the table (databases are per-service) nor the error handler (the non-retryable exception
  set is service-specific). The beans are `@ConditionalOnMissingBean`, so a service can override any
  piece.
- **Known follow-up (carried from the pilot).** The `REQUIRES_NEW` upsert's 5s tx timeout bounds
  statement execution but not connection *acquisition*: under a poison-event storm that also exhausts
  the Hikari pool, the recorder could wait up to the pool's `connectionTimeout` (default 30s) before
  the DLT publish, partly eroding the "never block the partition" goal. A dedicated small pool (or an
  explicit short `connectionTimeout`) for the inbox is the fix — deferred, not introduced here (the
  behaviour is unchanged from the finance pilot).
- **Verified:** the lib's pure-unit tests (redaction, milestone predicate, fail-safe swallow +
  PII-safe egress) + finance's `ErrorInboxWriterTest` (the lib bean against finance's real
  `error_log`) + the full Testcontainers suites of carwash / employee / entitlement / notification
  (each boots with its new migration + the wired recorder) all green.
