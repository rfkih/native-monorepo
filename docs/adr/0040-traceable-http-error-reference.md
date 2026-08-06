# 40. Traceable HTTP error reference (500s → error_log + a user-quotable reference)

Date: 2026-08-06

## Status

Accepted (extends [ADR 0005](0005-error-inbox.md) / [ADR 0009](0009-error-inbox-fleet-rollout.md))

## Context

The shared `ApiExceptionHandler` (libs/security, inherited by every web service) mapped an unexpected
fault to a generic RFC-7807 500 and logged it server-side with the trace id — but the user saw only
"An unexpected error occurred," with no way to report *which* error, and nothing was persisted for
ops to look up. The `error_log` ops table + `ErrorInboxWriter` (fingerprint-deduped, PII-redacted)
already existed but were wired only into the Kafka-DLT path, not HTTP 500s.

## Decision

The 500 catch-all now returns a **user-quotable reference** and **persists the fault to `error_log`**:

- **Reference** = the in-flight W3C trace id, or a freshly minted dash-free UUID when tracing did
  not populate MDC. Returned as both the `reference` and (back-compat) `traceId` ProblemDetail
  properties and appended to `detail`. The generated value is **not** written back into MDC (that
  would leak onto the next request on a reused Tomcat thread — security review).
- **Persistence** via the existing `ErrorInboxWriter.record(ex, "http:<path>", companyId, reference)`,
  so ops runs `SELECT * FROM error_log WHERE trace_id = '<reference>'`. Company id comes from
  `TenantContext.currentCompanyId()`; the raw throwable is redacted inside the writer (HR-6).
- **Toggle**: `native.error-inbox.persist-http-errors` (default `true`, per-service overridable).
- `error_log`'s `ON CONFLICT` now also updates `trace_id` (last-write-wins, matching the existing
  `redacted_message` semantics) so a freshly-quoted reference is findable.

Only the 500 catch-all changes; the 400 handlers (validation / illegal-argument / malformed-body)
are the caller's own fault and neither persist nor change.

## Consequences

- A user hitting an unexpected error gets a short reference to quote; ops correlates it to the
  `error_log` row and the server log. No PII reaches the client (detail stays generic; the reference
  is a trace id / UUID).
- Persistence is best-effort and defensively wrapped — a DB failure, missing bean, or missing
  `error_log` table can never alter or fail the HTTP response.
- **Coverage gap (accepted):** `org-service` depends on libs/security but has no `error_log` table
  and no kafka-clients, so its `ErrorInboxWriter` bean never activates — its 500s return a reference
  that resolves only in the server log, not `error_log`. It fails safe (no crash, no warn-spam).
  Closing it (an `error_log` migration + decoupling the auto-config's Kafka `@ConditionalOnClass`
  from the JDBC-only writer bean) is a tracked follow-up. The 8 DB+Kafka services — where virtually
  all user-facing 500s occur (POS, finance, payroll) — are fully covered.
- Redaction scope is the established ADR-0005 contract (emails + ≥10-digit runs → NIK/bank/NPWP
  covered; personal names and sub-10-digit figures are not) reused on the now-wider HTTP-500
  surface — re-affirmed as adequate, not re-scoped here.
