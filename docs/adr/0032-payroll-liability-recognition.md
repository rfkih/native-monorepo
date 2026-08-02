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

2. **The bucket-amount READ design.** No bucket total is stored per-role on `payroll_run_ledger` —
   only the run's single liability `journal_entry_id` is (P4's design). Both the settlement writer
   and the `GET /api/v1/payroll-liabilities` read reconstruct a bucket's signed total the SAME way:
   resolve the bucket's `AccountRole` to the `chart_of_account.account_code` it mapped to AT THE
   LIABILITY ENTRY'S OWN `occurred_at` (a `LATERAL` join against `role_account_map` using the exact
   `RoleAccountResolver` tie-break — `version DESC, effective_from DESC LIMIT 1` — so a LATER
   `role_account_map` edit can never misattribute a historical line), then sum that account code's
   `journal_line` rows as `credit_minor - debit_minor` — reconstructing the signed amount exactly as
   P4 posted it (positive = credit-side bucket, negative = the December Art-17 refund's debit-side
   bucket, ADR 0031). The settlement writer additionally falls back to
   `RoleAccountResolver.SUSPENSE_ACCOUNT_CODE` when a role is unmapped (mirroring
   `PayrollLiabilityWriter`'s own posting-time fallback) so the historical lookup finds the SAME
   line the entry actually carries — a documented residual: if two DIFFERENT bucket roles were BOTH
   unmapped at posting time, both would resolve to the SAME suspense account and become
   indistinguishable by role at settlement time (not reachable today — V40 maps all five roles).
   The `GET` read (`PayrollLiabilityRunView`, `PayrollRunLedgerRepository.LIABILITY_BUCKET_SELECT`)
   is the SAME join, generalized across all five roles in one query, restricted to
   `liability_state = 'POSTED'` runs (the currently-owed set) for the period-scoped list, or
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
