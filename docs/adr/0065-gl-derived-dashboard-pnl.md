# 0065. Dashboard P&L is GL-derived — one definition of "Laba bersih"

- **Status:** Accepted
- **Date:** 2026-08-16
- **Deciders:** rifki, Claude (Fable)
- **Related:** [0014](0014-accounts-receivable-subledger.md) (AR does not feed the POS read models),
  [0036](0036-register-sessions-and-platform-channel-settlements.md) (register-close variance),
  [0038](0038-daily-close-all-tender-and-inventory.md), [0045](0045-qris-modes-and-payment-service.md)
  (QRIS fee), hard rule 8 (Money), rule 5 (RLS)

## Context

A production report (Bara Kebab): the beranda (dashboard) net profit differed from the Laba-Rugi
(income statement) net profit for the same month. Root cause, confirmed in code: the dashboard's
`GET /api/v1/pnl` read the `consolidated_pnl` **accumulator** read model, which is fed only by a
hand-picked set of POS writers (`SaleRecorded` net revenue, sale reversals, `ExpenseRecorded`,
employee expense claims, labor, stocktake shrinkage). The income statement (`GET
/api/v1/statements/income`) is DERIVED from the GL trial balance — every `journal_entry` with a
REVENUE/EXPENSE account. Every posting that hits the GL but never ran through a `consolidated_pnl`
writer therefore appears on the income statement but NOT the dashboard: register-close cash variance
(5700/4300), QRIS/acquirer MDR fee (5720), online-platform fees, bank fees/interest, depreciation,
asset disposal gain/loss, service-charge revenue (4020), and AR/AP (invoices/bills — [ADR 0014](0014-accounts-receivable-subledger.md)
already documents these deliberately skip the POS read models).

The accumulator-fed-by-selected-writers shape is the anti-pattern: every *new* posting writer must
remember to also feed the dashboard, and each one that forgets widens the gap silently. Industry
practice is the opposite — anything labelled "profit", including a dashboard tile, is computed from
the one ledger, so the summary can never disagree with the statement.

## Decision

`GET /api/v1/pnl` (the dashboard P&L) is now **GL-derived**: `PnlReader.pnlForPeriod` delegates to the
same `IncomeStatementReader` computation that backs `/api/v1/statements/income`, returning a new
immutable `pnl.domain.PnlFigures` carrier (revenue/expense/net + `usesIllustrativeRules`). The beranda
"Laba bersih" and the Laba-Rugi report are therefore equal **by construction**. The HTTP contract of
`PnlResponse` is unchanged (byte-identical fields, including the presentation-currency lens) — no
frontend change.

**Out of scope (retained unchanged):** the `consolidated_pnl` table and all its writers stay — it now
serves only the two write-path currency guards (`PnlReadModelWriter.requireConsistentCurrency`,
`LaborCostPostingWriter.divergentPeriodCurrency`), read via the new `PnlReader.accumulatedPnlForPeriod`.
Retiring the accumulator is a later cleanup. `/api/v1/pnl/outlets` and `/api/v1/revenue` are untouched.

## Consequences

- **One source of truth.** A future posting writer feeds the dashboard automatically because it goes
  through the journal; the "forgot to feed `consolidated_pnl`" class of divergence cannot recur.
- **Behaviour deltas (all improvements):** a multi-currency period is now `422`
  (`GlMultiCurrencyException`) instead of a read-time `500`; a period with GL entries that net to zero
  P&L returns `200` zeros (statement parity) instead of a possible `204`; `usesIllustrativeRules` is
  now the per-period `bool_or` over journal entries (still monotonic, append-only ledger).
- **Enforcement / tests:** `PnlMatchesIncomeStatementTest` seeds a sale + a register-close cash short
  (a GL-only posting) and asserts `pnl.net == income.net` and that the old accumulator missed it;
  the writer-acceptance suites now read `accumulatedPnlForPeriod`. ArchUnit is green — the
  `pnl.service → statements.service` edge is an allowed intra-layer service dependency.
- **Known follow-ups (pre-existing GL correctness, NOT introduced here — the unified number inherits
  them):** (1) payroll re-run (supersession) posts a dimensional + accumulator reversal but **no GL
  contra** (`LaborCostPostingWriter` `TODO(GL-labor-reversal)`), so the GL — and now the dashboard —
  overstates labor on a re-run; (2) labor accrues `Dr 6000 / Cr 6900` (both EXPENSE-typed), netting to
  zero on the income statement until `PayrollLiabilitiesPosted` clears 6900 (accrual-vs-liability
  timing). Both already affected the income statement; fixing them makes the shared number fully
  correct for labor and is deferred.
