# 30. Employee expense claims — recognition at approval, a new event family, and a settle-once payable

Date: 2026-08-02

## Status

Accepted

## Context

The Odoo-parity gap analysis scored Expenses at ~10%: the `ExpenseRecorded` contract exists and
finance consumes it (Dr expense by `gl_hint` / Cr CASH_CLEARING), but **nothing produces it** and
the whole claims workflow — an employee submits a receipt, a manager approves, the company
reimburses — does not exist. Odoo Expenses is the target: submit w/ photo → approve/refuse →
post to the books → reimburse (through the next payslip or paid directly). The repo has no file
storage, no human approve/reject state machine, and `/api/v1/me/**` has never had a write.

## Decision

1. **Claims live in employee-service** (`expense` feature package). It owns the employee
   aggregate, the Keycloak `user_id` link that powers `/me`, the PII cipher, and the payroll run
   the reimbursement rides. A new service would duplicate all four.
2. **Expense recognition at approval.** A manager's approve posts, via a NEW event, Dr the
   category's expense account (resolved from `gl_hint` by finance's effective `mapping_rule`,
   suspense fail-safe) / **Cr `2600 Employee Expense Payable`** (new account + `AccountRole
   EMPLOYEE_EXPENSE_PAYABLE`). Settlement later clears the payable — a balance-sheet move.
3. **A new event family — NOT an evolution of `ExpenseRecorded`.** `ExpenseClaimApproved`,
   `ExpenseClaimVoided`, `ExpenseReimbursementSettled` (namespace
   `id.co.nativeapp.events.employee`, aggregate `expense_claim`, partition key `claim_id`).
   `ExpenseRecorded`'s consumer semantics are hardwired to Cr CASH_CLEARING; overloading it with
   an optional discriminator would make an already-deployed finance post claims as cash expenses
   during any rolling-deploy window — schema-compatible but semantically wrong books. New topic =
   no mixed-semantics window.
4. **`employee_id` rides the events as a UUID reference.** `LaborCostAllocated` deliberately
   drops it because per-employee labor cost ≈ salary (rule 6). A claim amount derives nothing
   about compensation and is manager-visible by design; finance needs it for the payable
   sub-ledger drill-down. Merchant, note, and the receipt image never cross an event.
5. **State machine** (guarded transitions on the aggregate, AP-Bill idiom):
   `DRAFT → SUBMITTED → APPROVED | REFUSED`; cancel from DRAFT/SUBMITTED;
   `APPROVED → VOIDED` only while un-settled AND not linked to a payroll run (void = exact
   contra; refuse-after-approve is not allowed — money is on the books); `APPROVED → REIMBURSED`
   via DIRECT pay or a POSTED payroll run. Approve/refuse/pay/void require an `Idempotency-Key`
   (400 without); replay detection via an append-only `expense_claim_event` audit row with a
   per-tenant `UNIQUE (company_id, claim_id, idempotency_key)`. **Self-approval is 403**, owners
   included. Every transition records actor + comment (refuse requires one).
