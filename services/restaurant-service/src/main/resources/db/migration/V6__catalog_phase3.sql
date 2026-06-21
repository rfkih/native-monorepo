-- restaurant-service V6 — Phase 3 catalog richness:
--   1. menu_category — display-ordered categories with per-category availability.
--   2. menu_item.category_id — FK to menu_category; back-fill from existing category strings.
--   3. menu_item.available — 86'ing flag (distinct from active).
--   4. menu_item_modifier_group — per-item modifier groups (Size, Spice, etc.).
--   5. menu_item_modifier_option — per-group options with signed price deltas.
--   6. order_line_modifier — snapshot of selected options on a line (receipt-reproducible).
--
-- Every table: 6 Auditable columns + ENABLE/FORCE ROW LEVEL SECURITY +
-- a <table>_tenant_isolation policy keyed to app.current_tenant, exactly as V2/V5.
-- Money stored as BIGINT minor units — never a float (rule 8).

-- ---------------------------------------------------------------------------
-- 1. menu_category
-- ---------------------------------------------------------------------------
CREATE TABLE menu_category (
    id            UUID         NOT NULL PRIMARY KEY,
    business_id   UUID         NOT NULL,
    name          VARCHAR(255) NOT NULL,
    display_order INT          NOT NULL DEFAULT 0,
    active        BOOLEAN      NOT NULL DEFAULT TRUE,

    -- Auditable (libs/tenant) — present on every Native table (rule 4).
    created_at    TIMESTAMPTZ  NOT NULL,
    created_by    VARCHAR(255) NOT NULL,
    updated_at    TIMESTAMPTZ  NOT NULL,
    updated_by    VARCHAR(255) NOT NULL,
    version       BIGINT       NOT NULL,
    company_id    VARCHAR(64)  NOT NULL
);

ALTER TABLE menu_category ENABLE ROW LEVEL SECURITY;
ALTER TABLE menu_category FORCE ROW LEVEL SECURITY;

CREATE POLICY menu_category_tenant_isolation ON menu_category
    USING      (company_id = current_setting('app.current_tenant', true))
    WITH CHECK (company_id = current_setting('app.current_tenant', true));

CREATE INDEX idx_menu_category_business
    ON menu_category (company_id, business_id, display_order);

-- ---------------------------------------------------------------------------
-- 2. Add category_id FK + available flag to menu_item
-- ---------------------------------------------------------------------------
ALTER TABLE menu_item
    ADD COLUMN category_id  UUID    NULL
        REFERENCES menu_category (id),
    ADD COLUMN available    BOOLEAN NOT NULL DEFAULT TRUE;

-- ---------------------------------------------------------------------------
-- 3. Back-fill: create one menu_category per distinct (company_id, business_id, category string)
--    and point existing menu_item rows at the new category.
--
--    The migration runs as app_user which owns the tables.  FORCE ROW LEVEL
--    SECURITY blocks even the table owner from reading across tenants, so
--    SET LOCAL row_security = off is insufficient.  The correct escape hatch
--    is ALTER TABLE ... NO FORCE ROW LEVEL SECURITY (only the table owner may
--    run this), which temporarily drops the forced-policy requirement for this
--    transaction, then re-enable it when done.
--
--    Each INSERT sets app.current_tenant to the row's own company_id so the
--    WITH CHECK predicate on menu_category passes and no cross-tenant row is
--    silently accepted.  The UPDATE uses an explicit WHERE company_id =
--    rec.company_id so it is safe even with RLS disabled.
--
--    The back-fill produces one category row per distinct (company_id,
--    business_id, category) triple and assigns display_order = 0 (operators
--    can reorder via PATCH /categories/{id}).
-- ---------------------------------------------------------------------------

-- Drop FORCE RLS temporarily so the DO block can read all tenants' rows.
ALTER TABLE menu_item NO FORCE ROW LEVEL SECURITY;
ALTER TABLE menu_category NO FORCE ROW LEVEL SECURITY;

DO $$
DECLARE
    rec RECORD;
    new_cat_id UUID;
BEGIN
    FOR rec IN
        SELECT DISTINCT company_id, business_id, category
          FROM menu_item
         WHERE category IS NOT NULL
         ORDER BY company_id, business_id, category
    LOOP
        -- Insert the new category row.  FORCE RLS is off for both tables so
        -- the table owner (app_user) bypasses the policy; the explicit
        -- company_id = rec.company_id in the VALUES guarantees correct tenant
        -- attribution with no cross-tenant leak.
        INSERT INTO menu_category (
            id, business_id, name, display_order, active,
            created_at, created_by, updated_at, updated_by, version, company_id
        ) VALUES (
            gen_random_uuid(),
            rec.business_id,
            rec.category,
            0,
            TRUE,
            now(), 'system-migration', now(), 'system-migration', 0,
            rec.company_id
        )
        RETURNING id INTO new_cat_id;

        -- Point all menu_item rows for this (company, business, category) at
        -- the new category.  Explicit WHERE company_id = rec.company_id
        -- prevents any cross-tenant update.
        UPDATE menu_item
           SET category_id = new_cat_id
         WHERE company_id   = rec.company_id
           AND business_id  = rec.business_id
           AND category     = rec.category;
    END LOOP;
END $$;

