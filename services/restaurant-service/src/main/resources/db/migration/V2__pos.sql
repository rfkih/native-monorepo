-- restaurant-service POS extension (ordering domain).
--
-- Three tables, mirroring V1's Auditable + ENABLE/FORCE RLS pattern exactly:
--
--   * `menu_item`        — the aggregate for a priced menu offering.
--   * `restaurant_order` — the order aggregate; holds lines and records a Sale on
--                          checkout so the total flows into finance via SaleRecorded.
--   * `order_line`       — per-item line within an order (company_id present for RLS).
--
-- Every table: 6 Auditable columns + ENABLE/FORCE ROW LEVEL SECURITY +
-- a <table>_tenant_isolation policy keyed to app.current_tenant, exactly as V1.

-- ---------------------------------------------------------------------------
-- menu_item aggregate
-- ---------------------------------------------------------------------------
CREATE TABLE menu_item (
    id           UUID         NOT NULL PRIMARY KEY,
    business_id  UUID         NOT NULL,
    name         VARCHAR(255) NOT NULL,
    category     VARCHAR(64)  NOT NULL,

    -- Money (libs/money) = integer minor units + ISO-4217 code. NEVER a float.
    price_minor  BIGINT       NOT NULL,
    currency     CHAR(3)      NOT NULL,

    active       BOOLEAN      NOT NULL DEFAULT TRUE,

    -- Auditable (libs/tenant) — present on every Native table.
    created_at   TIMESTAMPTZ  NOT NULL,
    created_by   VARCHAR(255) NOT NULL,
    updated_at   TIMESTAMPTZ  NOT NULL,
    updated_by   VARCHAR(255) NOT NULL,
    version      BIGINT       NOT NULL,
    company_id   VARCHAR(64)  NOT NULL
);

ALTER TABLE menu_item ENABLE ROW LEVEL SECURITY;
ALTER TABLE menu_item FORCE ROW LEVEL SECURITY;

CREATE POLICY menu_item_tenant_isolation ON menu_item
    USING (company_id = current_setting('app.current_tenant', true))
    WITH CHECK (company_id = current_setting('app.current_tenant', true));

CREATE INDEX idx_menu_item_business_active
    ON menu_item (company_id, business_id)
    WHERE active = TRUE;

-- ---------------------------------------------------------------------------
-- restaurant_order aggregate
-- ---------------------------------------------------------------------------
CREATE TABLE restaurant_order (
    id              UUID        NOT NULL PRIMARY KEY,
    business_id     UUID        NOT NULL,
    status          VARCHAR(16) NOT NULL,

    -- Order total as Money.
    total_minor     BIGINT      NOT NULL,
    currency        CHAR(3)     NOT NULL,

    -- Set after checkout records the Sale (nullable until then, null if not yet linked).
    sale_id         UUID        NULL,

    occurred_at     TIMESTAMPTZ NOT NULL,

    -- Producer idempotency: exactly one order per (company, client request id).
    idempotency_key TEXT        NOT NULL,

    -- Auditable
    created_at      TIMESTAMPTZ NOT NULL,
    created_by      VARCHAR(255) NOT NULL,
    updated_at      TIMESTAMPTZ NOT NULL,
    updated_by      VARCHAR(255) NOT NULL,
    version         BIGINT      NOT NULL,
    company_id      VARCHAR(64) NOT NULL,

    CONSTRAINT uq_order_company_idempotency UNIQUE (company_id, idempotency_key)
);

ALTER TABLE restaurant_order ENABLE ROW LEVEL SECURITY;
ALTER TABLE restaurant_order FORCE ROW LEVEL SECURITY;

CREATE POLICY restaurant_order_tenant_isolation ON restaurant_order
    USING (company_id = current_setting('app.current_tenant', true))
    WITH CHECK (company_id = current_setting('app.current_tenant', true));

-- ---------------------------------------------------------------------------
-- order_line (child of restaurant_order, still needs company_id for RLS)
-- ---------------------------------------------------------------------------
CREATE TABLE order_line (
    id                 UUID         NOT NULL PRIMARY KEY,
    order_id           UUID         NOT NULL
        REFERENCES restaurant_order (id),
    menu_item_id       UUID         NOT NULL,

    -- Snapshot of the menu item at the time of order (price may change later).
    name_snapshot      VARCHAR(255) NOT NULL,
    unit_price_minor   BIGINT       NOT NULL,
    qty                INT          NOT NULL,
    line_total_minor   BIGINT       NOT NULL,

    -- Auditable
    created_at         TIMESTAMPTZ  NOT NULL,
    created_by         VARCHAR(255) NOT NULL,
    updated_at         TIMESTAMPTZ  NOT NULL,
    updated_by         VARCHAR(255) NOT NULL,
    version            BIGINT       NOT NULL,

    -- company_id required so RLS applies; matches the parent order's company_id.
    company_id         VARCHAR(64)  NOT NULL
);

ALTER TABLE order_line ENABLE ROW LEVEL SECURITY;
ALTER TABLE order_line FORCE ROW LEVEL SECURITY;

CREATE POLICY order_line_tenant_isolation ON order_line
    USING (company_id = current_setting('app.current_tenant', true))
    WITH CHECK (company_id = current_setting('app.current_tenant', true));

CREATE INDEX idx_order_line_order_id ON order_line (order_id);