6. **Reimbursement, both paths (Odoo parity), converging on one settlement event.**
   - **DIRECT**: `POST /{id}/pay` → REIMBURSED + `ExpenseReimbursementSettled(DIRECT)` in-tx.
   - **PAYROLL**: an `ExpenseClaimPayrollLinker` links APPROVED+PAYROLL claims to the calculating
     run with an atomic conditional UPDATE (no read-modify-write window); the payslip carries a
     non-taxable `EXPENSE_REIMBURSEMENT` earning line (excluded from PPh 21/BPJS bases AND from
     `LaborCostAllocated` buckets — the expense is already on the books); the CALCULATED→POSTED
     transaction flips claims to REIMBURSED and emits one settlement event per claim. A
     superseded run's claims are **released and re-linked** by the corrective run, which re-emits
     settlements — safe because of (7).
   - The two paths race-guard each other: pay requires `reimbursement_run_id IS NULL` under the
     optimistic version; the linker's conditional UPDATE loses cleanly to a concurrent pay.
     **BINDING on the linker (E4 review W1): every linker UPDATE that sets or clears
     `reimbursement_run_id` MUST increment `version` in the same statement** — pay-direct flushes
     a full-column versioned UPDATE, so a linker that skips the version bump lets a stale pay
     clobber the link back to NULL and the employee is paid twice (direct cash + payslip).
   - **E5 transitional gating (`ExpenseClaimPayrollLinker` ships in E5; the `EXPENSE_REIMBURSEMENT`
     payslip line ships later, Track P Phase P7).** E5 wires `releaseForPeriod` + `linkForRun` +
     `findLinkedClaimTotalsByEmployee` + `ExpenseClaim#settleByPayrollRun` +
     `ExpenseReimbursementSettledSchema#toRecordPayroll` — the full atomic link/release
     machinery — but `markReimbursedAndEmit` (the CALCULATED→POSTED step that flips a linked claim
     to REIMBURSED and emits its settlement) is gated OFF until the run it is posting actually
     carries an `EXPENSE_REIMBURSEMENT` payslip line: it checks for that line's existence and, pre-
     P7, always finds none — logs and returns without flipping or emitting anything. This is
     deliberate, not a gap: flipping a claim to REIMBURSED and settling the payable BEFORE the
     employee's payslip actually carries the reimbursement line would book money the employee never
     received via that run — wrong books, the exact failure mode (7) exists to prevent. A claim
     linked in this window stays `APPROVED` + linked indefinitely. To recover it,
     `releaseForPeriod`'s predicate carries a THIRD branch alongside "run not POSTED" and "a
     superseded POSTED run": **linked to a POSTED run whose claim is still `APPROVED` (never
     flipped) is released unconditionally**, so the next `calculate()` for that period (any re-run —
     a correction, or simply an operator forcing one once P7 lands) frees it for re-linking. P7's own
     work is therefore additive: wire the earning line, and the very next re-run for a period with
     stranded claims picks them up with no further change to the linker. No claim is ever silently
     stuck forever, and none is ever falsely settled early.
   - **E5 review findings (fixed before P7 lands).** **W3 (the important one)** — supersession
     release+relink is SAME-CYCLE, not lagged: `releaseForPeriod(period, currentRunSeq)` takes the
     calculating run's OWN `run_seq`, and its "superseded" branch matches a linked POSTED run of the
     same period whose `run_seq < currentRunSeq`. Since a period's `next run_seq` is always
     `max + 1`, every PRIOR POSTED run necessarily satisfies that — so "linked to a POSTED run with
     a lower run_seq" IS exactly "linked to the run I, the corrective run, am about to supersede":
     the corrective run's OWN `calculate()` releases and re-links the claim to ITSELF, in the same
     cycle it does the superseding — not a third run later, as the original E5 predicate (which only
     matched a run_seq STRICTLY HIGHER than the one calculating — impossible during that very
     run's own `calculate()`) required. That original check is KEPT alongside the fix as an
     idempotent overlap safety net (widens, never narrows, the release condition). **W2** — the
     "run not POSTED" branch is now PERIOD-AGNOSTIC: `linkForRun` links a claim to a run regardless
     of the claim's own `expense_date`/period, so a claim stranded on an abandoned
     (CALCULATED-but-never-POSTED) run of period P must be released by ANY later `calculate()` for
     ANY period, not only a re-run of period P — an operator has no reason to ever recalculate an
     abandoned period on its own; only the "superseded" and "E5-transitional" branches stay
     period-scoped. **W1** — `findLinkedClaimTotalsByEmployee` filters `status = 'APPROVED'`: an
     already-REIMBURSED claim stays linked to its settling run forever (only `releaseForPeriod` ever
     clears the link), so without the filter an already-settled claim could fold into a totals read
     taken after its own settlement. **S1** — both bulk conditional UPDATEs stamp
     `updated_by = 'system:payroll-linker'` explicitly, since a native `@Modifying` UPDATE never
     runs through JPA's `@LastModifiedBy` auditing listener; no prior native `@Modifying` UPDATE in
     the codebase stamped `updated_by` at all, so this establishes the convention (CDC audit
     fidelity) for a system-triggered bulk write with no bound human actor.
7. **Finance settles once per claim.** A `employee_expense_claim_ledger` row with
   `UNIQUE (company_id, claim_id)`: any second settlement for a claim — re-delivery or
   supersession re-emission — is a logged no-op. This single invariant collapses every
   double-pay window; claim amounts are immutable after approval, so any re-settlement is
   financially identical. The row is also the §4 employee-payable drill-down source: it carries
   the recognition, void, and settlement facts as they land, in any arrival order, so it doubles as
   the reconciliation signal for an out-of-order or lost approval — a settlement landing with no
   matching recognition self-heals the row and logs a loud WARN, and a late-arriving approval
   reconciles onto that same row. The residual: a permanently-lost approval (never replayed) leaves
   that claim's account 2600 debit unbacked by a recognition entry indefinitely — until an operator
   replays it from the `ExpenseClaimApproved.DLT` (or its source), there is no automatic recovery.
8. **Receipts are `bytea` in the employee-service database** (`expense_receipt`, separate table;
   list projections never select the blob). No object store for v1: RLS, Auditable, backups and
   tenant deletion come free; Debezium is unaffected (outbox-only include list). Server caps
   5 MB file / 6 MB request; content-type whitelist jpeg/png/webp validated by **magic bytes**,
   not the declared header. Receipts are business documents, not rule-6 PII — stored unencrypted,
   never logged, served only via authenticated tenant-scoped GETs. OCR is deferred; it slots
   behind the same attachment model.
9. **v1 boundaries** (each revisitable): claims in the **company base currency only** (foreign
   currency needs FX-at-approval + a second payslip currency — real sub-projects; Odoo also
   reimburses in company currency); expenses ship inside the **`hr` module with no
   entitlement-service gate** (employee-service does not use `libs/entitlement-check` at all
   today — gating one feature while payroll stays ungated would be incoherent; when
   employee-service adopts the gate, it gates the whole module); the category **`taxable` flag is
   stored but drives no posting** (natura/per-diem taxability per PMK 66/2023 lands with the
   payroll program's TAXABLE_ALLOWANCE wiring).

## Consequences

- The books recognise expense liabilities the moment a manager approves — aging and P&L are
  truthful before any cash moves; 2600 shows exactly what the company owes its employees.
- Supersession correctness rests on TWO invariants working together: the linker releases/relinks
  claims across `run_seq`, and finance's settle-once guard makes re-emitted settlements no-ops.
  Tests must cover both arrival orders; reviewers of either program must check the pair.
- The first `/me` write and the first multipart upload enter the codebase — both surfaces must
  keep the absolute own-rows rule (resolve via `employee.user_id = X-Actor`, no id-widening
  params) and the magic-byte/size caps respectively.
- An unrecognised `gl_hint` quarantines to suspense (money posted, never dropped) — the category
  admin constrains hints to the seeded set, and a new hint requires a finance `mapping_rule` seed.
- Receipt blobs grow the employee DB (~MBs per claim); acceptable at SME scale, and the separate
  table + projection discipline keeps hot paths blob-free. An object store can replace the table
  behind the same endpoints if scale demands (a future ADR).
- **Residual (E3 spike finding): the gateway enforces NO body-size cap on any proxied request.**
  spring-cloud-gateway-server-mvc force-disables multipart resolution, so uploads stream through
  untouched and only the terminating service's limits bind (employee-service caps receipts at
  5 MB/6 MB) — but every other route is equally unbounded, a fleet-wide DoS/bandwidth surface
  that needs its own gateway ADR. Pinned by the permanent `MultipartUploadStreamingSpikeTest`.
- The per-tenant advisory lock that serializes first-currency establishment (W1, E1 code review)
  only closes the RACE — it cannot validate CORRECTNESS. Whichever concurrent request wins still
  pins the tenant's expense-claim currency for good, with no admin "reset" path in v1; in practice
  the console always sends the company's base currency on every claim, so this residual only bites
  a non-console API caller.

## §10 — Track P Phase P7 addendum: the reimbursement line is live

Point 6's E5-transitional gate (`ExpenseClaimPayrollLinker#markReimbursedAndEmit` checking for an
actual `EXPENSE_REIMBURSEMENT` payslip line before flipping a claim to REIMBURSED) is no longer
always closed: Track P Phase P7 (ADR 0031's P7 update) adds the payslip line itself, gated on the
tenant having activated a dataset that carries the `EXPENSE_REIMBURSEMENT` catalog component
(`ID-2026.2`+). For such a tenant, a linked claim now genuinely rides the payslip, settles, and the
E5 gate's own no-op branch simply never fires. For a tenant that has NOT activated that dataset, the
gate's original fallback behaviour is UNCHANGED — a linked claim stays `APPROVED`, recoverable by the
next `calculate()`'s release/relink pass, exactly as designed in point 6.

The reimbursement's LIABILITY-side accounting (why `NET_WAGES_PAYABLE` is `net − reimbursementTotal`,
never the full net, and why `LaborCostAllocated` excludes it entirely) is recorded in ADR 0032's own
P7 addendum, not repeated here — that ADR owns the liability/allocation side of the books; this one
owns the claim lifecycle and the `2600`/`2600`-settlement side, both unchanged by P7.

**P7 review round — the settle gate is now PER-EMPLOYEE, and claim ids are frozen (W3/W4).** A P7
review found the §10 gate above ("checking for an actual `EXPENSE_REIMBURSEMENT` payslip line")
described a WHOLE-RUN check that was too coarse once `reimbursementInfoByEmployee`'s currency-mismatch
skip (§9) can leave one employee's claim un-applied while others in the same run settle —
`markReimbursedAndEmit` now derives the SET of employees who actually carry the line THIS run and
settles a claim only when its own employee is in that set; a skipped employee's claim stays
`APPROVED` + linked exactly as point 6/§10 already describe for the whole-dataset-not-activated case.
`ExpenseClaimPayrollLinker#findLinkedClaimIdsByEmployee` (a new `MANDATORY` read alongside the
existing totals read) also lets `work_inputs_json` freeze the INDIVIDUAL linked claim ids, not just
the aggregate total/count — full details, including the regression-proving test, are in ADR 0032's P7
review addendum (the liability-side ADR now owns both the settle-gating and the claim-id-freezing
write-up, alongside the split it already owned, to keep the P7-review narrative in one place).
