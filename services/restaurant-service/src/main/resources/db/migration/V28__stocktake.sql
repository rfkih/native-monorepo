-- restaurant-service V28 — daily close v2, part 4: inventory stocktake + item unit cost (ADR 0038
-- phase 3).
--
-- A physical stocktake at the daily close: the operator counts each tracked item, the system adjusts
-- stock to the count, and the valued variance posts as inventory shrinkage in finance. Two new
-- things: a per-item unit cost (to value the variance) and the stocktake record (parent + lines).
--
-- Money is minor units (BIGINT) + the company base currency (rule 8). Every new table: 6 Auditable
-- columns + ENABLE/FORCE ROW LEVEL SECURITY + a tenant-isolation policy (rules 4 + 5), as V21/V26.

-- ---------------------------------------------------------------------------
-- 1. menu_item.unit_cost_minor — the item's cost (for valuing stocktake variance)
-- ---------------------------------------------------------------------------
-- NULL = no cost recorded: the item is still counted at stocktake (its stock is adjusted) but its
-- variance posts NO ledger entry (shrinkage is only valued for cost-bearing items). Nullable, no
-- backfill needed (Postgres defaults an added nullable column to NULL without a rewrite).
ALTER TABLE menu_item
    ADD COLUMN unit_cost_minor BIGINT NULL;

-- ---------------------------------------------------------------------------
-- 2. stocktake — one physical count submission (parent)
-- ---------------------------------------------------------------------------
CREATE TABLE stocktake (
    id                    UUID         NOT NULL PRIMARY KEY,
    business_id           UUID         NOT NULL,
    counted_at            TIMESTAMPTZ  NOT NULL,
    currency              CHAR(3)      NOT NULL,
    -- SIGNED net valued shrinkage: Σ (system_qty − counted_qty) × unit_cost over cost-bearing lines.
    -- Positive = net loss (shrinkage), negative = net gain (found stock).
    shrinkage_minor       BIGINT       NOT NULL,
    idempotency_key       VARCHAR(64)  NOT NULL,

    -- Auditable (libs/tenant) — present on every Native table (rule 4).
    created_at            TIMESTAMPTZ  NOT NULL,
    created_by            VARCHAR(255) NOT NULL,
    updated_at            TIMESTAMPTZ  NOT NULL,
    updated_by            VARCHAR(255) NOT NULL,
    version               BIGINT       NOT NULL,
    company_id            VARCHAR(64)  NOT NULL,

    -- Producer idempotency: a retried submit resolves to the existing stocktake, no double-adjust.
    CONSTRAINT uq_stocktake_company_idempotency UNIQUE (company_id, idempotency_key)
);

ALTER TABLE stocktake ENABLE ROW LEVEL SECURITY;
ALTER TABLE stocktake FORCE ROW LEVEL SECURITY;

CREATE POLICY stocktake_tenant_isolation ON stocktake
    USING (company_id = current_setting('app.current_tenant', true))
    WITH CHECK (company_id = current_setting('app.current_tenant', true));

CREATE INDEX idx_stocktake_business ON stocktake (company_id, business_id, counted_at DESC);

-- ---------------------------------------------------------------------------
-- 3. stocktake_line — one counted item within a stocktake (audit trail)
-- ---------------------------------------------------------------------------
CREATE TABLE stocktake_line (
    id                    UUID         NOT NULL PRIMARY KEY,
    stocktake_id          UUID         NOT NULL REFERENCES stocktake (id),
    menu_item_id          UUID         NOT NULL,
    system_qty            INTEGER      NOT NULL,
    counted_qty           INTEGER      NOT NULL,
    -- counted_qty − system_qty (positive = found more, negative = shrinkage).
    variance_qty          INTEGER      NOT NULL,
    -- The item's unit cost at count time, or NULL when the item had no cost (no GL impact).
    unit_cost_minor       BIGINT       NULL,
    -- variance_qty × unit_cost_minor, or 0 when the item had no cost.
    variance_value_minor  BIGINT       NOT NULL,

    created_at            TIMESTAMPTZ  NOT NULL,
    created_by            VARCHAR(255) NOT NULL,
    updated_at            TIMESTAMPTZ  NOT NULL,
    updated_by            VARCHAR(255) NOT NULL,
    version               BIGINT       NOT NULL,
    company_id            VARCHAR(64)  NOT NULL,

    CONSTRAINT ck_stl_counted_nonneg CHECK (counted_qty >= 0),
    CONSTRAINT uq_stl_stocktake_item UNIQUE (stocktake_id, menu_item_id)
);

ALTER TABLE stocktake_line ENABLE ROW LEVEL SECURITY;
ALTER TABLE stocktake_line FORCE ROW LEVEL SECURITY;

CREATE POLICY stocktake_line_tenant_isolation ON stocktake_line
    USING (company_id = current_setting('app.current_tenant', true))
    WITH CHECK (company_id = current_setting('app.current_tenant', true));

CREATE INDEX idx_stl_stocktake ON stocktake_line (company_id, stocktake_id);
