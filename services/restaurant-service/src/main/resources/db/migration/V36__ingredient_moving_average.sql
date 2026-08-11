-- restaurant-service V36 — ingredient moving-average costing: promote stock_value_minor to the
-- source of truth for a perpetual moving weighted-average cost, demoting ingredient.unit_cost_minor
-- (V31) to a derived DISPLAY CACHE = round(stock_value_minor / stock_qty). unit_cost_minor is left
-- untouched here (not dropped, not altered) — the write path recomputes it going forward; this
-- migration only adds and backfills the value column it will be derived from.
--
-- Money is minor units (BIGINT) + an ISO-4217 currency code (rule 8): stock_value_minor is the total
-- acquisition value, in minor units, of the stock currently on hand, denominated in ingredient's
-- existing cost_currency (V31) — no new currency column needed.

-- ---------------------------------------------------------------------------
-- 1. ingredient.stock_value_minor — additive column, default 0 (behavior-preserving for any row not
--    covered by the backfill below).
-- ---------------------------------------------------------------------------
ALTER TABLE ingredient ADD COLUMN stock_value_minor BIGINT NOT NULL DEFAULT 0;

-- ---------------------------------------------------------------------------
-- 2. Backfill — seed stock_value_minor from the existing (qty, unit_cost_minor) pair so no valuation
--    is lost for rows that were already costed and carrying stock. Uncosted rows and empty-stock rows
--    stay at the column default of 0 (explicit WHERE below for safety/clarity even though the default
--    already gives them 0).
--
-- THE RLS TRAP (why the NO FORCE / FORCE bracket). ingredient is FORCE ROW LEVEL SECURITY (V31),
-- policy-scoped by current_setting('app.current_tenant'). Flyway runs as the service's OWN
-- table-owning role, but FORCE makes the policy bind even the owner, and a migration session sets NO
-- app.current_tenant GUC — so current_setting('app.current_tenant', true) is NULL and the tenant
-- policy would filter EVERY row. A plain UPDATE here would therefore match ZERO rows across every
-- tenant, a silent empty backfill that Flyway reports as success (fleet precedent: restaurant-service
-- V6, org-service V7, finance-service V12). This backfill must reach every tenant's ingredient rows,
-- so the owner briefly drops FORCE, does the UPDATE, then restores FORCE — all inside this migration's
-- single transaction (PostgreSQL DDL is transactional: if anything here fails, the NO FORCE is rolled
-- back too, so the table is never left unprotected). FORCE is fully restored before the migration
-- commits.
-- ---------------------------------------------------------------------------
ALTER TABLE ingredient NO FORCE ROW LEVEL SECURITY;

UPDATE ingredient
   SET stock_value_minor = stock_qty * unit_cost_minor
 WHERE unit_cost_minor IS NOT NULL
   AND stock_qty > 0;

ALTER TABLE ingredient FORCE ROW LEVEL SECURITY;

-- ---------------------------------------------------------------------------
-- 3. Invariants — added AFTER the backfill so the table never transiently violates a constraint
--    (column default 0 + a backfill that only ever sets non-negative, cost-consistent values means
--    either order is actually safe here, but add-column -> backfill -> add-constraints is the
--    maximally-safe ordering and what we do fleet-wide).
-- ---------------------------------------------------------------------------
ALTER TABLE ingredient
    ADD CONSTRAINT ck_ingredient_stock_value_nonneg CHECK (stock_value_minor >= 0);

-- Uncosted stock (no cost_currency) carries no valuation.
ALTER TABLE ingredient
    ADD CONSTRAINT ck_ingredient_value_requires_cost
        CHECK (stock_value_minor = 0 OR cost_currency IS NOT NULL);

-- No stock on hand carries no valuation.
ALTER TABLE ingredient
    ADD CONSTRAINT ck_ingredient_value_requires_stock
        CHECK (stock_qty > 0 OR stock_value_minor = 0);
