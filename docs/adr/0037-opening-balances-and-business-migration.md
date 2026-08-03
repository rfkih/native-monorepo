# 37. Opening balances & business migration — the opening balance sheet + brought-forward assets

Date: 2026-08-03

## Status

Accepted

## Context

Native could open the books for a business that starts *from zero*, but not for one that
already exists — nor could it record the initial capital of a brand-new one. Two concrete
failures:

1. **Adding an owned asset drained cash.** `FixedAssetWriter.buildAcquisitionEntry` always posts
   `Dr FIXED_ASSET_COST (1500) / Cr CASH_CLEARING (1900)` — it models every asset as *bought now
   with cash*. Registering equipment a migrating business already owns drove the cash account
   negative.
2. **There was nowhere to put equity.** `chart_of_account` allowed an `EQUITY` type but **no equity
   account or role was seeded**, there was **no manual / opening journal-entry path** (every posting
   is event- or feature-driven), and company creation (org-service) recorded no financials.

An existing business joining a new ledger records a one-time **opening balance sheet** as of a
go-live date — it does *not* re-enter history. Prior accumulated profit enters as *retained
earnings*, not by replaying revenue and expense.

## Decision

1. **The opening journal** (`OpeningBalanceWriter`, an ad-hoc balanced entry — the acquire / bank /
   tax / disposal pattern, no Kafka event): one balanced `JournalEntry` touching **balance-sheet
   accounts only** (`ASSET` / `LIABILITY` / `EQUITY`). A line whose `chart_of_account.account_type`
   is `REVENUE` or `EXPENSE` is rejected (422 `opening-balance-pnl-account`) — prior profit is
   entered as a credit to Retained Earnings (3100), never by re-posting a P&L.

2. **Opening Balance Equity is the auto-plug.** The writer sums the caller's lines and appends ONE
   balancing line to `OPENING_BALANCE_EQUITY` (3900) — `Cr` when assets exceed liabilities + entered
   equity, `Dr` otherwise, omitted when the residual is zero. So the entry balances by construction:
   a user who does not know their equity figure still posts a valid balance sheet; a user who enters
   capital (3000) and retained earnings (3100) explicitly leaves 3900 at zero. 3900 is a standard
   clearing account (the QuickBooks / Xero *Opening Balance Equity*, Odoo's *Undistributed
   Profits/Losses*) that an SME reclassifies later — reclassification is out of scope here.

3. **A real `YYYY-MM` period, never a sentinel.** The entry posts into `periodOf(asOfDate)` (UTC).
   Statement and trial-balance queries filter `period <= :asOf` **lexicographically**; a sentinel
   like `"OPENING"` sorts *after* every real month (`'O' > '2'`) and would silently vanish from the
   balance sheet. `asOfDate` is `@PastOrPresent`; the go-live/day-before date is the caller's choice.

4. **Once-only per company.** A `company_opening_balance` row with `UNIQUE(company_id)` records that
   opening balances were posted (entry id, key, as-of, period, plug). A second attempt under a
   different key → 409 `opening-balances-already-recorded`; there is no amend/revise flow (the
   disposal one-shot precedent). **Idempotency-Key required** (400 keyless); same-key replay → 200
   with the original; a replayed key whose payload differs → 409
   `opening-balance-idempotency-key-conflict`. Guards: not a sealed period
   (`sealedPeriodExists`, 422) and consistent posting currency (`requireConsistentGlCurrency`, 422).

5. **Brought-forward (pre-owned) fixed assets** register WITHOUT crediting cash. Accumulated
   depreciation is derived (`Σ amortization_run_line.amount_minor`), not stored, and the monthly run
   spreads `depreciableBase = cost − salvage` over the full `useful_life_months` from `start_period`.
   So a new `fixed_asset.opening_accumulated_minor` column (+ `origin ACQUIRED|BROUGHT_FORWARD`, V47)
   carries the depreciation already taken before go-live, and:
   - `FixedAsset.depreciableBase() = cost − salvage − opening_accumulated` (unchanged for `ACQUIRED`,
     whose opening is 0) — the run depreciates only the **remaining** base over the **remaining**
     life; the asset is registered with `useful_life_months = remainingLifeMonths` and
     `start_period` = the next open period.
   - `registerBroughtForward` posts a **self-contained, cash-free** entry:
     `Dr FIXED_ASSET_COST (gross) / Cr ACCUMULATED_DEPRECIATION (opening_accumulated, omitted if 0) /
     Cr OPENING_BALANCE_EQUITY (net book value)`. Its net book value lands in 3900 alongside the main
     entry's plug, so total 3900 = total opening equity.
   - The register (`findRegister`) and disposal (`accumulatedFor`) add `opening_accumulated_minor` to
     the summed run lines. `requireDepreciationInStep` needs no change: a brought-forward asset's
     `start_period` is the first open period, so its posted-line count matches elapsed months.

6. **Equity accounts + roles are illustrative global reference data** (V46, `uses_illustrative=TRUE`,
   the V13/V35 pattern): `3000 Owner's / Share Capital`, `3100 Retained Earnings (prior years)`,
   `3900 Opening Balance Equity` (all `EQUITY`), plus `1100 Inventory` (`ASSET`) and `2700 Other
   Liabilities / Loans` (`LIABILITY`) so the console form has real targets; role maps
   `OPENING_BALANCE_EQUITY→3900`, `OWNER_CAPITAL→3000`, `RETAINED_EARNINGS→3100`. `BalanceSheetReader`
   already classifies `EQUITY` accounts credit-normal from `chart_of_account.account_type`, so posted
   equity flows through with no reader change (its synthetic current-year retained-earnings line under
   the literal `"3000-RETAINED-EARNINGS"` is a distinct string — no collision; a reconciliation test
   guards it).

## Consequences

- An existing business is migrated end-to-end: it enters cash, bank, receivables, payables, loans,
  inventory, owned fixed assets, and capital/retained earnings, and the balance sheet reads
  `assets = liabilities + equity` from the first posting. A new business records initial capital the
  same way (`Dr Cash / Cr Owner's Capital`). **Adding an owned asset never makes cash negative.**
- **SME-gated:** the equity account codes 3000/3100/3900 (and 1100/2700) are illustrative placeholders
  an SME replaces with a real chart + effective-dated maps; reclassifying the Opening Balance Equity
  balance into proper capital/retained accounts is left to the user/SME.
- **Cash-flow presentation nuance (not a break).** The exact-reconciliation invariant holds — a
  brought-forward asset books `Dr 1500 gross` (INVESTING) + `Cr 1590 opening` (OPERATING) + `Cr 3900
  net` (FINANCING), which net to zero cash. But the cash-flow statement for the *registration* month
  shows a gross-cost investing outflow with no offsetting proceeds line even though no cash was spent
  (the disposal reclass only fires in a disposal period). Opening entries are normally dated the day
  before the first operating period, so operating-period cash-flow statements exclude them; presenting
  the opening period specially is an SME/reporting refinement, not a rule violation.
- **Deferred:** opening AR/AP/inventory are GL *control* balances only — not per-invoice / per-bill /
  per-SKU open items (aging will not itemize opening receivables; restaurant stock is not seeded);
  a full open-item migration is a follow-up. Single currency per opening entry (matches
  `JournalEntry`); FX-denominated opening balances are a follow-up. Amend/revise of a recorded
  opening balance is not offered (once-only).
