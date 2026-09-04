-- restaurant-service V39 — sale breakdown snapshot: persist the POS price-breakdown fields already
-- computed at ring time onto `sale`, so the daily summary / Z-report can aggregate exact SQL sums
-- instead of re-deriving the breakdown from `restaurant_order` / `applied_promotion` / tax-rule
-- lookups after the fact.
--
-- These six columns mirror the SaleRecorded event's Phase 2/4 breakdown fields exactly (see
-- docs/EVENT-CATALOG.md "SaleRecorded" — Reconciliation identity, EXTENDED):
--   subtotal_minor - discount_minor - loyalty_redeemed_minor + service_charge_minor + tax_minor
--     == amount_minor
-- This is a REPORTING SNAPSHOT only — the breakdown is already computed once, by TaxChargeService /
-- the promotions/loyalty engines, at ring time and threaded onto the SaleRecorded event; nothing new
-- is computed here, we are only mirroring those already-computed values onto the row that produced
-- them so a same-service SQL SUM(...) over `sale` reproduces the same totals finance derives from the
-- event stream, without re-deriving anything.
--
-- All six columns are ADDITIVE and NULLABLE, no DEFAULT, no backfill — legacy/pre-migration rows
-- stay NULL. This matches the event's own documented fallback (EVENT-CATALOG.md: "finance falls back
-- to subtotal == amount" when subtotal_minor is null) — a reader here must apply the identical
-- fallback: treat a NULL subtotal_minor as amount_minor, and NULL discount/service_charge/tax/
-- loyalty_redeemed as 0, exactly as finance does. `sale` is FORCE-RLS (V1); no RLS or policy change
-- is needed here — the existing sale_tenant_isolation policy covers every column on the row,
-- including new ones, automatically (same reasoning as V33/V35). No backfilling UPDATE is issued
-- either, so the FORCE-RLS Flyway-UPDATE-matches-zero-rows trap (V21/V22 header) does not apply.
--
-- Index check (per task): the daily-aggregate access path is `WHERE business_id = ? AND occurred_at
-- >= ? AND occurred_at < ?` (scoped to the session tenant by RLS) — the SAME predicate shape
-- SaleRepository#findHistory already uses for "today's transactions". None of the three existing
-- indexes on `sale` covers it for an ALL-TENDER aggregate:
--   * idx_sale_cash_window (V21) / idx_sale_cash_collected_window (V22) are PARTIAL WHERE
--     tender_type = 'CASH' — they silently exclude every QRIS/CARD/ONLINE sale, wrong for a Z-report
--     that must total every tender.
--   * idx_sale_tender_window (V25) is (company_id, business_id, tender_type, occurred_at) — with
--     tender_type as a required EQUALITY column ahead of occurred_at, a query that does not filter on
--     tender_type cannot use occurred_at as an index bound (no skip-scan on PG16), so it degrades to
--     scanning every row the outlet has ever recorded.
-- No existing index covers the general predicate, so this migration adds one below:
-- `idx_sale_business_window`, a plain (non-partial) (company_id, business_id, occurred_at) index that
-- INCLUDEs the summed columns so the Z-report's SUM(...) is answered by an index-only scan.
ALTER TABLE sale
    ADD COLUMN subtotal_minor          BIGINT  NULL,
    ADD COLUMN discount_minor          BIGINT  NULL,
    ADD COLUMN service_charge_minor    BIGINT  NULL,
    ADD COLUMN tax_minor               BIGINT  NULL,
    ADD COLUMN uses_illustrative_rules BOOLEAN NULL,
    ADD COLUMN loyalty_redeemed_minor  BIGINT  NULL;

COMMENT ON COLUMN sale.subtotal_minor IS
    'Reporting snapshot of SaleRecorded.subtotal_minor: sum of order-line totals before discount/tax/service-charge, in minor units. NULL for legacy/pre-migration rows -- readers fall back to subtotal == amount_minor, matching finance''s documented fallback (EVENT-CATALOG.md).';

COMMENT ON COLUMN sale.discount_minor IS
    'Reporting snapshot of SaleRecorded.discount_minor: the order-level discount (collapsed sum of promo rules, at most one coupon, and any manual discount) in minor units. NULL treated as 0, matching finance''s fallback.';

COMMENT ON COLUMN sale.service_charge_minor IS
    'Reporting snapshot of SaleRecorded.service_charge_minor: the resolved service charge in minor units. NULL treated as 0, matching finance''s fallback.';

COMMENT ON COLUMN sale.tax_minor IS
    'Reporting snapshot of SaleRecorded.tax_minor: the resolved tax/PB1 amount in minor units. NULL treated as 0, matching finance''s fallback.';

COMMENT ON COLUMN sale.uses_illustrative_rules IS
    'Reporting snapshot of SaleRecorded.uses_illustrative_rules: true when the resolved tax/service-charge rule was provenance=ILLUSTRATIVE_PLACEHOLDER (ADR 0042). NULL (legacy row) is treated as false, matching finance''s fallback.';

COMMENT ON COLUMN sale.loyalty_redeemed_minor IS
    'Reporting snapshot of SaleRecorded.loyalty_redeemed_minor: the currency value of loyalty points redeemed on this sale, a contra-revenue deduction (ADR 0027) alongside discount_minor in the extended reconciliation identity. NULL treated as 0, matching finance''s fallback.';

-- Serves the daily summary / Z-report's all-tender aggregate and SaleRepository#findHistory's
-- "today's transactions" list: WHERE company_id = ? AND business_id = ? AND occurred_at in
-- [from, to). Non-partial (every tender_type, unlike V21/V22's CASH-only indexes) and not gated by
-- tender_type as a leading column (unlike V25's idx_sale_tender_window), so occurred_at is always a
-- true index bound. INCLUDEs every column the Z-report SUM(...) touches — the summed breakdown
-- columns, amount_minor/currency for the fallback, gift_card_redeemed_minor for the gift-card
-- settlement sum, AND tender_type so the `tender_type IS NOT NULL` filter is evaluated straight from
-- the index tuple — so the query is answered by a true index-only scan (no heap fetch for the
-- filter). (A brief ACCESS EXCLUSIVE lock during the non-concurrent build is acceptable at current
-- SME table sizes; revisit CREATE INDEX CONCURRENTLY as `sale` grows.)
CREATE INDEX idx_sale_business_window
    ON sale (company_id, business_id, occurred_at)
    INCLUDE (amount_minor, currency, tender_type, subtotal_minor, discount_minor,
             service_charge_minor, tax_minor, uses_illustrative_rules, loyalty_redeemed_minor,
             gift_card_redeemed_minor);
