# 32. Payroll liability recognition — a third event, a clearing re-class, and run-type-aware supersession

## Status

Accepted

## Context

Phase 3 (#23) wired `PayrollPosted` (company totals) and `LaborCostAllocated` (outlet/GL cost-center
buckets) into finance: every posted run books `Dr LABOR_EXPENSE / Cr LABOR_CLEARING (6900)` per
bucket. `6900` was always meant to be cleared — but nothing ever clears it. Net pay is computed
(encrypted on `payslip_line`) but never disbursed and never booked as a liability; employee
deductions (PPh21, BPJS) never reach the GL. `6900` accumulates forever, and the books cannot answer
"how much does the company currently owe its employees, the tax office, and BPJS?" — the #1 honesty
gap the payroll-parity gap analysis flagged.

Two designs were considered for recognising the liability side of the same money:

1. **One combined journal entry spanning both `PayrollPosted` and `LaborCostAllocated`.** Rejected:
   it needs a barrier ("wait for both events before posting anything"), which breaks the standing
   invariant that finance never blocks on `PayrollPosted` (buckets post as they arrive; `PayrollPosted`
   is reconciliation-only, #23). Barriers also complicate out-of-order delivery and supersession,
   which the existing labor consumers already handle without one.
2. **A NEW event, `PayrollLiabilitiesPosted`, emitted third (after `PayrollPosted` and every
   `LaborCostAllocated` bucket) in the SAME `post()` outbox transaction — a clearing re-class.**
   Finance posts `Dr LABOR_CLEARING (6900)` for the run's total employer-borne labor cost (the SAME
   sum the buckets already total to) / `Cr` one leg per liability bucket. `6900` still nets to the
   run's total after both consumers finish — the re-class is accounting-equivalent to the combined
   design's end state, without a barrier.

Design (2) is the decision.

## Decision

1. **`PayrollLiabilitiesPosted` (`id.co.nativeapp.events.employee`, aggregate `payroll_run`,
   partition key `payroll_run_id`)** carries `employer_cost_total_minor` (= `gross_total_minor +
   employer_contribution_total_minor`, the Dr leg) and a `liabilities` array of `{liability_role,
   amount_minor}` buckets (the Cr legs, a zero bucket omitted): `NET_WAGES_PAYABLE`,
   `PPH21_PAYABLE`, `BPJS_KES_PAYABLE` (Kesehatan EE-withheld + ER-contribution together — both owed
   to the same BPJS body), `BPJS_TK_PAYABLE` (JHT + JP, EE+ER, plus JKK/JKM ER-only — all four owed
   to the same BPJS body), `OTHER_DEDUCTIONS_PAYABLE` (any deduction line not named above —
   EMPLOYEE- or EMPLOYER-borne alike, e.g. a future custom component such as a loan repayment). NO
   PII: company-level bucket totals only, exactly like `PayrollPosted`/`LaborCostAllocated`.
2. **The producer asserts the accounting identity before writing anything**:
   `employer_cost_total == Σ(liability bucket amounts)`, where the buckets sum ALREADY includes
   `NET_WAGES_PAYABLE` (== `net_total`) as one of its five entries — equivalently `employer_cost_total
   == net_total + Σ(the other four buckets)`, but the two must never be added twice (an
   implementation bug that summed `net_total` AND iterated the buckets — whose own `NET_WAGES_PAYABLE`
   entry already carries that same total — shipped briefly during this phase and was caught by the
   P4 test suite before merge). This holds STRUCTURALLY because every DEDUCTION line — regardless of
   bearer — is bucketed into exactly one of the four non-net roles (the catch-all
   `OTHER_DEDUCTIONS_PAYABLE` takes EMPLOYER-borne lines too, not just EMPLOYEE-borne ones, for
   exactly this reason); the assertion is still a genuine cross-check because `employer_cost_total`/
   `net_total` are `payroll_run`'s STORED totals (accumulated during `calculate()`) while the four
   deduction buckets are FRESHLY decrypted-and-summed from `payslip_line` at `post()` time —
   independently derived from the same underlying data. A mismatch throws loudly; an
   unbalanced event is never emitted (HR-3).
3. **A bucket amount MAY be negative** — the December Art-17 true-up refund month (ADR 0031) can
   drive `PPH21_PAYABLE` negative. `JournalLine` enforces a strictly-positive, single-sided
   debit-XOR-credit invariant, so a negative bucket cannot become a negative Cr leg. Finance instead
   posts it as a Dr leg for its absolute value — the opposite side. This keeps the entry balanced by
   construction: `Dr(6900 + Σ|negative buckets|) = net + Σ(positive buckets) = Cr total`.
