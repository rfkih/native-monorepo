# 0060. Per-sale price-breakdown snapshot + POS daily summary (Z-report)

- **Status:** Accepted
- **Date:** 2026-08-14
- **Deciders:** Owner (rifki); domain-specialist (tax/reporting semantics); code + security review
- **Related:** [0036](0036-register-sessions-and-platform-channel-settlements.md) (register sessions /
  closing kasir), [0038](0038-daily-close-all-tender-and-inventory.md) (daily close v2 — per-tender
  expected), [0027](0027-loyalty-service-and-eventual-consistency-redemption.md) (loyalty contra-revenue),
  [0042](0042-go-live-official-coa-and-tax-rates.md) (illustrative vs official rates),
  [0039](0039-direct-thermal-printing-escpos.md) (ESC/POS printing), CLAUDE.md rule 8 (Money) / rule 9
  (i18n), `docs/EVENT-CATALOG.md` `SaleRecorded`

## Context

Owners want to **print today's transaction summary (a Z-report) when closing the till** — sales by
tender, transaction count, the price breakdown (gross / discount / service / tax), and the cash
reconciliation — on the same thermal printer receipts use.

The per-sale price breakdown (`subtotal`, `discount`, `serviceCharge`, `tax`, `usesIllustrativeRules`)
is **already computed once at ring time** by `TaxChargeService` + the promotions/loyalty engines and
threaded onto the `SaleRecorded` event — but restaurant-service **did not persist it on the `sale`
row** (the `order`/`payment` tables store only `total_minor` + a fixed `discount_minor`; the full
breakdown is otherwise re-derived on read). So an authoritative daily tax/discount total had no cheap,
exact source: re-deriving each order's breakdown at report time would duplicate the pricing +
promotion + loyalty logic, reconstruct consumed coupon state, mis-price a sale whose effective tax
rule changed mid-window, and drift by rounding (sum-of-rounded ≠ round-of-sum).

## Decision

**Snapshot, don't re-derive.** restaurant-service **`V39`** adds six nullable columns to `sale`
(`subtotal_minor`, `discount_minor`, `service_charge_minor`, `tax_minor`, `loyalty_redeemed_minor`,
`uses_illustrative_rules`) plus a covering index `idx_sale_business_window`. `SaleWriter` stamps them
at creation (before the first save; columns `updatable=false`) from the same `command.breakdown()` it
already puts on the event — `discount_minor` is decomposed to **promo-only** and `loyalty_redeemed_minor`
carried separately, byte-identical to `SaleRecordedSchema.toRecord`. Nothing new is computed; the sale
row, the `SaleRecorded` event, and the printed receipt now agree.

A new **session-scoped** endpoint `GET /api/v1/register-sessions/{id}/summary` aggregates those
snapshots over the session window `[openedAt, closedAt ?? now)` — exact SQL `SUM`s, each `COALESCE`'d
to 0, respecting per-sale rule versioning — and assembles them with the existing per-tender/cash-reconciliation
logic into one `RegisterSummaryResponse`. It works for an **OPEN** session (a live X-report) and a
**CLOSED** one (the final Z-report). The console prints it through the existing `ThermalReceipt`
pipeline (device print, `window.print()` fallback, reconnect/retry), reached from **both** the closing
sheet ("Cetak ringkasan" on the close form + the after-close verdict) and a till-menu entry
("Ringkasan hari ini").

Both decompositions of `total` foot. The **revenue** side: `gross − discount − loyalty + service +
tax == total`. The **settlement** side: the per-tender lines are **GROSS** sales (before refunds),
plus a 5th **gift-card** settlement line (`Σ sale.gift_card_redeemed_minor`, shown only when used),
so `Σ tenders == total`; the standalone refunds line then gives `net == total − refunds` (total is
gross of refunds). Presenting per-tender gross + an explicit refunds line — rather than per-tender net
with a second refunds deduction — is what makes the printed drawer-reconciliation document actually
add up (code-review W1/W2). The tax line is **PB1 ("Pajak Restoran"), not PPN**, and is
**reporting-only** — finance's ledger remains the authoritative statutory figure.

**Explicitly OUT of scope:** re-deriving pre-migration history (snapshot is **forward-only** — legacy
rows fall back to `subtotal == amount`, `tax == 0`, mirroring finance's documented fallback, so the
deploy-day report's tax line can be partial); `servicepos`/carwash entry points (their sales carry a
null breakdown); a day-scoped summary independent of a register session (the Z-report is per drawer
session); any GL/finance change (this reads restaurant-service's own rows only — no cross-service join,
rule 1).

## Consequences

- **Storage:** `V39` adds six additive **nullable** columns (no backfill → the FORCE-RLS
  Flyway-UPDATE-matches-zero-rows trap does not apply, [[rls-migration-backfill]]) covered by the
  existing `sale_tenant_isolation` policy, plus `idx_sale_business_window (company_id, business_id,
  occurred_at) INCLUDE (…)` — an index-only scan for the all-tender window aggregate (the CASH-only
  partial indexes and the tender-leading `idx_sale_tender_window` could not serve it).
- **Write path:** `SaleWriter.create` and `recordInCurrentTx` gain one guarded `stampBreakdown` call
  before save; a null breakdown (legacy `POST /sales`, carwash) is a no-op. `Math.*Exact` throughout
  the summary sums (no silent wrap).
- **Illustrative tax:** whenever any sale in the window used an `ILLUSTRATIVE_PLACEHOLDER` rule
  (`BOOL_OR`), the printed tax line is badged **"estimasi"** and the report carries a banner + a
  footer stating it is an internal management summary, **not a Faktur Pajak**. The seeded PB1 10% is
  illustrative until an accounting SME replaces it (ADR 0042).
- **Tenancy:** the summary query has no manual `company_id` — RLS auto-applies via the read-only
  transactional bean; the endpoint reuses `outletAccessGuard.enforce` like every other register route.
- **Enforced by:** the `Money` minor-unit discipline (rule 8), the snapshot-equals-event invariant
  (shared decomposition), both reconciliation identities (revenue + settlement, asserted in tests),
  and unit + Testcontainers tests (breakdown persistence, OPEN/CLOSED aggregation, gift-card-split
  footing, empty window, tenancy). Code + security review PASS.
- **Accepted limitations:** (1) forward-only coverage as above; (2) reporting figures still need
  accountant sign-off before being treated as book-truth (the elected PB1/PPN regime and rate remain
  SME-gated). *(The earlier gift-card-settlement footing gap is resolved: gift-card redemption is now
  its own settlement line so `Σ tenders == total` holds even on a gift-card split.)*
