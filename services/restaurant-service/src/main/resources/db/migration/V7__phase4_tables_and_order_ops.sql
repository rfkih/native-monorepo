-- restaurant-service V7 — Phase 4: order-type/table-id on orders, restaurant_table CRUD,
-- hold/park (PARKED status) support.
--
-- Every new table: 6 Auditable columns + ENABLE/FORCE ROW LEVEL SECURITY +
-- a <table>_tenant_isolation policy keyed to app.current_tenant, exactly as V2/V6.
--
-- Changes to restaurant_order:
--   - order_type VARCHAR(16)  CHECK (DINE_IN, TAKEAWAY, DELIVERY)  DEFAULT 'DINE_IN'
--   - table_id   UUID NULL   references restaurant_table
--   - status CHECK extended to include PARKED

-- ---------------------------------------------------------------------------
-- 1. restaurant_table — seating plan for dine-in orders
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS restaurant_table (
    id           UUID         NOT NULL PRIMARY KEY,
    business_id  UUID         NOT NULL,
    label        VARCHAR(64)  NOT NULL,
    capacity     INT          NOT NULL DEFAULT 2,
    area         VARCHAR(128) NULL,
    active       BOOLEAN      NOT NULL DEFAULT TRUE,

    -- Auditable (libs/tenant) — present on every Native table (rule 4).
    created_at   TIMESTAMPTZ  NOT NULL,
    created_by   VARCHAR(255) NOT NULL,
    updated_at   TIMESTAMPTZ  NOT NULL,
    updated_by   VARCHAR(255) NOT NULL,
    version      BIGINT       NOT NULL,
    company_id   VARCHAR(64)  NOT NULL,

    CONSTRAINT uq_table_company_business_label UNIQUE (company_id, business_id, label)
);

ALTER TABLE restaurant_table ENABLE ROW LEVEL SECURITY;
ALTER TABLE restaurant_table FORCE ROW LEVEL SECURITY;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_policies
         WHERE tablename = 'restaurant_table'
           AND policyname = 'restaurant_table_tenant_isolation'
    ) THEN
        EXECUTE $q$
            CREATE POLICY restaurant_table_tenant_isolation ON restaurant_table
                USING      (company_id = current_setting('app.current_tenant', true))
                WITH CHECK (company_id = current_setting('app.current_tenant', true))
        $q$;
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_restaurant_table_business
    ON restaurant_table (company_id, business_id)
    WHERE active = TRUE;

-- ---------------------------------------------------------------------------
-- 2. Extend restaurant_order
-- ---------------------------------------------------------------------------

-- Drop any existing status / type check constraints so we can replace them.
-- Covers: auto-named inline constraint from V2, plus our named version if partially applied.
ALTER TABLE restaurant_order
    DROP CONSTRAINT IF EXISTS restaurant_order_status_check,
    DROP CONSTRAINT IF EXISTS ck_order_status,
    DROP CONSTRAINT IF EXISTS ck_order_type;

-- Add order_type column if not present.
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
         WHERE table_name = 'restaurant_order' AND column_name = 'order_type'
    ) THEN
        ALTER TABLE restaurant_order
            ADD COLUMN order_type VARCHAR(16) NOT NULL DEFAULT 'DINE_IN';
    END IF;
END $$;

-- Add table_id column if not present.
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
         WHERE table_name = 'restaurant_order' AND column_name = 'table_id'
    ) THEN
        ALTER TABLE restaurant_order
            ADD COLUMN table_id UUID NULL
                REFERENCES restaurant_table (id);
    END IF;
END $$;

-- Add the status constraint covering all statuses including PARKED.
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.table_constraints
         WHERE table_name = 'restaurant_order' AND constraint_name = 'ck_order_status'
    ) THEN
        ALTER TABLE restaurant_order
            ADD CONSTRAINT ck_order_status
                CHECK (status IN ('PENDING', 'AWAITING_PAYMENT', 'COMPLETED', 'PARKED'));
    END IF;
END $$;

-- Add order_type constraint.
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.table_constraints
         WHERE table_name = 'restaurant_order' AND constraint_name = 'ck_order_type'
    ) THEN
        ALTER TABLE restaurant_order
            ADD CONSTRAINT ck_order_type
                CHECK (order_type IN ('DINE_IN', 'TAKEAWAY', 'DELIVERY'));
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_restaurant_order_status_business
    ON restaurant_order (company_id, business_id, status);