4. **Additive `run_type` (string, default `"REGULAR"`) lands on `PayrollPosted` AND
   `LaborCostAllocated` in this SAME phase**, ahead of Track P phase P8 (THR, ADR 0034) — so finance
   is run-type-aware before any THR run exists, rather than needing a second migration later.
   `PayrollRunLedgerRepository`'s supersession scans (`findActivePriorRuns`/`existsActiveHigherRun`)
   and the per-run advisory lock key are re-keyed onto `(company, period, run_type, run_seq)`; the
   `DEFAULT 'REGULAR'` on `payroll_run_ledger.run_type` (new column, V40) keeps every existing run
   and every pre-P4 test byte-identical.
5. **`PayrollLiabilityWriter` posts ONE ad-hoc balanced `JournalEntry`** — not a `posting_template`
   (unlike every fixed-shape `EventKind` since V13): the leg count is variable (1–5 non-zero
   buckets) and a bucket may sit on either side depending on sign, which does not fit the
   fixed-template shape. This mirrors `TaxSettlementWriter`/`TaxFilingWriter`'s ad-hoc pattern. An
   unrecognised `liability_role` string, or a role with no effective `role_account_map` row, fails
   safe to `SUSPENSE` with a logged WARN (money never dropped, HR-3).
6. **The liability dimension gets its OWN lifecycle column pair — `liability_entry_id` /
   `liability_state`** on the SHARED `payroll_run_ledger` control row, tracked INDEPENDENTLY of the
   pre-existing `state` (the `LaborCostPostingWriter`/`PayrollReconciliationWriter` reconciliation
   lifecycle). Sharing one `state` column across three writers would create a cross-writer
   coordination gap: if `LaborCostPostingWriter` flips `state` to `SUPERSEDED` before the liability
   event for the same prior run arrives (because ITS bucket for the higher-seq run arrived first), a
   shared-column supersession scan would never find that prior run's liability entry to reverse.
   `PayrollLiabilityWriter`'s own supersession scan (new repository queries
   `findActiveLiabilityPriorRuns`/`existsActiveHigherLiabilityRun`) filters on `liability_state`
   instead, and reuses the SAME per-(company, period, run_type) advisory lock so all three writers
   serialize against each other on the shared row.
7. **Supersession mirrors `LaborCostPostingWriter` steps 2/2a/6 exactly** — append-only, per-leg
   debit↔credit-swap contra entries with a deterministic synthetic `source_event_id`
   (`ReversalEventIds.forPriorPosting`), `processOnce` + the `journal_entry.source_event_id` UNIQUE
   backstop for idempotent re-delivery, and the out-of-order self-supersession case (a late lower-seq
   event arriving after a higher-seq run's liability already posted posts its own PRIMARY then
   immediately self-contras).
8. **No settlement yet.** This phase only RECOGNISES the liability; clearing it (a bank-file payment
   for net wages, a tax-office remittance for PPh21, a BPJS contribution payment) is Track P phase
   P5 (`payroll_settlement`, `Dr 26xx / Cr CASH_CLEARING`). Until then the five liability accounts
   accrue every POSTED run with no automated clearing — an accepted, documented gap for THIS phase.

## Consequences

- The books can finally answer "what does the company currently owe, and to whom" for payroll — the
  #1 honesty gap this program was scoped to close. `6900 LABOR_CLEARING` nets to zero per run once
  both the labor-cost and liability consumers finish (verifiable per period).
- Three writers now share one control row with two independent lifecycles; any reviewer of a future
  payroll-liability change MUST re-verify supersession under BOTH arrival orders AND both writer
  families (Labor/Reconciliation vs Liability) — the two are not automatically consistent just
  because they share a table.
- Chart-of-account codes `2610`/`2620`/`2630`/`2640`/`2690` are ILLUSTRATIVE, SME-gated exactly like
  every other account seeded since V13 — an accountant swaps them via higher-version
  `role_account_map` rows, no code change.
- `run_type` lands two phases ahead of THR (P8) by design — a smaller, well-tested surface now
  instead of a second migration touching the same supersession-scan queries later.
- The identity assertion is only as strong as its OWN arithmetic: a review-caught bug briefly
  double-counted `NET_WAGES_PAYABLE` (seeded once as the running sum's starting value, then again as
  a `liabilities` entry) — it surfaced immediately as a hard `IllegalStateException` failure on
  EVERY OFFICIAL-dataset payroll test (BPJS legs make the numbers large enough to be unmissable),
  never as a silent wrong number. The fix + the exact failure mode are documented in
  `PayrollRunWriter#assertLiabilityIdentity`'s javadoc as a permanent warning against re-introducing
  the double-count.
