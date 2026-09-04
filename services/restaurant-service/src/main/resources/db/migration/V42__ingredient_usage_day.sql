-- restaurant-service V42 — per-day ingredient USAGE aggregate (ADR 0050 follow-up, 2026-08-16).
-- "Stock terpakai hari itu": every per-sale recipe depletion (IngredientDepletionWriter) also
-- UPSERTs its quantities here, keyed (ingredient, date), so the stock-opname history can show how
-- much of each ingredient the day's sales consumed. Without this row the figure is IRRECOVERABLE —
-- depletion mutates ingredient.stock_qty in place, and a mid-day receive would corrupt any
-- back-derivation. A derived operational aggregate (no GL impact, no CDC-audit need beyond rule 4's
-- columns); usage accrues from this migration forward — prior days cannot be reconstructed.
--
-- usage_date is the OUTLET-LOCAL calendar day, derived in Asia/Jakarta by the writer (the v1
-- register business_date convention, ADR 0036 — a per-outlet timezone is the additive follow-up).
--
-- Every new table: 6 Auditable columns + ENABLE/FORCE ROW LEVEL SECURITY + a
-- <table>_tenant_isolation policy keyed to app.current_tenant, exactly as V11.

CREATE TABLE IF NOT EXISTS ingredient_usage_day (
    id            UUID   NOT NULL PRIMARY KEY,
    business_id   UUID   NOT NULL,
    ingredient_id UUID   NOT NULL REFERENCES ingredient (id) ON DELETE CASCADE,
    usage_date    DATE   NOT NULL,
    qty_used      BIGINT NOT NULL,

    -- Auditable (libs/tenant) — present on every Native table (rule 4).
    created_at   TIMESTAMPTZ  NOT NULL,
    created_by   VARCHAR(255) NOT NULL,
    updated_at   TIMESTAMPTZ  NOT NULL,
    updated_by   VARCHAR(255) NOT NULL,
    version      BIGINT       NOT NULL,
    company_id   VARCHAR(64)  NOT NULL,

    CONSTRAINT ck_ingredient_usage_day_qty CHECK (qty_used >= 0),
    -- The UPSERT conflict target: one row per ingredient per day (ids are globally unique UUIDs,
    -- so the pair needs no company/business discriminator).
    CONSTRAINT uq_ingredient_usage_day UNIQUE (ingredient_id, usage_date)
);

ALTER TABLE ingredient_usage_day ENABLE ROW LEVEL SECURITY;
ALTER TABLE ingredient_usage_day FORCE ROW LEVEL SECURITY;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_policies
         WHERE tablename = 'ingredient_usage_day'
           AND policyname = 'ingredient_usage_day_tenant_isolation'
    ) THEN
        EXECUTE $q$
            CREATE POLICY ingredient_usage_day_tenant_isolation ON ingredient_usage_day
                USING      (company_id = current_setting('app.current_tenant', true))
                WITH CHECK (company_id = current_setting('app.current_tenant', true))
        $q$;
    END IF;
END $$;

-- The read shape: an outlet's usage for one day (the opname sheet + riwayat detail).
CREATE INDEX IF NOT EXISTS idx_ingredient_usage_day_outlet_date
    ON ingredient_usage_day (company_id, business_id, usage_date);
