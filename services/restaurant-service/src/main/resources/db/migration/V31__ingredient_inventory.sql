-- restaurant-service V31 — ingredient-level inventory, phase 1 (ADR 0046): a per-outlet raw-material
-- catalog (bahan: bread, patty, sauce, vegetables) + an ingredient stock opname (physical count),
-- reusing the existing StocktakeCompleted event (doc-only generalized in libs/contracts) for the
-- finance posting. Distinct from V28's stocktake, which counts TRACKED MENU ITEMS and is left
-- UNTOUCHED — both flows keep serving; menu_item.stock_quantity remains the sellable-portion /
-- sold-out (86) gate (ADR 0046).
--
-- Money is minor units (BIGINT) + an ISO-4217 currency code (rule 8). Quantities are INTEGER in the
-- ingredient's own unit — the ArchUnit entitiesHaveNoDecimalMoneyFields rule is a blanket field-type
-- scan over every @Entity, so a BigDecimal/float quantity would fail the build; the console's unit
-- picker ships g / ml / pcs / pack only, so an integer count is always honest. Every new table: 6
-- Auditable columns + ENABLE/FORCE ROW LEVEL SECURITY + a tenant-isolation policy (rules 4 + 5), as
-- V28.

-- ---------------------------------------------------------------------------
-- 1. ingredient — a per-outlet raw-material catalog row (bahan)
-- ---------------------------------------------------------------------------
CREATE TABLE ingredient (
    id                 UUID         NOT NULL PRIMARY KEY,
    business_id        UUID         NOT NULL,
    name               VARCHAR(255) NOT NULL,
    -- Opaque display text server-side (g / ml / pcs / pack on the console); no unit conversion here.
    unit               VARCHAR(16)  NOT NULL,
    -- ALWAYS tracked (unlike menu_item.stock_quantity, which is nullable/untracked) — there is no
    -- untracked-ingredient state (ADR 0046).
    stock_qty          INTEGER      NOT NULL DEFAULT 0,
    -- Optional cost pair: both present (costed — valued at stocktake) or both NULL (counted
    -- operationally only, no GL impact).
    unit_cost_minor    BIGINT       NULL,
    cost_currency      CHAR(3)      NULL,
    active             BOOLEAN      NOT NULL DEFAULT TRUE,

    -- Auditable (libs/tenant) — present on every Native table (rule 4).
    created_at         TIMESTAMPTZ  NOT NULL,
    created_by         VARCHAR(255) NOT NULL,
    updated_at         TIMESTAMPTZ  NOT NULL,
    updated_by         VARCHAR(255) NOT NULL,
    version            BIGINT       NOT NULL,
    company_id         VARCHAR(64)  NOT NULL,

    CONSTRAINT ck_ingredient_stock_nonneg CHECK (stock_qty >= 0),
    CONSTRAINT ck_ingredient_cost_both_or_neither
        CHECK ((unit_cost_minor IS NULL) = (cost_currency IS NULL))
);

ALTER TABLE ingredient ENABLE ROW LEVEL SECURITY;
ALTER TABLE ingredient FORCE ROW LEVEL SECURITY;

CREATE POLICY ingredient_tenant_isolation ON ingredient
    USING (company_id = current_setting('app.current_tenant', true))
    WITH CHECK (company_id = current_setting('app.current_tenant', true));

-- One active name per outlet (case-insensitive); a deactivated row frees its name for reuse.
CREATE UNIQUE INDEX uq_ingredient_company_business_name
    ON ingredient (company_id, business_id, lower(name))
    WHERE active;

CREATE INDEX idx_ingredient_business ON ingredient (company_id, business_id, active);

-- ---------------------------------------------------------------------------
-- 2. ingredient_stocktake — one physical ingredient count submission (parent)
-- ---------------------------------------------------------------------------
-- Clone of V28's stocktake with ONE deliberate difference: currency is NULLABLE. A count with ZERO
-- costed lines has nothing to value or post (ADR 0046) and carries no currency at all — the writer
-- skips the outbox entirely in that case.
CREATE TABLE ingredient_stocktake (
    id                    UUID         NOT NULL PRIMARY KEY,
    business_id           UUID         NOT NULL,
    counted_at            TIMESTAMPTZ  NOT NULL,
    currency              CHAR(3)      NULL,
    -- SIGNED net valued shrinkage, or 0 when currency IS NULL (nothing costed). Positive = net loss.
    shrinkage_minor       BIGINT       NOT NULL,
    idempotency_key       VARCHAR(64)  NOT NULL,

    created_at            TIMESTAMPTZ  NOT NULL,
    created_by            VARCHAR(255) NOT NULL,
    updated_at            TIMESTAMPTZ  NOT NULL,
    updated_by            VARCHAR(255) NOT NULL,
    version               BIGINT       NOT NULL,
    company_id            VARCHAR(64)  NOT NULL,

    -- Producer idempotency: a retried submit resolves to the existing count, no double-adjust.
    CONSTRAINT uq_ingredient_stocktake_company_idempotency UNIQUE (company_id, idempotency_key)
);

ALTER TABLE ingredient_stocktake ENABLE ROW LEVEL SECURITY;
ALTER TABLE ingredient_stocktake FORCE ROW LEVEL SECURITY;

CREATE POLICY ingredient_stocktake_tenant_isolation ON ingredient_stocktake
    USING (company_id = current_setting('app.current_tenant', true))
    WITH CHECK (company_id = current_setting('app.current_tenant', true));

CREATE INDEX idx_ingredient_stocktake_business
    ON ingredient_stocktake (company_id, business_id, counted_at DESC);

-- ---------------------------------------------------------------------------
-- 3. ingredient_stocktake_line — one counted ingredient within a count (audit trail)
-- ---------------------------------------------------------------------------
CREATE TABLE ingredient_stocktake_line (
    id                       UUID         NOT NULL PRIMARY KEY,
    ingredient_stocktake_id  UUID         NOT NULL REFERENCES ingredient_stocktake (id),
    ingredient_id            UUID         NOT NULL,
    system_qty               INTEGER      NOT NULL,
    counted_qty              INTEGER      NOT NULL,
    -- counted_qty − system_qty (positive = found more, negative = shrinkage).
    variance_qty             INTEGER      NOT NULL,
    -- The ingredient's unit cost at count time, or NULL when it carried no cost (no GL impact).
    unit_cost_minor          BIGINT       NULL,
    -- variance_qty × unit_cost_minor, or 0 when the ingredient had no cost.
    variance_value_minor     BIGINT       NOT NULL,

    created_at               TIMESTAMPTZ  NOT NULL,
    created_by                VARCHAR(255) NOT NULL,
    updated_at                TIMESTAMPTZ  NOT NULL,
    updated_by                 VARCHAR(255) NOT NULL,
    version                    BIGINT       NOT NULL,
    company_id                  VARCHAR(64)  NOT NULL,

    CONSTRAINT ck_istl_counted_nonneg CHECK (counted_qty >= 0),
    CONSTRAINT uq_istl_stocktake_ingredient UNIQUE (ingredient_stocktake_id, ingredient_id)
);

ALTER TABLE ingredient_stocktake_line ENABLE ROW LEVEL SECURITY;
ALTER TABLE ingredient_stocktake_line FORCE ROW LEVEL SECURITY;

CREATE POLICY ingredient_stocktake_line_tenant_isolation ON ingredient_stocktake_line
    USING (company_id = current_setting('app.current_tenant', true))
    WITH CHECK (company_id = current_setting('app.current_tenant', true));

CREATE INDEX idx_istl_stocktake ON ingredient_stocktake_line (company_id, ingredient_stocktake_id);
