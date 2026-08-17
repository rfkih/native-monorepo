-- restaurant-service V43 — goods_receipt (ADR 0067 Phase B, §1): the durable anchor for a PRICED
-- goods receipt (a moving-average receive, ADR 0056). Two jobs:
--   (a) its id becomes the StockReceived event's receipt_id — finance's idempotency key
--       (journal_entry.source_event_id UNIQUE) for the Dr 1100 Inventory / Cr 2050 GRNI posting;
--   (b) it closes ADR 0056 accepted-limitation #1: today a duplicated priced receive (e.g. a
--       retried IngredientWriter#addStock call) double-adds moving-average value with nothing to
--       detect the repeat. The producer-wiring task (next task, NOT this one) will INSERT one row
--       here inside the SAME transaction as Ingredient#receive, using it to derive receipt_id for
--       the outbox event.
--
-- A REAL AGGREGATE TABLE, not an audit-derived read model — rule 4's CDC exception does not apply
-- here (contrast ingredient_usage_day, V42, which IS derived). These are the receipt FACTS (qty,
-- value, currency, when), independently meaningful even before any consumer or event exists. Money
-- is minor units (BIGINT) + an ISO-4217 currency code (rule 8) — never a float. Every new table: 6
-- Auditable columns + ENABLE/FORCE ROW LEVEL SECURITY + a <table>_tenant_isolation policy keyed to
-- app.current_tenant, exactly as V40/V42.
--
-- ---------------------------------------------------------------------------------------------
-- IDEMPOTENCY KEY DECISION (ADR 0067 §1's "closes ADR 0056 #1"):
-- ---------------------------------------------------------------------------------------------
-- Inspected IngredientWriter#addStock (2026-08-18): it has NO caller-supplied idempotency key
-- today. AddIngredientStockRequest carries only {amount, amountPaidMinor, costCurrency} — unlike
-- SubmitIngredientStocktakeRequest, which already threads an explicit idempotency key into
-- ingredient_stocktake (V31, uq_ingredient_stocktake_company_idempotency) and StocktakeService. So
-- this migration adds idempotency_key NULLABLE with a PARTIAL UNIQUE index on
-- (company_id, idempotency_key) — mirroring bill_payment's identical partial-unique idiom
-- (finance-service V27) — rather than a NOT NULL column this migration cannot honestly populate
-- (no key exists yet to backfill). The producer-wiring task adds a key to the request/service (a
-- client-generated request id, most likely) and starts populating this column on every priced
-- receive; until then every row's idempotency_key is NULL, and the partial index is inert (NULLs
-- are never equal to one another under a UNIQUE index, so any number of not-yet-keyed rows coexist
-- without a false rejection of today's unkeyed calls).
-- ---------------------------------------------------------------------------------------------

CREATE TABLE goods_receipt (
    id              UUID         NOT NULL PRIMARY KEY,
    ingredient_id   UUID         NOT NULL REFERENCES ingredient (id),
    business_id     UUID         NOT NULL,
    -- Units received, in the ingredient's own base unit — BIGINT to mirror the StockReceived event's
    -- qty:long field (ADR 0067 §1); still an integer count (ADR 0046 decimal ban), never a fraction.
    qty             BIGINT       NOT NULL,
    -- EXACT total paid for this receipt, minor units — the value added to the moving-average bucket
    -- (Ingredient#receive). Never a float (rule 8).
    value_minor     BIGINT       NOT NULL,
    currency        CHAR(3)      NOT NULL,
    received_at     TIMESTAMPTZ  NOT NULL,

    -- NULLABLE until the producer-wiring task populates it (see the IDEMPOTENCY KEY DECISION note
    -- above) — a caller-supplied key that, once present, prevents a duplicated priced receive from
    -- minting a second goods_receipt row (and therefore a second StockReceived event / GL posting).
    idempotency_key VARCHAR(64)  NULL,

    -- Auditable (libs/tenant) — present on every Native table (rule 4).
    created_at      TIMESTAMPTZ  NOT NULL,
    created_by      VARCHAR(255) NOT NULL,
    updated_at      TIMESTAMPTZ  NOT NULL,
    updated_by      VARCHAR(255) NOT NULL,
    version         BIGINT       NOT NULL,
    company_id      VARCHAR(64)  NOT NULL,

    -- Mirrors Ingredient#receive's own validation (addedQty > 0, amountPaidMinor >= 0) — a receipt
    -- row can never encode a receive the aggregate itself would have rejected.
    CONSTRAINT ck_goods_receipt_qty_positive CHECK (qty > 0),
    CONSTRAINT ck_goods_receipt_value_nonneg CHECK (value_minor >= 0)
);

ALTER TABLE goods_receipt ENABLE ROW LEVEL SECURITY;
ALTER TABLE goods_receipt FORCE ROW LEVEL SECURITY;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_policies
         WHERE tablename = 'goods_receipt'
           AND policyname = 'goods_receipt_tenant_isolation'
    ) THEN
        EXECUTE $q$
            CREATE POLICY goods_receipt_tenant_isolation ON goods_receipt
                USING      (company_id = current_setting('app.current_tenant', true))
                WITH CHECK (company_id = current_setting('app.current_tenant', true))
        $q$;
    END IF;
END $$;

-- Idempotency (closes ADR 0056 #1 once the producer starts supplying a key — see the note above): a
-- retried addStock call carrying the SAME key for the SAME tenant resolves to the existing row
-- instead of minting a second one. Partial because the column is nullable until the producer wiring
-- lands.
CREATE UNIQUE INDEX uq_goods_receipt_company_idempotency
    ON goods_receipt (company_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;

-- The two read paths this table exists to serve: per-ingredient receipt history/traceability (ADR
-- 0067 §1: "ingredient_id ... traceability; finance ... does not key GL on it" — restaurant is the
-- traceability owner) and per-outlet receipt history (audit), both newest-first — the same shape as
-- V42's idx_ingredient_usage_day_outlet_date.
CREATE INDEX idx_goods_receipt_ingredient
    ON goods_receipt (company_id, ingredient_id, received_at DESC);
CREATE INDEX idx_goods_receipt_business
    ON goods_receipt (company_id, business_id, received_at DESC);
