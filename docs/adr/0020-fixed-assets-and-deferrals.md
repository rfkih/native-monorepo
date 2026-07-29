# 20. Fixed assets & deferrals — the amortization run

Date: 2026-07-30

## Status

Accepted

## Context

The final pillar of the Odoo accounting-parity program (Phases 1–5 = AR, AP, Bank, Tax, Cash-flow &
budgets — ADR 0014–0017, 0019; ~85% of Odoo). Nothing in the books could hold a **fixed asset**
(capex hit expense or sat in clearing) or spread an up-front payment/receipt over its months
(**prepaid expense / deferred revenue**). Phase 6 adds both, and with them the system's first
**time-based scheduled postings**: a monthly **amortization run** that posts every due
depreciation/amortization for a period, exactly once.

## Decision

1. **Fixed assets are a finance-local sub-ledger** (`fixed_asset` + the run tables, V34), RLS-scoped.
   Acquiring posts `Dr FIXED_ASSET_COST (1500) / Cr CASH_CLEARING (1900)` — capex through the same
   clearing account every cash movement uses (a bank reconciliation later sweeps it);
   `source_event_id = the asset id`. One shared cost/accumulated control pair (1500/1590) — the
   AR-1200/AP-2000 convention; per-asset-class accounts are deferred.

2. **Deferrals are the mirror pair** (`deferral`, V34): PREPAID_EXPENSE posts `Dr 1400 / Cr 1900` and
   amortizes monthly `Dr EXPENSE (5000) / Cr 1400`; DEFERRED_REVENUE posts `Dr 1900 / Cr 2400` and
   recognizes monthly `Dr 2400 / Cr REVENUE (4000)`.

3. **The amortization run is sealed once per `(company, period)`** — the tax-filing/within-close
   idempotency pattern verbatim: advisory lock → `findByPeriod` probe (no-op → 200 with the existing
   run) → post → seal (`uq_amortization_run`). Each due item posts its own ad-hoc balanced 2-leg
   entry (asset: `Dr DEPRECIATION_EXPENSE (5500) / Cr ACCUMULATED_DEPRECIATION (1590)`) keyed on its
   `amortization_run_line` id (UNIQUE `source_event_id` backstop), ALL in one transaction — no
   partial run. The run is user-triggered per month (like the tax filing); an automatic scheduler is
   deferred. No posting_template, no EventKind — the ad-hoc Bank/Tax path.

4. **Straight-line with exact-sum by cumulative rounding.** Month `k` of `N` posts
   `round(B·k/N) − round(B·(k−1)/N)` (single HALF_EVEN rounding per cumulative target,
   `Money.mulDiv`), so the schedule telescopes to exactly `B = cost − salvage` (assets) or the total
   (deferrals) — remainder minor units spread evenly, no drift, no last-month lump. A month that
   rounds to zero records its run line with no journal entry. Periods are independent (`k` from
   `monthsBetween`), so a missed month is caught up by simply running it — no ordering constraint.
   **Start convention (ILLUSTRATIVE):** assets start the month AFTER acquisition (full-month);
   deferrals start at their chosen month, inclusive.

5. **Book values come from the run-line sub-ledger** (register = cost − Σ line amounts per item;
   remaining = total − Σ), an RLS-scoped SUM — no per-item GL queries.

6. **Acquire / create-deferral are idempotent per `(company, Idempotency-Key)`** (code-review C-1 —
   they post money, so a retried request must replay the original, not double-post capex): the header
   is REQUIRED (keyless → 400), a replay returns 200 with the original resource, and
   `UNIQUE(company_id, idempotency_key)` (V34) is the DB backstop — the AR/AP payment pattern.
   **Backdating into an already-run month is rejected** (code-review W-1): a sealed run never
   re-runs, so an item starting at or before `MAX(run period)` could never fully amortize — the
   writers reject it with a clear 400. (Catch-up runs for not-yet-run past months remain allowed.)

7. **The cash-flow statement gains its INVESTING section**: `CashFlowReader` now resolves an
   investing account set from the `FIXED_ASSET_COST` role (mirroring the cash-role resolution, ADR
   0019) — capex classifies as investing while `ACCUMULATED_DEPRECIATION` (also ASSET-typed)
   deliberately stays in OPERATING as the classic non-cash add-back, and prepaid/deferred-revenue
   remain working capital. The exact reconciliation invariant is preserved.

## Consequences

- The balance sheet gains `1500` / `1590` (net book value) / `1400` / `2400`; the income statement
  gains `5500` and the monthly amortization effects; the cash-flow statement shows real INVESTING
  outflows. The accounting program is complete: **AR + AP + Bank + Tax + Cash-flow & budgets + Fixed
  assets & deferrals ≈ 90% of Odoo's accounting**, everything SME-gated where Indonesian law or a
  real COA is required.
- New endpoints `/api/v1/assets/**` (register, acquire, runs) + `/api/v1/deferrals/**`
  (owner/manager); console Fixed-assets + Deferrals pages; V34/V35; five new `AccountRole`s.
- **SME gates:** useful lives + salvage (user-entered, unvalidated vs Indonesian tax classes
  Kelompok 1–4); the month-after-acquisition convention; straight-line only; the COA codes;
  single-book (commercial=tax) depreciation.
- **Deliberate deferrals:** asset disposal/sale (gain/loss accounts — `status` reserves it);
  capitalizing from an AP bill; declining-balance; partial-month proration; revaluation/impairment;
  deferral early-termination; an automatic monthly scheduler.