- Residual (tracked, not fixed here): no currency-mismatch guard analogous to
  `LaborCostPostingWriter`'s `CURRENCY_MISMATCH` state exists for the liability dimension — a
  divergent-currency liability event (should never occur; a company has one immutable base currency)
  would surface as a `MismatchedCurrencyException`/`UnbalancedJournalException` propagating out of
  the consumer to the bounded-retry-then-DLT path, not a dedicated terminal state. Add one if this
  ever proves reachable in practice.

## P5 addendum — settlement + the net-pay bank file (finance V41, employee-service)

Track P phase P5 closes the gap #8 above left open: clearing the five liability accounts P4
recognised. Two new surfaces land in the SAME phase, both read-only against each other (no new
event — `docs/EVENT-CATALOG.md` is unchanged):

1. **`payroll_settlement`** (finance, V41) — a one-shot settlement of exactly one bucket of one
   run: `Dr <bucket role account> / Cr CASH_CLEARING (1900)`, posted in the CURRENT period (the
   `TaxSettlementWriter` convention — the payment is made now, regardless of the liability's
   original period). `PayrollSettlementWriter` guards, in order: (a) an Idempotency-Key replay —
   the SAME key against the SAME `(run, kind)` returns the original settlement, `200`, posting
   nothing again; the SAME key against a DIFFERENT `(run, kind)` is a `409`
   (`payroll-settlement-idempotency-key-conflict`, the path/body-authoritative
   `AssetDisposalWriter` idiom); (b) the run must exist in the bound tenant (`404` otherwise); (c) a
   DIFFERENT key against an already-settled `(run, kind)` is a `409`
   (`payroll-settlement-already-settled` — a genuine second attempt, not a retry); (d) the run's
   `liability_state` must be `POSTED` — `NULL` (never recognised) or `SUPERSEDED` both fail `409`
   (`payroll-liability-not-settleable`) — **settling a superseded run is forbidden**, its liability
   entry has already been reversed by P4's supersession contra; (e) the bucket amount is read back
   from the run's OWN liability `journal_entry` (never client-supplied — see the bucket-amount read
   design below); a zero/absent bucket is `409` (`payroll-liability-bucket-empty`, nothing to pay);
   a negative bucket is `422` (below).

