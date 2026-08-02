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
