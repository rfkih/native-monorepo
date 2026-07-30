# 22. Fixed-asset disposal — gain/loss on disposal (other income)

Date: 2026-07-30

## Status

Accepted

## Context

The accounting program covered acquire + monthly depreciation (ADR 0020) but not selling or
scrapping an asset — V35 note E deferred it. Without a disposal flow the only outlets were wrong
ones: ringing the sale as POS revenue (overstates operating revenue AND leaves the asset plus its
accumulated depreciation on the balance sheet forever) or not booking it at all. Disposal income is
**other income**, presented below operating results, and the asset must leave the books.

## Decision

1. **The posting** (`AssetDisposalWriter`, ad-hoc balanced entry — the acquire/bank/tax pattern, no
   Kafka event): `Dr CASH_CLEARING (1900)` proceeds (omitted when 0 — scrap/write-off) + `Dr
   ACCUMULATED_DEPRECIATION (1590)` accumulated (omitted when 0) / `Cr FIXED_ASSET_COST (1500)`
   cost, plugged with `Cr GAIN_ON_DISPOSAL (4200, REVENUE)` when `proceeds − (cost − accumulated) >
   0`, `Dr LOSS_ON_DISPOSAL (5600, EXPENSE)` when negative, no plug at exactly book value. New
   roles + illustrative accounts seeded in V36 (4200/5600 verified collision-free).

2. **The entry posts into the CURRENT period** (`periodOf(now)`) — the acquisition precedent. The
   user's `disposalDate` is metadata (must be `>= acquisitionDate`): backdating the posting would
   restate closed/VAT-filed periods (the close snapshot is published to groups and never re-emits)
   and can even produce a negative 1500 when the acquisition entry itself posted later than its
   acquisition date. `disposal_period` on the row records the POSTING period.

3. **Depreciation must be in step** (409 `asset-depreciation-behind` otherwise), enforced
   per-asset because runs may skip months (periods are independent; company-wide `MAX(period)`
   proves nothing): (a) the asset's posted run-line COUNT must equal the months due strictly
   before the posting period (exact — a zero-amount month still writes a line); (b) no run line may
   exist in or after the posting period (the disposal month gets no depreciation — SME-gated
   convention; an already-posted later month would need a reversal, which this slice does not do).

4. **The disposal facts are FROZEN onto `fixed_asset`** (date, posting period, proceeds,
   `accumulated_disposed_minor`, entry id, Idempotency-Key; V36 all-or-nothing CHECK). A DISPOSED
   asset is excluded from every future run (`findByStatus(ACTIVE)`), so the frozen amounts never
   drift. **Serialization:** both `AssetDisposalWriter` and `AmortizationRunWriter` take the shared
   `company:ASSET_POSTING` advisory lock FIRST (same order → no deadlock) — a dispose can never
   interleave with a run posting the same asset's depreciation, which would desync the frozen
   amounts from the posted legs.

5. **Idempotency**: `Idempotency-Key` required (400 keyless); replay via
   `disposal_idempotency_key` + partial `UNIQUE(company, key)` → 200 posting nothing; a second
   attempt under a different key → 409 `asset-already-disposed` (one-shot, no reversal flow);
   `source_event_id = UUIDv3(assetId + ":DISPOSE")` makes the `journal_entry` UNIQUE a DB-level
   one-disposal-per-asset backstop. `POST /api/v1/assets/{id}/dispose` → 201 fresh / 200 replay.

6. **Cash-flow reclassification** (`CashFlowReader`): the trial balance merges acquisitions,
   depreciation, and disposals into the same 1500/1590 rows, so a disposal period is reclassified
   from the frozen columns (aggregated per `disposal_period`, RLS-scoped): the 1500 line drops the
   disposal credits (pure capex), ONE synthetic `DISPOSAL_PROCEEDS` investing inflow is emitted
   (console localizes the label), the 1590 line drops the disposal debits (pure depreciation
   add-back), and the P&L gain/loss is backed out of operating as GROSS lines on 4200/5600
   (mixed-sign periods reclass line-for-line against the income statement). The five adjustments
   net to zero, so the exact-reconciliation assert still closes — and now genuinely cross-checks
   the frozen aggregates against the posted legs. Income statement + balance sheet need no reader
   change (4200/5600 flow via `account_type`; zeroed 1500/1590 drop off via the HAVING clause);
   the register shows DISPOSED assets with zero book value + proceeds/date.

## Consequences

- Selling an asset now books correctly in two clicks (register → Dispose → date + price): gain/loss
  as other income, proceeds under INVESTING, the asset off the books — never POS revenue.
- The income statement lists 4200/5600 beside other revenue/expense lines — operating-vs-other
  sectioning remains deferred (same as interest income, ADR 0016).
- **SME-gated:** the no-depreciation-in-disposal-month convention; 4200/5600 as placeholders; VAT
  on the sale of business assets (UU PPN 16D) is NOT computed — proceeds post gross (V36 note C).
- **Deferred:** disposal reversal/amendment; partial disposal; disposing a deferral early; posting
  proceeds to a specific bank account (they land in 1900 and reconcile like every cash movement).
