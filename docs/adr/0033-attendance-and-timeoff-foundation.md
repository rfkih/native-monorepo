# 33. Attendance & time-off foundation — leave requests, overtime entries, and a derived balance

Date: 2026-08-02

## Status

Accepted

## Context

Track P (payroll) Phase P7 needs work inputs to feed a run: unpaid-leave days prorate pay, overtime
minutes add pay, and annual-leave usage needs a balance to check against. None of that exists yet —
employee-service has no attendance/leave/overtime concept at all. This phase (P6) builds the
FOUNDATION only: recording, approving, and reading leave requests and overtime entries, plus the
per-tenant work calendar the divisor math will need. **It intentionally does NOT touch payroll** —
no run consumes these rows yet (that is P7, gated on this phase landing first, per the program's
recommended merge order). The feature is standalone-usable today: a manager can already track and
approve leave/overtime and correct balances before a single payslip line reads any of it.

## Decision

1. **Four tables, one shared audit log** (`employee.timeoff` package, migration V12):
   `leave_request` (`ANNUAL|UNPAID|SICK`, whole-day count, `SUBMITTED → APPROVED|REJECTED`, cancel
   from `SUBMITTED`), `overtime_entry` (`WEEKDAY|REST_DAY`, minutes capped at 600, same lifecycle),
   `leave_balance` (per employee × year: `granted_days` default 12, `adjustment_days` default 0),
   `work_calendar` (one row per tenant: `days_per_week` 5|6, `monthly_divisor` 21|25 — the PP
   36/2021 daily-wage convention P7 will divide an unpaid-leave day's pay by). `timeoff_request_event`
   is ONE shared append-only audit table for BOTH `leave_request` and `overtime_entry` transitions,
   discriminated by a `request_kind` column — chosen over two separate tables because the guarded
   transition shape (SUBMIT/APPROVE/REJECT/CANCEL, identical actor/comment/idempotency-key columns)
   is byte-identical between the two aggregates; splitting it would duplicate the repository/service
   boilerplate for no audit-fidelity gain. `request_kind` is included in the `UNIQUE (company_id,
   request_kind, request_id, idempotency_key)` index purely for self-documentation/defense-in-depth
   — `request_id` values are drawn from separate UUID spaces and cannot practically collide across
   kinds, but the index reads unambiguously either way.
2. **No DRAFT stage.** Unlike `expense_claim` (which has a DRAFT the employee edits before
   submitting), a leave/overtime request is created DIRECTLY as `SUBMITTED` — v1 has no
   draft-editing UX for this resource. That makes CREATE itself the first guarded, audited,
   idempotency-key-required transition, which the `expense_claim_event`-replay idiom cannot cover
   on its own (there is no pre-existing `request_id` to key a replay probe by before the row
   exists). So `leave_request`/`overtime_entry` EACH also carry their own `idempotency_key` column
   with `UNIQUE (company_id, idempotency_key)` — the `fixed_asset`/`AssetController.acquire`
   create-with-money idiom (V34), applied here to a create-with-no-prior-id resource instead of a
   money-posting one. A retried `POST` replays the original row; the shared
   `timeoff_request_event` table still records the SUBMIT transition (for a uniform audit trail
   across all four actions) and is the replay mechanism for the LATER transitions
   (approve/reject/cancel), which DO act on a known, already-created id.
3. **Overlap guard + derived balance, both under a per-employee advisory lock (ADR 0033 §4).** A
   new leave request overlapping an existing `SUBMITTED`/`APPROVED` request for the SAME employee
   is rejected `409` (`LeaveOverlapException`) at creation time. `leave_balance` NEVER stores "days
   used" — approving an `ANNUAL` request decrements nothing physically; usage is DERIVED at read
   time as `Σ days` of `APPROVED` `ANNUAL` requests for the employee/year (a query, never a mutable
   counter, so it can never drift from the immutable approved rows it sums). Approving an `ANNUAL`
   request whose `days` would push `used + days` past `granted + adjustment` is rejected `409`
   (`InsufficientLeaveBalanceException`). Both checks are check-then-act races if left
   unserialized (two concurrent creates could both see "no overlap"; two concurrent approvals could
   both see "enough balance"), so `LeaveRequestWriter#create`/`#approve` both take a PER-EMPLOYEE
   `pg_advisory_xact_lock` (the `ExpenseClaimWriter` currency-establishment idiom, keyed by employee
   instead of tenant — two different employees' requests never contend with each other, so a
   tenant-wide lock would over-serialize for no correctness benefit). `UNPAID`/`SICK` requests skip
   the balance check entirely (ADR 0033 §2 — sick is paid-full v1, unpaid needs no balance).
4. **The work calendar is seeded lazily, not by Flyway.** RLS forbids an unscoped `INSERT` against
   a FORCE-RLS table with no session GUC bound, so V12 ships no default row (the
   `expense_category` default-set precedent, ADR 0030). `GET /api/v1/work-calendar` seeds the
   `(5, 21)` default on first read via `WorkCalendarWriter#seedDefaultIfMissing`; `PUT` upserts
   (create-or-replace) the tenant's single row. A concurrent double-seed race is caught by
   `uq_work_calendar_company` and recovered by re-reading the winner's row, never surfacing a raw
   `500`.
5. **No events.** This phase is entirely local to employee-service; nothing here is consumed by
   another service, so no outbox row is written and nothing is added to the event catalog. Track P
   Phase P7 is what turns an approved unpaid-leave/overtime row into a payslip line — that phase
   may introduce new *statutory rule* data (already shipped, `OVERTIME_HOURLY` in `ID-2026.1`), not
   a new cross-service event.
6. **Endpoints.** Self-service (every business role, `ME_ROLES`, resolved strictly from the bound
   `X-Actor` — the `/me` idiom, no id-widening parameter anywhere): `POST/GET
   /api/v1/me/leave-requests`, `POST /{id}/cancel`, `GET /api/v1/me/leave-balance?year=`,
   `POST/GET /api/v1/me/overtime-entries`, `POST /{id}/cancel`. Manager (`DASHBOARD_ROLES`):
   `GET/POST-approve/POST-reject /api/v1/leave-requests`, the same shape for
   `/api/v1/overtime-entries`, `GET+PUT /api/v1/work-calendar`, `GET /api/v1/leave-balances?year=`
   (per-employee derived usage, paginated envelope) + `PATCH /api/v1/leave-balances/{employeeId}`
   (the manager grant/correction). Four new gateway routes (`DASHBOARD_ROLES`); the `/me/**` paths
   ride the existing `meRoute`.

## Consequences

- Managers can track and approve time off today, independent of payroll — the feature earns its
  keep before P7 ever reads it.
- The derived-balance decision means `leave_balance` is cheap to reason about (no counter can ever
  disagree with the approved requests it is supposed to summarize) at the cost of an `O(n)`
  aggregate query per read — acceptable at SME scale (a year's worth of one employee's ANNUAL
  requests is a handful of rows); a materialized counter is a future perf fallback only if it ever
  bites, mirroring the December-YTD precedent in Track P Phase P3.
- The per-employee advisory lock closes the overlap/balance RACE but, like the expense-claim
  currency lock, cannot retroactively fix a bad value that already committed — there is no
  "unapprove" path in v1; a wrongly-approved request needs a manual balance adjustment to correct.
- **Deferred (tracked, not built here):** accrual/carry-over/expiry of unused annual leave (v1
  grants the full 12 days on day one of every year, no proration for a mid-year hire, no rollover);
  the sick-leave 100/75/50/25% prolonged-illness pay schedule (v1 is paid-full only); a holiday
  calendar (the `REST_DAY` `day_kind` on an overtime entry is caller-asserted, not
  calendar-derived); the PP 35/2021 4h/day overtime regulatory cap (only the 10h/entry DB `CHECK`
  is enforced); termination-date-aware balance proration. Each is a real sub-project the domain spec
  already flagged as out of v1 scope.
- Track P Phase P7 depends on this landing first (the program's hard merge-order constraint); this
  phase's tables/reads are additive and require no changes here when P7 wires them into a run.
