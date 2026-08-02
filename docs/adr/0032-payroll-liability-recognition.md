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
