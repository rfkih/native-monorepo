-- restaurant-service V11 — Bill/Tab feature: multiple independent guest bills per table.
--
-- A bill is a long-lived, table-bound, appendable, multi-round record of what one guest
-- ordered.  Paying a bill finalises it (records the sale → SaleRecorded outbox) and deducts
-- stock.  This is DISTINCT from the existing "park" (one-shot saved cart).
--
-- Every new table: 6 Auditable columns + ENABLE/FORCE ROW LEVEL SECURITY +
-- a <table>_tenant_isolation policy keyed to app.current_tenant, exactly as V2/V7.

-- ---------------------------------------------------------------------------
-- 1. bill — the open/paid/cancelled tab for one guest
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS bill (
    id              UUID          NOT NULL PRIMARY KEY,
    business_id     UUID          NOT NULL,
    table_id        UUID          NULL REFERENCES restaurant_table (id),
    guest_label     VARCHAR(128)  NOT NULL,
    status          VARCHAR(16)   NOT NULL DEFAULT 'OPEN',
    currency        CHAR(3)       NOT NULL,
    discount_minor  BIGINT        NULL,
    sale_id         UUID          NULL,

    -- Auditable (libs/tenant) — present on every Native table (rule 4).
    created_at   TIMESTAMPTZ  NOT NULL,
    created_by   VARCHAR(255) NOT NULL,
    updated_at   TIMESTAMPTZ  NOT NULL,
    updated_by   VARCHAR(255) NOT NULL,
    version      BIGINT       NOT NULL,
    company_id   VARCHAR(64)  NOT NULL,

    CONSTRAINT ck_bill_status CHECK (status IN ('OPEN', 'PAID', 'CANCELLED'))
);

ALTER TABLE bill ENABLE ROW LEVEL SECURITY;
ALTER TABLE bill FORCE ROW LEVEL SECURITY;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_policies
         WHERE tablename = 'bill'
           AND policyname = 'bill_tenant_isolation'
    ) THEN
        EXECUTE $q$
            CREATE POLICY bill_tenant_isolation ON bill
                USING      (company_id = current_setting('app.current_tenant', true))
                WITH CHECK (company_id = current_setting('app.current_tenant', true))
        $q$;
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_bill_business_status
    ON bill (company_id, business_id, status);

CREATE INDEX IF NOT EXISTS idx_bill_table_status
    ON bill (company_id, table_id, status)
    WHERE status = 'OPEN';

-- ---------------------------------------------------------------------------
-- 2. bill_line — one item round within a bill (splittable unit for Increment 3)
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS bill_line (
    id                  UUID          NOT NULL PRIMARY KEY,
    bill_id             UUID          NOT NULL REFERENCES bill (id),
    menu_item_id        UUID          NOT NULL,
    name_snapshot       VARCHAR(255)  NOT NULL,
    unit_price_minor    BIGINT        NOT NULL,
    modifier_delta_minor BIGINT       NOT NULL DEFAULT 0,
    qty                 INT           NOT NULL,
    line_total_minor    BIGINT        NOT NULL,

    -- Auditable
    created_at   TIMESTAMPTZ  NOT NULL,
    created_by   VARCHAR(255) NOT NULL,
    updated_at   TIMESTAMPTZ  NOT NULL,
    updated_by   VARCHAR(255) NOT NULL,
    version      BIGINT       NOT NULL,
    company_id   VARCHAR(64)  NOT NULL
);

ALTER TABLE bill_line ENABLE ROW LEVEL SECURITY;
ALTER TABLE bill_line FORCE ROW LEVEL SECURITY;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_policies
         WHERE tablename = 'bill_line'
           AND policyname = 'bill_line_tenant_isolation'
    ) THEN
        EXECUTE $q$
            CREATE POLICY bill_line_tenant_isolation ON bill_line
                USING      (company_id = current_setting('app.current_tenant', true))
                WITH CHECK (company_id = current_setting('app.current_tenant', true))
        $q$;
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_bill_line_bill
    ON bill_line (company_id, bill_id);

-- ---------------------------------------------------------------------------
-- 3. bill_line_modifier — modifier snapshot for one bill line
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS bill_line_modifier (
    id                  UUID          NOT NULL PRIMARY KEY,
    bill_line_id        UUID          NOT NULL REFERENCES bill_line (id),
    option_id           UUID          NOT NULL,
    name_snapshot       VARCHAR(255)  NOT NULL,
    price_delta_minor   BIGINT        NOT NULL DEFAULT 0,

    -- Auditable
    created_at   TIMESTAMPTZ  NOT NULL,
    created_by   VARCHAR(255) NOT NULL,
    updated_at   TIMESTAMPTZ  NOT NULL,
    updated_by   VARCHAR(255) NOT NULL,
    version      BIGINT       NOT NULL,
    company_id   VARCHAR(64)  NOT NULL
);

ALTER TABLE bill_line_modifier ENABLE ROW LEVEL SECURITY;
ALTER TABLE bill_line_modifier FORCE ROW LEVEL SECURITY;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_policies
         WHERE tablename = 'bill_line_modifier'
           AND policyname = 'bill_line_modifier_tenant_isolation'
    ) THEN
        EXECUTE $q$
            CREATE POLICY bill_line_modifier_tenant_isolation ON bill_line_modifier
                USING      (company_id = current_setting('app.current_tenant', true))
                WITH CHECK (company_id = current_setting('app.current_tenant', true))
        $q$;
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_bill_line_modifier_line
    ON bill_line_modifier (company_id, bill_line_id);