2. **The bucket-amount READ design — NOT literally the same mechanism on both sides (corrected
   post-review, W3).** No bucket total is stored per-role on `payroll_run_ledger` — only the run's
   single liability `journal_entry_id` is (P4's design). The `GET /api/v1/payroll-liabilities` read
   REVERSE-resolves: for every `journal_line` of the entry, a `LATERAL` join against
   `role_account_map` (the exact `RoleAccountResolver` tie-break — `version DESC, effective_from
   DESC LIMIT 1` — keyed on the liability entry's OWN `occurred_at`, so a LATER `role_account_map`
   edit can never misattribute a historical line) maps that line's account code BACK to a role, and
   the five totals are pivoted by role name — this never assumes a role maps to a unique account, so
   it is correct even if two roles briefly shared an account. `PayrollSettlementWriter` instead
   FORWARD-resolves: it resolves the ONE role being settled to a single account code (the same
   resolution `PayrollLiabilityWriter` used when it built the line, at the entry's `occurred_at`,
   never at "now" — the P5 review C1 fix) and filters the entry's lines by that code — cheaper, but
   only correct while the resolution is unambiguous. **The original text here claimed both sides
   reconstruct a bucket "the SAME way"; that was inaccurate — the forward/reverse distinction above
   is the truth, and it is the reason the SUSPENSE guard below exists.** The settlement writer falls
   back to `RoleAccountResolver.SUSPENSE_ACCOUNT_CODE` when a role was unmapped at accrual time
   (mirroring `PayrollLiabilityWriter`'s own posting-time fallback) so the historical lookup finds
   the SAME line the entry actually carries — but a bucket resolving to SUSPENSE is REJECTED before
   settling (`PayrollLiabilitySuspenseBucketException`, `409`, P5 review W3): the ambiguous case (two
   DIFFERENT bucket roles BOTH unmapped at accrual time, sharing the suspense account and therefore
   indistinguishable by account code alone) can never reach a settlement debit, closing the residual
   this section previously only documented. The `GET` read
   (`PayrollLiabilityRunView`, `PayrollRunLedgerRepository.LIABILITY_BUCKET_SELECT`) is unaffected —
   it stays the reverse-resolution join, generalized across all five roles in one query, restricted
   to `liability_state = 'POSTED'` runs (the currently-owed set) for the period-scoped list, or
   unrestricted for the single-run-by-id read (so a SUPERSEDED run's historical totals remain
   auditable even though settling it is blocked).

3. **A negative bucket is REJECTED, v1 residual.** The December Art-17 true-up (ADR 0031) can drive
   `PPH21_PAYABLE` negative — a negative payable is in substance a RECEIVABLE from the tax office,
   netted against the NEXT period's remittance, not a standalone payment. Rather than invent an
   unmodeled receivable-settlement flow, `NegativeLiabilityBucketException` rejects it with a `422`
   typed problem directing the caller to net it against the next remittance. Tracked as a residual
   for whenever a real tax-remittance netting flow is designed.

4. **The net-pay bank file is a NEW employee-service endpoint**, not a finance one — the amounts
   come from `payslip_line` (PII, employee-service's own aggregate), not the GL. `GET
   /api/v1/payroll-runs/{runId}/bank-file` (POSTED runs only, `409` otherwise) streams a `text/csv`
   CSV (`employee_name, bank_account, net_amount_minor, currency, reference`) built by
   `BankFileReader`: net mirrors `MeReader#payslipDetail`'s own computation exactly (`Σ
   EMPLOYEE-borne EARNING − Σ EMPLOYEE-borne DEDUCTION`; EMPLOYER-borne lines are cost, never net
   pay). `Employee#bankAccountForBankFile()` is a NEW, deliberately narrow public accessor — the
   ONE OTHER place (besides the encrypt/decrypt round-trip test) the decrypted bank account crosses
   a boundary; it lands directly in the CSV body and is NEVER logged. The one audit line this reader
   emits carries `runId` + a row count ONLY (rule 6). An employee whose decrypted bank account is
   blank still gets a CSV row (an empty cell, never silently dropped) — defensive: the aggregate's
   constructor requires a non-blank value today, so this is unreachable in practice, but a future
   relaxation must not vanish a row; the trailing `# row_count=N` comment line lets a recipient
   verify no row was ever dropped.

5. **Route posture.** `/api/v1/payroll-liabilities/**` rides the ordinary DASHBOARD_ROLES
   (owner/manager) gate, like every other finance read/write surface. The bank file is
   OWNER-ONLY — a NEW, narrower `OWNER_ROLES = {"owner"}` gateway constant (manager is excluded, a
   posture stricter than the payroll-runs DASHBOARD surface it nests under) — via a
   `@Order(HIGHEST_PRECEDENCE)` exact route (`path("/api/v1/payroll-runs/*/bank-file")`, a
   single-path-segment wildcard) checked BEFORE the general `/api/v1/payroll-runs/**`
   (DASHBOARD_ROLES) route, mirroring the `userMeOutletsRoute`/`currentCompanyRoute`
   first-match-wins precedent — RouterFunction beans are matched in DECLARATION order across the
   WHOLE bean set, not most-specific-path-wins, so ordering is load-bearing, not cosmetic.

6. **Deviation from the original column list.** `payroll_settlement` also carries an
   `idempotency_key` column (V41) not in the original phase brief's literal column list — required
   to implement BOTH halves of the spec (same-key replay `200`; a different key against an
   already-settled bucket `409`), mirroring `invoice_payment.idempotency_key` (V26) rather than
   `TaxSettlementWriter`'s keyless one-shot-transition idiom (settlement here is a genuinely
   repeatable client action, unlike a filing-status flip).

## Post-review amendments (P4 review, same phase)

- **W1 — a refund-only run may carry `employer_cost_total == 0`** (every employee zero-gross, one
  over-withheld December refund): the accrual's Dr 6900 leg is now CONDITIONAL on a positive
  employer cost — the bucket legs balance on their own (e.g. Dr 2610 / Cr 2640) by the producer
  identity. An unconditional strictly-positive debit would have DLT'd a valid run and left 6900
  uncleared for it.
- **W2 — additive fields go at the RECORD END.** The runtime decodes with a single schema (no
  writer-schema resolution — `AvroSerde.deserialize(payload, SCHEMA)`), so a mid-record insertion
  makes genuinely-old bytes silently MISREAD every following field; at the record end, old bytes
  fail fast (EOF → DLT) instead. `run_type` was moved to the end of all three schemas while the
  events have never left dev. **Standing rule for every future additive field on a live event:
  append at the end, and drain in-flight topics before deploying a consumer whose schema changed
  mid-record.** The real fix (propagating the writer schema / a registry-backed serde) is a
  documented platform residual.

## Post-review amendments (P5 review, same phase)

- **C1 (CRITICAL, fixed) — the settlement Dr leg now debits the SAME as-of-`occurred_at` account
  the amount was reconstructed from, never a fresh "now" resolution.** The original
  `PayrollSettlementWriter.buildSettlementEntry` called `requireMapped(kind.liabilityRole(), now)`
  — a SECOND, independent resolution of the bucket's role at settlement time — while the amount had
  already been reconstructed by resolving the SAME role at the liability entry's `occurred_at`.
  Under a `role_account_map` remap landing between accrual and settlement, the two resolutions
  diverge: the entry debited the NEW account while the amount (and the original payable) sat on the
  OLD one — the original account is never cleared, and the new one goes net-negative for money it
  never actually received. The fix threads the writer's ALREADY-COMPUTED, as-of-`occurred_at`
  account code straight into `buildSettlementEntry` (a new parameter); the method no longer resolves
  the bucket's role at all — ONLY `CASH_CLEARING` still resolves at "now" (mirroring every other
  settlement writer: cash always clears through the currently-mapped account). A side effect,
  covered by its own test: a role whose mapping has since EXPIRED by settlement time no longer
  surfaces an unhandled `IllegalStateException` (→ 500) — settling never asks what the role maps to
  "now", so it succeeds regardless. Regression tests: `PayrollSettlementWriterTest#aRoleAccountMapRemapBetweenAccrualAndSettlementStillDebitsTheOriginalAccrualAccount`,
  `#settlingSucceedsEvenWhenTheBucketRolesMappingHasExpiredByNow`, and a DB-independent proof
  (`#buildSettlementEntryNeverResolvesTheBucketRoleItselfOnlyCashClearingResolvesAtNow`).

- **W1 (fixed) — an all-zero run (gross, employer contributions, and every liability bucket all
  zero) now completes its liability lifecycle instead of DLT-ing forever.** `employer_cost_total ==
  0` (so the 6900 leg is skipped, P4's W1 fix) combined with an EMPTY `liabilities` array produces
  ZERO `JournalLine`s; `PayrollLiabilityWriter.buildLiabilityEntry` previously still called {@code
  JournalEntry.balanced}, which requires ≥ 2 lines and throws — the consumer went to the DLT and
  `liability_state` stayed `NULL` forever (this run's liability could never be settled OR correctly
  reported as "nothing owed"). The fix: `buildLiabilityEntry` returns `null` for a genuinely empty
  set; the writer then stamps the ledger row `liability_state = POSTED` with `liability_entry_id =
  NULL` (`PayrollRunLedger#markLiabilityPostedEmpty`) — the lifecycle is COMPLETE, just empty, and
  no entry is ever attempted. The supersession queries
  (`findActiveLiabilityPriorRuns`/`existsActiveHigherLiabilityRun`) were re-keyed onto `liability_state
  = 'POSTED'` alone (dropping the `liability_entry_id IS NOT NULL` filter, which would otherwise hide
  a POSTED-with-no-entry row from a later corrective run's supersession scan forever) — reversing a
  null-entry prior run is already a documented no-op in `reversePriorRunLiability`. Regression tests
  in `PayrollLiabilityWriterTest`: `anAllZeroRunRecognisesNothingAndMarksTheLedgerPostedWithNoEntry`,
  `redeliveryOfAnAllZeroEventIsANoOp`, `aLaterRealRunSupersedesAnAllZeroPriorRunWithoutAnNpe`,
  `anAllZeroRunArrivingAfterAHigherSeqRunIsPostedThenImmediatelySuperseded`.

- **W2 (documented, not code-changed) — the settlement writer takes NO advisory lock.** Two
  DIFFERENT Idempotency-Keys racing the SAME `(run, kind)` are serialized purely by the
  `uq_payroll_settlement_once` DB unique constraint: exactly one wins, the loser's WHOLE transaction
  (including its orphan journal entry) rolls back on the `DataIntegrityViolationException`, which
  `PayrollLiabilityAdvice#handleConcurrentConflict` maps to `409` — proven by the dedicated
  `PayrollSettlementConcurrencyTest`. A raced SAME-key replay (two requests carrying the IDENTICAL
  key, neither committed when the other's replay probe runs) is a narrower, ACCEPTED residual: the
  loser sees a generic `409` instead of the graceful `200` a sequential retry would get. Documented
  on `PayrollSettlementWriter`'s class javadoc; a `REQUIRES_NEW` re-read recovery (the `SaleWriter`
  idiom) is more machinery than this action currently warrants.

- **W3 (fixed) — a suspense-parked bucket cannot be settled.** Per the corrected bucket-amount READ
  design above (§2), the settlement writer's forward-resolution is only unambiguous while at most one
  bucket role is unmapped at any instant; `PayrollLiabilitySuspenseBucketException` (`409`,
  `payroll-liability-suspense-bucket`) now rejects settling ANY bucket whose accrual-time resolution
  is `SUSPENSE`, closing that latent double-pay window entirely rather than accepting the residual.
  An accountant must map the role (a higher-version `role_account_map` row) and let the run be
  corrected/re-posted before the bucket becomes settleable. Test:
  `PayrollSettlementWriterTest#settlingABucketPostedToSuspenseAtAccrualTimeIsRejected`.

- **S1 (fixed) — `GET /api/v1/payroll-liabilities` now returns the ENGINEERING-STANDARDS §1.3
  pagination envelope** (`{content, page, size, totalElements, totalPages}`, a new local
  `finance.labor.dto.PageResponse<T>` mirroring `employee-service`'s `expense.dto.PageResponse`
  exactly — no fleet-wide shared version exists yet), never a bare array. Paginated IN-MEMORY over
  the already-fetched full list (default size 20, max 100, per the standard) rather than a DB
  `LIMIT`/`OFFSET`: one company's one period's run count is small. The console `usePayrollLiabilities`
  hook was updated to read `.content`.

- **S2 / S3 (residual, no code change this phase).** (a) The bank-file CSV does not defend against
  formula injection: a `bank_account`/`employee_name` cell beginning with `=`, `+`, `-`, or `@` could
  be interpreted as a formula by a spreadsheet application that opens the file (a known CSV-export
  hazard, not specific to this endpoint). (b) A payslip line whose net amount is exactly zero or
  negative (e.g. a fully-clawed-back advance) still produces a bank-file row with that
  `net_amount_minor` — no floor/skip/flag exists for a non-positive net, which a real bank transfer
  file would need to reject or special-case. Both are tracked as follow-ups for whenever the bank
  file moves beyond its current illustrative/manual-review posture, not fixed in this pass.

- **S4 — two doc typos fixed.** `PayrollLiabilityRunView`'s javadoc stated the signed-bucket formula
  as `credit_minor - credit_minor` (should read `credit_minor - debit_minor`); `PayrollRunLedgerRepository`'s
  class javadoc called its queries "Derived/JPQL" (this codebase has no JPQL anywhere — corrected to
  "Derived-query methods plus NATIVE `@Query` methods").

## P7 addendum (2026-08-02) — the NET_WAGES_PAYABLE / employer-cost split for reimbursement

Track P phase P7 (ADR 0031's P7 update) puts a real `EXPENSE_REIMBURSEMENT` payslip line into the
run for the first time (ADR 0030 §6's E5 seam, previously wired but never actually fed). This is the
crux this addendum records: reimbursement lifts the employee's NET pay (real cash in the SAME
transfer) but is NOT labor cost — the claim's expense was already recognised, Dr expense / Cr `2600
Employee Expense Payable`, at manager APPROVAL time (ADR 0030). Crediting the FULL `net_total` to
`NET_WAGES_PAYABLE (2640)` would double-book the reimbursed portion: once as the `2600` payable
(settled separately when `ExpenseClaimPayrollLinker#markReimbursedAndEmit` emits
`ExpenseReimbursementSettled(PAYROLL)`, which finance's `empexpense` consumer clears Dr 2600 / Cr
CASH_CLEARING) and again as `2640`.

**Resolution — split at the SOURCE of the two totals, not with a new liability bucket.**
`PayrollLiabilityWriter`'s bucket set is UNCHANGED (still the same five roles); no new
`liability_role`, no schema/event change. Instead, `PayrollRunWriter` computes BOTH numbers net of
the SAME `reimbursementTotal` (freshly decrypted-and-summed from the run's `EXPENSE_REIMBURSEMENT`
EARNING lines, in the SAME single pass `computeLiabilityBuckets` already makes over the run's payslip
lines — see `LiabilityComputation`, a new small record carrying the buckets AND that total together
so they can never be derived from two different reads):

- `NET_WAGES_PAYABLE = net_total − reimbursementTotal` — the LABOR-ONLY portion of net pay finance
  owes via the payroll liability account; the reimbursed portion is owed (and settled) via the
  SEPARATE `2600` payable instead, never through `2640`.
- `employer_cost_total = gross_total − reimbursementTotal + employer_contribution_total` — the SAME
  subtraction, so the accounting identity from ADR 0032's original design (`net_total + Σ(the other
  four buckets) = employer_cost_total`) holds ALGEBRAICALLY unchanged on these LABOR-ONLY figures:
  `(net_total − reimbursementTotal) + Σ(other 4 buckets) = (gross_total − employeeDeductionTotal) −
  reimbursementTotal + (employeeDeductionTotal + employerContributionTotal) = gross_total −
  reimbursementTotal + employerContributionTotal = employer_cost_total`. `assertLiabilityIdentity`
  is otherwise UNCHANGED — it still throws rather than ever emit an unbalanced event; it simply now
  checks against the reimbursement-adjusted `employer_cost_total`.
- `LaborCostAllocated`/`LaborCostAllocation` rows EXCLUDE `EXPENSE_REIMBURSEMENT` entirely (a new
  `PayrollRunWriter#laborCostByGlAccount` per-person grouping filters the component key out before
  summing into gl-account buckets) — the SAME `employer_cost_total` figure is what the allocation
  buckets sum to, proven by a dedicated assertion in `PayrollWorkInputsEndToEndTest` (the allocated
  sum equals `grossTotal − reimbursementTotal + employerContributionTotal` exactly).
- The PAYSLIP itself, and the P5 net-pay BANK FILE (`BankFileReader`, unchanged), still show/pay the
  FULL net INCLUDING the reimbursement — the employee receives ONE transfer for both. Only the GL
  split between `2640` and the `2600` settlement differs; the employee-facing figure is unaffected.

**The gl-hint bucket split (reconciliation #4 — allocation engine change only, no schema change).**
Independent of the reimbursement exclusion above, `LaborCostAllocated` buckets now split per
COMPONENT gl account (5100 salary / 5130 overtime / 5200 BPJS-ER / ...) instead of collapsing the
person's entire labor cost onto BASE's single gl account — `PayrollRunWriter#allocateForPerson` now
calls the pure `LaborCostAllocator` ONCE PER non-zero gl-account group (sharing the SAME per-outlet
earnings-share ratios across groups, since the share is about WHERE the person worked, not WHICH
component the cost belongs to), rather than once per person. `OVERTIME` lines carry a NEW gl hint,
`5130-OVERTIME`; finance-service gained a matching V42 migration (`6110 Overtime Expense` chart-of-
account row + one `mapping_rule` row, mirroring V3's exact labor-cost-postings idiom) — pure data, no
Java change, since `GlAccountResolver`/`LaborCostPostingWriter` already resolve a labor gl hint
data-driven via `mapping_rule`. The UNALLOCATED suspense path is UNCHANGED — it still collapses a
no-outlet-assignment person's entire (reimbursement-excluded) labor cost into ONE `9999-UNALLOCATED-
LABOR` bucket, deliberately not split, so an operator sees one clearly-marked suspense figure to
investigate rather than a fragmented one.

**Regression note.** This bucket split changed `PayrollRunEndToEndTest`'s pre-existing single-bucket
assertion (an illustrative-dataset employee with BASE + BPJS-Kes-ER now emits TWO `LaborCostAllocated`
events, not one) — updated to sum across however many buckets land, an intentional, reviewed behaviour
change, not a regression. A dedicated golden test,
`PayrollRunEndToEndTest#aWorkInputFreeTenantsPayslipAndTotalsAreByteIdenticalToAPreP7Run` (P7 review
S1), pins that THIS bucket split is the ONLY externally-visible difference for a work-input-free
tenant — every other figure (gross/employeeDeductions/employerContributions/net,
`payroll_run.work_inputs_json == "{}"`) is byte-identical to a pre-P7 run.

## P7 review addendum (2026-08-02) — claim-id freezing, per-employee settle gating, and the "cash circle" honestly scoped

An adversarial review of the P7 addendum above found three liability/reproducibility gaps in
`ExpenseClaimPayrollLinker`/`PayrollRunWriter`, fixed without changing the NET_WAGES_PAYABLE split
identity itself (the algebra above is unchanged):

**W3 — `work_inputs_json` now freezes the INDIVIDUAL claim ids that make up a reimbursement, not just
the aggregate total/count.** The original `reimbursementTotalsByEmployee` read only
`ExpenseClaimPayrollLinker#findLinkedClaimTotalsByEmployee` (one aggregate row per employee) — a
reproducibility gap: re-deriving WHICH claims a frozen total came from required a live join against
`expense_claim.reimbursement_run_id`, not the immutable run record itself. A new
`ExpenseClaimRepository#findLinkedClaimIdsByEmployee` (via `ExpenseClaimPayrollLinker
#findLinkedClaimIdsByEmployee`, `propagation = MANDATORY` like every other linker method) returns one
`(employeeId, claimId, currency)` row per linked-and-still-APPROVED claim; `PayrollRunWriter`'s new
`ReimbursementInfo` record (`total`, `claimCount`, `claimIds`) combines both reads (same currency
filter as the total) so `work_inputs_json`'s `reimbursement` node now carries `claimIds: [...]`
alongside `amountMinor`/`claimCount` — a re-run's `work_inputs_json` is now a COMPLETE reproducibility
record of exactly which claims funded the reimbursement line, not just how much.

**W4 — `markReimbursedAndEmit` now settles a claim ONLY if its OWN employee actually received an
`EXPENSE_REIMBURSEMENT` payslip line THIS run, not merely if ANY employee in the run did.** The
original E5-transitional gate (`existsByPayrollRunIdAndComponentKey`) checked whether the component
key appeared ANYWHERE in the run — sufficient while every linked claim's employee unconditionally got
a line, but `reimbursementInfoByEmployee`'s currency-mismatch skip (ADR 0030 §9 — a linked claim in a
currency other than the run's base currency is a data anomaly, logged and excluded) can leave ONE
employee's reimbursement un-applied while OTHERS in the same run settle normally. Settling that
skipped employee's claim would book a `2600`→settled transition, and emit
`ExpenseReimbursementSettled(PAYROLL)`, for money they never actually received via this run — the
exact wrong-books failure mode the gate exists to prevent, just at finer grain than the original
caught. `markReimbursedAndEmit` now derives, via a new
`PayslipLineRepository#findDistinctEmployeeIdsByPayrollRunIdAndComponentKey`, the SET of employee ids
that actually carry the line this run, and settles a linked claim only when its `employee_id` is in
that set; a skipped claim stays `APPROVED` + linked — `ExpenseClaimRepository#releaseForPeriod`'s
existing "POSTED but still APPROVED" branch (unchanged, the E5-transitional recovery path) releases it
for the NEXT `calculate()` to re-link and retry, exactly as it already did for the whole-run gate.
`PayrollWorkInputsEndToEndTest#aCurrencyMismatchedLinkedClaimStaysApprovedAndLinkedWhileAnotherEmployeesClaimSettles`
proves BOTH halves in one run: the matching-currency employee's claim settles, the mismatched
employee's claim stays APPROVED+linked, and a follow-up `calculate()` re-links it.

**The "cash circle" (`net == reimbursement` when labor floors to zero) is proven on an ISOLATED
catalog, not claimed as a general invariant.** See ADR 0031's P7 review addendum (W1) for the full
framing: once ID-2026.2's `base_kind=BASE_PAY` fix (ADR 0031 W5) is active, BPJS legs compute on the
UNCLAMPED base pay regardless of how far the unpaid-leave earning line reduces labor pay — so a run
with BOTH real BPJS/PPh21 AND a fully-clamped unpaid-leave line does NOT generally satisfy `net ==
reimbursement` (BPJS-EE/PPh21 still withhold against the unclamped base). `PayrollWorkInputsEndToEndTest`
proves the EXACT identity against a deliberately minimal catalog (BASE + UNPAID_LEAVE +
EXPENSE_REIMBURSEMENT only, zero statutory deductions) where no such confound exists, and separately
proves the hard invariant that DOES generalize unconditionally — labor net never goes negative — for
both the isolated catalog and (via ADR 0031's W5 regression test) the full BPJS-bearing case.
`PayrollAnnualTrueUpEndToEndTest#aNegativeDecemberPphRefundCombinedWithALinkedClaimReimbursementBalancesTheLiabilitySplit`
(P7 review S2c) is the complementary proof at the OTHER end of the scale: a large NEGATIVE December
PPh21 refund combined with a linked reimbursement in the SAME run, showing the liability split
identity balances even when PPh21 itself is a credit — the reimbursement rides alongside the true-up
without perturbing it (PPh21/BPJS figures are byte-identical with or without the reimbursement, since
it is non-taxable and never enters either base).