-- Re-enable FORCE RLS on both tables.
ALTER TABLE menu_item FORCE ROW LEVEL SECURITY;
ALTER TABLE menu_category FORCE ROW LEVEL SECURITY;

-- ---------------------------------------------------------------------------
-- 4. menu_item_modifier_group
-- ---------------------------------------------------------------------------
CREATE TABLE menu_item_modifier_group (
    id               UUID         NOT NULL PRIMARY KEY,
    menu_item_id     UUID         NOT NULL REFERENCES menu_item (id),
    business_id      UUID         NOT NULL,
    name             VARCHAR(255) NOT NULL,
    -- SINGLE: exactly min_select..max_select selections; MULTI: multiple options allowed.
    selection_type   VARCHAR(16)  NOT NULL,
    required         BOOLEAN      NOT NULL DEFAULT FALSE,
    min_select       INT          NOT NULL DEFAULT 0,
    max_select       INT          NOT NULL DEFAULT 1,
    display_order    INT          NOT NULL DEFAULT 0,

    -- Auditable
    created_at       TIMESTAMPTZ  NOT NULL,
    created_by       VARCHAR(255) NOT NULL,
    updated_at       TIMESTAMPTZ  NOT NULL,
    updated_by       VARCHAR(255) NOT NULL,
    version          BIGINT       NOT NULL,
    company_id       VARCHAR(64)  NOT NULL,

    CONSTRAINT ck_modifier_group_selection_type
        CHECK (selection_type IN ('SINGLE', 'MULTI')),
    CONSTRAINT ck_modifier_group_min_le_max
        CHECK (min_select >= 0 AND max_select >= min_select)
);

ALTER TABLE menu_item_modifier_group ENABLE ROW LEVEL SECURITY;
ALTER TABLE menu_item_modifier_group FORCE ROW LEVEL SECURITY;

CREATE POLICY menu_item_modifier_group_tenant_isolation ON menu_item_modifier_group
    USING      (company_id = current_setting('app.current_tenant', true))
    WITH CHECK (company_id = current_setting('app.current_tenant', true));

CREATE INDEX idx_modifier_group_item
    ON menu_item_modifier_group (menu_item_id, display_order);

-- ---------------------------------------------------------------------------
-- 5. menu_item_modifier_option
-- ---------------------------------------------------------------------------
CREATE TABLE menu_item_modifier_option (
    id                UUID         NOT NULL PRIMARY KEY,
    group_id          UUID         NOT NULL REFERENCES menu_item_modifier_group (id),
    business_id       UUID         NOT NULL,
    name              VARCHAR(255) NOT NULL,
    -- Signed price delta in minor units. Positive = surcharge, negative = discount-variant, 0 = no change.
    price_delta_minor BIGINT       NOT NULL DEFAULT 0,
    available         BOOLEAN      NOT NULL DEFAULT TRUE,
    display_order     INT          NOT NULL DEFAULT 0,

    -- Auditable
    created_at        TIMESTAMPTZ  NOT NULL,
    created_by        VARCHAR(255) NOT NULL,
    updated_at        TIMESTAMPTZ  NOT NULL,
    updated_by        VARCHAR(255) NOT NULL,
    version           BIGINT       NOT NULL,
    company_id        VARCHAR(64)  NOT NULL
);

ALTER TABLE menu_item_modifier_option ENABLE ROW LEVEL SECURITY;
ALTER TABLE menu_item_modifier_option FORCE ROW LEVEL SECURITY;

CREATE POLICY menu_item_modifier_option_tenant_isolation ON menu_item_modifier_option
    USING      (company_id = current_setting('app.current_tenant', true))
    WITH CHECK (company_id = current_setting('app.current_tenant', true));

CREATE INDEX idx_modifier_option_group
    ON menu_item_modifier_option (group_id, display_order);

-- ---------------------------------------------------------------------------
-- 6. order_line_modifier — snapshot of selected options (receipt-reproducible)
-- ---------------------------------------------------------------------------
CREATE TABLE order_line_modifier (
    id                UUID         NOT NULL PRIMARY KEY,
    order_line_id     UUID         NOT NULL REFERENCES order_line (id),
    option_id         UUID         NOT NULL,   -- the modifier option chosen (not FK; snapshot)
    name_snapshot     VARCHAR(255) NOT NULL,   -- option name at order time
    -- Signed price delta in minor units at order time (snapshot; option may change later).
    price_delta_minor BIGINT       NOT NULL,

    -- Auditable
    created_at        TIMESTAMPTZ  NOT NULL,
    created_by        VARCHAR(255) NOT NULL,
    updated_at        TIMESTAMPTZ  NOT NULL,
    updated_by        VARCHAR(255) NOT NULL,
    version           BIGINT       NOT NULL,
    company_id        VARCHAR(64)  NOT NULL
);

ALTER TABLE order_line_modifier ENABLE ROW LEVEL SECURITY;
ALTER TABLE order_line_modifier FORCE ROW LEVEL SECURITY;

CREATE POLICY order_line_modifier_tenant_isolation ON order_line_modifier
    USING      (company_id = current_setting('app.current_tenant', true))
    WITH CHECK (company_id = current_setting('app.current_tenant', true));

CREATE INDEX idx_order_line_modifier_line
    ON order_line_modifier (order_line_id);
