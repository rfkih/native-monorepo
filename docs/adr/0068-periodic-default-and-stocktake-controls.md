# 0068. Inventory accounting policy — periodic-safe default, perpetual opt-in, and a stock-opname control

- **Status:** Accepted
- **Date:** 2026-08-19
- **Deciders:** rifki (owner) — chose "perpetual + controls (gold standard)" after a real prod incident; Claude (tech lead).
- **Related:** [0067](0067-perpetual-inventory-accounting.md) (perpetual — the gold-standard opt-in), [0056](0056-moving-average-inventory-cost.md) (moving-average cost), [0050](0050-recipes-bom-costing.md) (recipe depletion / HPP), [0038](0038-daily-close-all-tender-and-inventory.md) (introduced stocktake shrinkage → GL), [0065](0065-gl-derived-dashboard-pnl.md) (GL-derived P&L). Rules **5** (RLS), **8** (Money), **9** (i18n).

## Context — a real incident

On prod (tenant *Bara Kebab*, IDR), the dashboard P&L showed a **~Rp 160 juta phantom profit**. Root cause traced in the GL: a single stock opname on 2026-08-17 recorded `Daging Kebab TIS FOOD` (unit **gram**) at **counted 2,640,000 g (2.64 tonnes)** vs system 3,411 g — a unit/data-entry error (kg typed as g / extra zeros). At Rp 61/g that is a **+Rp 160,831,929** "inventory gain", posted `Dr 1100 Inventory / Cr 5800 Inventory-Shrinkage`. Because `5800` is an **expense** account, a giant credit there reads as **negative expense → inflated profit**, and `1100` inflated as a phantom asset.

Two lessons:

1. **A single count typo flows straight to profit** because stock-opname variance posts to the GL with no plausibility control. Best practice does not *disconnect* opname from the books (a real spoilage loss SHOULD reduce profit — the matching principle) — it *controls the count* (variance thresholds, review) so a typo never posts.
2. **The double-count / negative-`1100` problem** (the reason [ADR 0067](0067-perpetual-inventory-accounting.md) exists) is only correct once a tenant is on **perpetual** — which requires a per-tenant activation (opening balance + accountant sign-off). Until then, opname posting to the GL is unsafe.

The owner, shown the best-practice spectrum (expense-on-purchase → periodic → perpetual+COGS), chose **perpetual + controls (the gold standard)**.

## Decision

Adopt a three-part inventory-accounting policy:

### 1. Periodic-safe DEFAULT (the on-ramp) — stock opname does not post to the GL until a tenant is perpetual-active

Gate the finance `StocktakeWriter` on `PerpetualInventoryReader.isActiveFor(period)` — the SAME election [ADR 0067](0067-perpetual-inventory-accounting.md) introduced:

- **NOT perpetual-active (every tenant by default):** a **claimed no-op** — `processOnce` still records the `StocktakeCompleted` event (idempotency preserved), but **no** `Dr 5800 / Cr 1100` journal and **no** dimensional `ledger_posting` is written. Opname stays **operational-only** (restaurant-service still updates ingredient `stock_qty` so the owner sees on-hand levels; the HPP/margin display is unaffected). P&L is therefore `income − expense` (purchases expensed via bills → 5000), which a count typo cannot corrupt. This immediately removes the phantom-profit class for all non-activated tenants.
- **Perpetual-active (opt-in):** the existing `Dr 5800 / Cr 1100` posting is the **true-up** of a now-real `1100` ([ADR 0067](0067-perpetual-inventory-accounting.md) §4) — a genuine shrinkage/gain that correctly hits the P&L.

This mirrors the dormant-gate pattern already shipped for the `StockReceived` and `SaleCogsRecorded` consumers (ADR 0067 Phase B/C): no tenant's numbers change without an explicit activation.

### 2. Perpetual is the gold-standard OPT-IN (ADR 0067, already built)

Nothing new — a tenant that wants inventory-on-the-balance-sheet + COGS/margin in the books activates perpetual via `/settings/inventory` (owner-only, books an opening `1100`). Then opname is a true-up, purchases capitalize, sales post COGS.

### 3. A stock-opname CONTROL (the "controls" half of the gold standard) — variance-confirmation guard

Independent of periodic/perpetual, add a plausibility guard to the opname flow so a wildly-implausible count is caught **before** submit — the direct fix for the 2.64-tonne typo, protecting the operational stock + HPP **and** (once perpetual) the GL. Mirrors the register close-cash confirmation ([`closeGuard`](../../frontend/console/src/features/pos/lib/closeGuard.ts)):

- A pure, unit-tested decision (`stocktakeVarianceGuard`): flag a line when the counted quantity implies an **implausibly large variance** — e.g. the variance *value* exceeds a threshold, or `counted` exceeds `system` by more than a large factor (guarding the classic ×1000 g/kg slip). Tuned to catch order-of-magnitude slips, not normal shrinkage.
- Frontend: when any line trips the guard, a confirmation dialog ("This count is much larger/smaller than expected — check the amount") gates the submit; strings i18n EN + ID (rule 9).

## Consequences

- **The phantom-profit class is closed for the default (non-activated) tenants** the moment this ships: opname can no longer move the P&L, so a count typo is at worst an operational-stock error (further guarded by the control) — never a GL distortion.
- **No tenant's GL numbers change on deploy** — non-activated tenants simply stop *newly* posting opname to the GL (they were the only ones affected); the gate is behaviour-preserving for perpetual-active tenants (none today). This is a dormant-safe change like ADR 0067.
- **Existing already-posted opname entries are NOT auto-reversed by this ADR** (fix-forward, the project precedent). Correcting the *Bara Kebab* phantom (`Dr 5800 / Cr 1100` reversal, or a corrected recount) is a separate, owner-authorised prod data operation, gated on the owner's go-ahead.
- **Best-practice honesty:** making the *default* periodic is a deliberate simplification (no inventory asset, no COGS matching, lumpier profit) that is appropriate for micro-UMKM — especially those on **final PPh 0.5%**, where COGS is tax-irrelevant. Businesses that need accrual-accurate inventory opt into perpetual. The control guard applies to both, so operational stock/HPP quality is protected regardless.
- **Enforcement:** `Money`-only (rule 8); RLS-scoped consumer (rule 5); idempotent (`processOnce`); the guard decision is a pure tested helper; `/code-review` on the money gate.
