-- restaurant-service V34 — recipe / bill-of-materials, phase A (ADR 0050): one table, recipe_line,
-- expresses how much of each ingredient a menu item consumes per sold portion, plus an optional
-- signed delta per modifier option (e.g. "extra cheese" adds +30 g of cheese, "no rice" subtracts the
-- base rice qty). A row with modifier_option_id NULL is a BASE line (qty_per_portion is the amount
-- consumed for the plain item, always positive); a row with modifier_option_id set is an OPTION DELTA
-- (qty_per_portion is signed and applied on top of the base lines only when that option is selected on
-- the sale, positive = consumes more, negative = consumes less). This mirrors V31's ingredient table:
-- menu_item_id and ingredient_id carry NO foreign key — recipe rows are validated at the service layer
-- against the catalog service owns (menu_item) and the ingredient table in this same schema, same
-- house precedent as ingredient_stocktake_line.ingredient_id having no FK. Quantities are INTEGER in
-- the ingredient's OWN unit (g / ml / pcs / pack, matching ingredient.unit) — the ArchUnit
-- entitiesHaveNoDecimalMoneyFields rule is a blanket field-type scan over every @Entity, so decimals
-- are banned on entities fleet-wide and an integer count is always honest at these unit granularities.
--
-- Depletion at sale time floors ingredient.stock_qty at 0 and NEVER blocks or fails a sale — a recipe
-- is a costing/inventory signal, not a sellability gate (that remains menu_item.stock_quantity / the
-- 86'd flag, untouched by this migration); this is enforced in the service layer, not by a DB
-- constraint, and is noted here for future readers wondering why there is no CHECK tying consumption
-- to available stock. Every new table: 6 Auditable columns + ENABLE/FORCE ROW LEVEL SECURITY + a
-- tenant-isolation policy (rules 4 + 5), as V31.

-- ---------------------------------------------------------------------------
-- 1. recipe_line — one (menu item, ingredient[, modifier option]) quantity
-- ---------------------------------------------------------------------------
CREATE TABLE recipe_line (
    id                   UUID         NOT NULL PRIMARY KEY,
    menu_item_id         UUID         NOT NULL,
    ingredient_id        UUID         NOT NULL,
    -- NULL = base recipe line; non-NULL = a signed per-option delta (see header).
    modifier_option_id   UUID         NULL,
    qty_per_portion      INTEGER      NOT NULL,
    business_id          UUID         NOT NULL,

    -- Auditable (libs/tenant) — present on every Native table (rule 4).
    created_at           TIMESTAMPTZ  NOT NULL,
    created_by           VARCHAR(255) NOT NULL,
    updated_at           TIMESTAMPTZ  NOT NULL,
    updated_by           VARCHAR(255) NOT NULL,
    version               BIGINT      NOT NULL,
    company_id            VARCHAR(64) NOT NULL,

    CONSTRAINT ck_recipe_line_qty
        CHECK ((modifier_option_id IS NULL AND qty_per_portion > 0)
            OR (modifier_option_id IS NOT NULL AND qty_per_portion <> 0))
);

ALTER TABLE recipe_line ENABLE ROW LEVEL SECURITY;
ALTER TABLE recipe_line FORCE ROW LEVEL SECURITY;

CREATE POLICY recipe_line_tenant_isolation ON recipe_line
    USING (company_id = current_setting('app.current_tenant', true))
    WITH CHECK (company_id = current_setting('app.current_tenant', true));

-- Plain UNIQUE treats NULL as distinct in Postgres, so the base-line uniqueness needs its own partial
-- index rather than folding modifier_option_id into one constraint.
CREATE UNIQUE INDEX uq_recipe_line_base
    ON recipe_line (company_id, menu_item_id, ingredient_id)
    WHERE modifier_option_id IS NULL;

CREATE UNIQUE INDEX uq_recipe_line_option
    ON recipe_line (company_id, menu_item_id, ingredient_id, modifier_option_id)
    WHERE modifier_option_id IS NOT NULL;

CREATE INDEX idx_recipe_line_item ON recipe_line (company_id, menu_item_id);

-- Serves the ingredient-deactivation reverse-lookup guard (is this ingredient used in any recipe?).
CREATE INDEX idx_recipe_line_ingredient ON recipe_line (company_id, ingredient_id);
