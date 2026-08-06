# ADR 0042 — Go-live: declare the chart of accounts + tax rates OFFICIAL

## Status

Accepted — 2026-08-06.

## Context

Since the accounting core was built, every seeded chart-of-account name, GL role
mapping, POS/finance tax rate, and payroll statutory rule shipped flagged
**illustrative / placeholder / SME-gated**. That was deliberate honesty: the
figures were reasonable defaults, not values an accountant had verified against a
real chart of accounts or Indonesian statute. The framing is wired end-to-end as a
data-provenance flag (`uses_illustrative_rules` on `journal_entry`, `invoice`,
`bill`, `payroll_run`, `consolidated_pnl`, the dimensional `ledger_posting`, and
the statement/close/consolidation read models), surfaced on the dashboards as a
"provisional / Estimated / Illustrative" badge, and propagated on the Avro events.
The flag turns **true** because the seed reference data declares itself
illustrative — not because anything is wrong with the numbers.

We are now going live. The business owner has reviewed and **confirmed the figures
as production values**:

- **VAT / PPN — 11%** (Indonesia standard rate). Finance AR output VAT + AP input
  VAT (the `1_100` bp constant already equals 11%).
- **POS indirect tax** — restaurant **PB1 10%** + **service charge 5%**; carwash /
  barbershop **PPN 11%** (the rates already seeded; only their provenance was
  illustrative).
- **Payroll statutory** — activate the verified **`ID-2026.1`** dataset (real
  PPh21 TER, PTKP, BPJS from PMK 168/2023, UU HPP 7/2021, PMK 101/2016, Perpres
  64/2020, PP 44-46/2015) as the default for new tenants instead of the
  deliberately-fake illustrative seed.

## Decision

**Flip the underlying data to verified/OFFICIAL — do NOT delete the provenance
infrastructure.** The `uses_illustrative_rules` flag, its columns, the badges, and
the Avro fields all stay. They stop showing "illustrative" because the data they
report on is now declared production, and the machinery remains so a *future*
unverified rate change (a new jurisdiction, a rate table migration) can flag itself
again. Concretely:

1. **Finance chart of accounts + GL config (global reference data, migration V51).**
   Reword every `chart_of_account.name` to drop the `(ILLUSTRATIVE …)` /
   `(PLACEHOLDER …)` suffix. Supersede every `role_account_map` and
   `posting_template` at its highest version with an identical row at `version + 1`
   carrying `uses_illustrative = FALSE` (the resolvers pick the highest version —
   the documented V13 supersession mechanism; the illustrative version-1 rows stay
   as an audit trail). Account codes and template lines are unchanged — only the
   provenance flag and the display names change.

2. **Finance VAT (AR/AP).** Rename `ILLUSTRATIVE_OUTPUT_VAT_BP` /
   `ILLUSTRATIVE_INPUT_VAT_BP` → `OUTPUT_VAT_BP` / `INPUT_VAT_BP` (still `1_100`
   bp = 11%, now the official PPN rate) and stop stamping a taxable invoice/bill
   `uses_illustrative_rules=true` (the flag was literally `= taxable`; it becomes
   `false`). The tax amount is unchanged.

3. **POS indirect tax (restaurant / carwash / barbershop).** Insert a
   higher-`rule_version` `OFFICIAL-2026.1` `tax_charge_rule` row (provenance
   `OFFICIAL`) superseding each `ILLUSTRATIVE-2026.1` placeholder at the **same
   rate** — restaurant `PB1_RESTAURANT` 10% + `SERVICE_CHARGE` 5%, carwash
   `VAT_CARWASH` 11%, barbershop `VAT_BARBERSHOP` 11%. The resolver (`ORDER BY
   rule_version DESC`) prefers it; `TaxChargeService` then reports
   `usesIllustrative=false`, which flows onto `SaleRecorded` and the GL.

4. **Payroll (employee-service).** Make the official `ID-2026.1` dataset the
   default a new tenant's payroll setup seeds, instead of the illustrative rates.
   Activation deactivates the orphan illustrative `PPH21_PROGRESSIVE` rule, so every
   subsequent run resolves OFFICIAL and comes out `uses_illustrative_rules=false`.

## Consequences

- Every "illustrative / Estimated / provisional" badge disappears **because the
  data is now declared production**, not because the signal was suppressed. The
  provenance machinery is intact for the next genuinely-unverified change.
- **Historical stickiness.** `uses_illustrative_rules` is monotonic per posted row
  and sticky per `(company, period)` on `consolidated_pnl`. Journal entries / P&L
  periods posted *before* this change keep their illustrative flag (they *were*
  illustrative when booked — correct). Only postings after the flip are clean;
  fully clean dashboard periods appear from the first post-flip period. A
  retro-recompute of already-posted periods is out of scope.
- **Statutory ownership.** Declaring 11% PPN, 10% PB1, 5% service charge, and the
  `ID-2026.1` payroll figures "official" is the owner's/accountant's assertion, not
  the code's. Three payroll figures inside `ID-2026.1` remain self-flagged to
  verify at go-live: the **BPJS_JP ceiling** (adjusted every March), the
  **BPJS_JKK** default risk-class II rate, and a pajak.go.id **PPh21 TER**
  spot-check. Confirm these before real payroll.
- **Known residual — POS regime.** Restaurant uses PB1 (regional restaurant tax);
  carwash/barbershop use PPN 11%. If a regency's real PB1 rate differs, or a
  service vertical is actually PB1-not-PPN, seed another higher-version OFFICIAL
  `tax_charge_rule` row — no code change (the resolver picks the highest version).
- The `uses_illustrative` version-1 seed rows and the `ILLUSTRATIVE-2026.1`
  `tax_charge_rule` rows are retained as an audit trail of what the books used
  before go-live; they are never resolved once the OFFICIAL rows exist.
