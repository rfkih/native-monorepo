# 17. Tax / PPN — the VAT return, filing & settlement

Date: 2026-07-29

## Status

Accepted

## Context

Phase 4 of the Odoo accounting-parity program (Phases 1–3 = AR + AP + Bank, [ADR
0014](0014-accounts-receivable-subledger.md)/[0015](0015-accounts-payable-subledger.md)/[0016](0016-bank-reconciliation.md)).
AR already accrues **output VAT** to GL account `2200` (`VAT_OUTPUT`, a LIABILITY) on every taxable
invoice, and AP accrues **input VAT** to `1300` (`VAT_INPUT`, an ASSET) on every taxable bill — both
at an illustrative 11% (1100 bp). But nothing turned those accruals into a **tax return**: there was
no PPN (Indonesian VAT) report, no period-end netting, and no way to record the return being filed or
the net VAT paid to the tax authority. Phase 4 adds that pillar.

This is the **most SME-gated phase**: every Indonesian-tax parameter and policy is built as real
machinery with clearly-flagged illustrative defaults, swappable by data (higher-version
`role_account_map` / COA rows), not code — the same stance as AR/AP/Bank.

## Decision

1. **The PPN return is GL-derived, not a separate ledger.** For a period, output VAT = credit-net of
   `2200` (`VAT_OUTPUT`), input VAT = debit-net of `1300` (`VAT_INPUT`), and **net = output − input**
   → PAYABLE (≥ 0) or CREDITABLE (< 0). `VatReturnReader` wraps the existing `GlTrialBalanceReader`
   (inheriting its Σdebit==Σcredit + single-currency assertions) and resolves the two account codes
   via `RoleAccountResolver` (VAT_OUTPUT / VAT_INPUT), so a remapped chart is followed automatically.
   No new tables are needed for the report — the same pattern the income statement / balance sheet use.

2. **Filing posts an ad-hoc balanced netting entry into the return period** (built directly in
   `TaxFilingWriter`, resolving accounts via `RoleAccountResolver` — no `posting_template`, no new
   `EventKind`, exactly the Bank-reconciliation approach), because the net leg's *side* flips with the
   sign:
   - `Dr VAT_OUTPUT (2200)` for the output VAT + `Cr VAT_INPUT (1300)` for the input VAT — clears the
     period's accruals (a zero-amount leg is omitted).
   - net PAYABLE: `Cr VAT_PAYABLE (2300)` for `output − input`.
   - net CREDITABLE: `Dr VAT_CREDIT_CARRYFORWARD (1310)` for `input − output` (the Indonesian default,
     *dikompensasikan* — carry the credit forward rather than claim an immediate refund).

   The entry belongs to the **return period** (so it offsets that period's 2200/1300 in the period
   trial balance); once filed, the report reads the sealed snapshot from `tax_filing`, not the
   now-cleared live GL.

3. **A `tax_filing` seal makes filing idempotent** (V31): `UNIQUE (company_id, period, tax_type)`, a
   per-`(company, period, PPN)` advisory lock taken before a `findByPeriodAndTaxType` probe, and the
   netting entry's `source_event_id = filing id` (UNIQUE on `journal_entry`) — the same lock + probe +
   UNIQUE idempotency the within-company close uses. A re-file is a clean no-op that posts nothing.

4. **Settlement pays a net-PAYABLE return through the clearing account** (`Dr VAT_PAYABLE / Cr
   CASH_CLEARING`), routing the payment through the same `1900` clearing every other cash movement
   uses — so a later bank reconciliation sweeps it and the cash cycle stays consistent. It is a
   one-shot FILED → SETTLED transition (guarded up-front + in the domain, UNIQUE `source_event_id`
   backstop), so it needs no `Idempotency-Key` (the bank-reconcile rationale). A CREDITABLE / zero-net
   return is terminal at FILED.

5. **e-Faktur is an illustrative CSV export, not a DJP integration.** `GET /api/v1/tax/vat/efaktur`
   returns the period's output tax invoices as JSON; the console renders the CSV download (client-side,
   like every other finance export). The real DJP e-Faktur API (certificates, NSFP tax-invoice serial
   numbers, the certified import schema) is deferred.

6. **`tax_type` leaves room for other taxes** (PPh etc.) without a schema change; this slice files
   `'PPN'` only.

## Consequences

- The balance sheet gains `2300 VAT Payable` (settled to zero on payment) and `1310 VAT Credit
  Carryforward`; the period's `2200`/`1300` draw down to zero on filing — all via the existing
  GL-derived statement readers. With AR + AP + Bank + Tax, accounting reaches ~80% of Odoo.
- New `AccountRole.VAT_PAYABLE` (→ 2300) / `VAT_CREDIT_CARRYFORWARD` (→ 1310), V31 (`tax_filing`) +
  V32 (COA + role maps). New `/api/v1/tax/**` gateway route (owner/manager). Console `features/tax/`.
- **SME gates (illustrative, must be confirmed before production PPN):** the 11% rate (lives as a
  constant in `InvoiceWriter`/`BillWriter`; a real regime needs a rate table + effective dates); the
  net-creditable policy (carryforward-to-1310 vs refund/restitusi, year-end handling); PKP status /
  threshold (assumed registered); the e-Faktur layout (illustrative, not the DJP schema); filing
  deadlines / penalties / the SPT Masa form; the `2300`/`1310` account codes.
- **Deliberate deferrals:** amended/revised returns (this slice snapshots at file time and does NOT
  block later postings to a sealed period); a net-void period (output/input VAT negative because voids
  exceeded issues) is rejected, not netted; PPh and other tax types; multi-rate / per-line tax codes;
  presentation-currency VAT; the real e-Faktur/DJP integration; the unified single-currency-GL guard
  across all producers (the standing AR/AP/Bank residual).
