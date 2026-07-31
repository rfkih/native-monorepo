-- restaurant-service V19 — Phase 6 of the POS-parity program (ADR 0029: self-order QR + customer
-- display). Anonymous diners scan a per-table/kiosk QR and can do exactly two things: read the
-- menu, and create a PARKED order tagged source=SELF_ORDER. Parking writes NO sale, NO payment, NO
-- outbox row, and NO stock movement — the worst an abuser can achieve is junk parked rows, never
-- money. The cashier confirms the order in the ParkedTray and takes payment through the normal,
-- fully-guarded flow.
--
-- Three pieces:
--   1. self_order_access — one row per (company, outlet, kid): the per-outlet HMAC secret (AES-256-
--      GCM encrypted at rest) a printed QR's token is signed with. Revocation = rotation: retire the
--      ACTIVE row, mint a new kid+secret. At most one ACTIVE row per (company_id, outlet_id).
--   2. restaurant_order gets `source` (POS | SELF_ORDER) and `table_label` (a snapshot string
--      carried by the token — independent of the table_id FK, since an anonymous QR scan is not
--      guaranteed to reference a formally-created restaurant_table row) + a composite index to serve
--      the unconfirmed-cap count (>= 50 -> 429) cheaply. The status CHECK gains EXPIRED — the
--      terminal, non-money-moving state the lazy sweep (30 min) moves a stale self-order PARKED row
--      to; findParkedViewsByBusinessId already filters status = 'PARKED', so an EXPIRED row silently
--      drops out of the ParkedTray.
--   3. entitlement_projection — the local `(company_id, module_key) -> entitled` read model this
--      service now maintains (its first entitlement-gated feature), fed by entitlement-service's
--      EntitlementGranted/Revoked (rule 2 — a cached read model, never a sync call). Backs the DB-
--      backed EntitlementLoader the shared libs/entitlement-check Redis cache consults on a miss, so
--      POST /api/v1/self-order/orders can be rejected 403 for a company not entitled to `self_order`.
--
-- Every new table: 6 Auditable columns + ENABLE/FORCE ROW LEVEL SECURITY + a <table>_tenant_isolation
-- policy (USING + WITH CHECK) keyed to app.current_tenant, exactly as V7/V14/V16/V17.

-- ---------------------------------------------------------------------------
-- 1. self_order_access — per-outlet QR signing secret
-- ---------------------------------------------------------------------------
CREATE TABLE self_order_access (
    id               UUID         NOT NULL PRIMARY KEY,
    outlet_id        UUID         NOT NULL,
    kid              VARCHAR(16)  NOT NULL,

    -- AES-256-GCM ciphertext (base64( IV || ciphertext+tag )) of the base64-encoded 32-byte secret
    -- the token's HMAC-SHA256 signature is keyed with. Never returned by any API after mint/rotate.
    secret_encrypted TEXT         NOT NULL,

    -- ACTIVE | RETIRED. A rotation retires the current ACTIVE row (kept for audit/history, never
    -- deleted) and inserts a fresh ACTIVE row with a new kid + secret — every printed QR signed by
    -- the retired secret stops verifying immediately (no expiry field needed).
    status           VARCHAR(16)  NOT NULL,

    -- Auditable (libs/tenant) — present on every Native table (rule 4).
    created_at       TIMESTAMPTZ  NOT NULL,
    created_by       VARCHAR(255) NOT NULL,
    updated_at       TIMESTAMPTZ  NOT NULL,
    updated_by       VARCHAR(255) NOT NULL,
    version          BIGINT       NOT NULL,
    company_id       VARCHAR(64)  NOT NULL,

    CONSTRAINT ck_self_order_access_status CHECK (status IN ('ACTIVE', 'RETIRED')),
    CONSTRAINT uq_self_order_access_company_outlet_kid UNIQUE (company_id, outlet_id, kid)
);

ALTER TABLE self_order_access ENABLE ROW LEVEL SECURITY;
ALTER TABLE self_order_access FORCE ROW LEVEL SECURITY;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_policies
         WHERE tablename = 'self_order_access'
           AND policyname = 'self_order_access_tenant_isolation'
    ) THEN
        EXECUTE $q$
            CREATE POLICY self_order_access_tenant_isolation ON self_order_access
                USING      (company_id = current_setting('app.current_tenant', true))
                WITH CHECK (company_id = current_setting('app.current_tenant', true))
        $q$;
    END IF;
END $$;

-- At most ONE ACTIVE row per (company_id, outlet_id) — the "which secret is live right now" lookup
-- the anonymous token filter and the management mint endpoint both rely on. A partial unique index
-- (rather than a plain unique constraint on (company_id, outlet_id)) so RETIRED history rows never
-- collide with each other or with the current ACTIVE row.
CREATE UNIQUE INDEX uq_self_order_access_one_active
    ON self_order_access (company_id, outlet_id)
    WHERE status = 'ACTIVE';

-- ---------------------------------------------------------------------------
-- 2. restaurant_order — source tag + table-label snapshot + the unconfirmed-cap index
-- ---------------------------------------------------------------------------
ALTER TABLE restaurant_order
    ADD COLUMN IF NOT EXISTS source VARCHAR(16) NOT NULL DEFAULT 'POS';

ALTER TABLE restaurant_order
    ADD COLUMN IF NOT EXISTS table_label VARCHAR(64) NULL;

ALTER TABLE restaurant_order
    DROP CONSTRAINT IF EXISTS ck_order_status,
    DROP CONSTRAINT IF EXISTS ck_order_source;

-- EXPIRED is the terminal state the 30-minute self-order sweep moves a stale PARKED SELF_ORDER row
-- to — non-money-moving (findParkedViewsByBusinessId filters status = 'PARKED', so it silently
-- drops out of the ParkedTray).
ALTER TABLE restaurant_order
    ADD CONSTRAINT ck_order_status
        CHECK (status IN ('PENDING', 'AWAITING_PAYMENT', 'COMPLETED', 'PARKED', 'EXPIRED'));

ALTER TABLE restaurant_order
    ADD CONSTRAINT ck_order_source
        CHECK (source IN ('POS', 'SELF_ORDER'));

-- Serves the unconfirmed-cap count cheaply: "how many PARKED SELF_ORDER rows does this outlet
-- currently have?" (>= 50 -> 429) and the lazy-sweep UPDATE (status = 'PARKED' AND source =
-- 'SELF_ORDER' AND occurred_at < :cutoff), both scoped by (company_id, business_id, status, source).
CREATE INDEX idx_restaurant_order_status_source
    ON restaurant_order (company_id, business_id, status, source);

-- ---------------------------------------------------------------------------
-- 3. entitlement_projection — local (company_id, module_key) -> entitled read model
-- ---------------------------------------------------------------------------
-- App-maintained (NOT Debezium-CDC-derived), fed by consuming entitlement-service's
-- EntitlementGranted/Revoked (rule 2 — no sync call). Mirrors barbershop-service /
-- carwash-service's identically-named table byte-for-byte.
CREATE TABLE entitlement_projection (
    id          UUID         NOT NULL PRIMARY KEY,
    module_key  VARCHAR(64)  NOT NULL,
    entitled    BOOLEAN      NOT NULL,

    -- Auditable (libs/tenant) — present on every Native table (rule 4).
    created_at  TIMESTAMPTZ  NOT NULL,
    created_by  VARCHAR(255) NOT NULL,
    updated_at  TIMESTAMPTZ  NOT NULL,
    updated_by  VARCHAR(255) NOT NULL,
    version     BIGINT       NOT NULL,
    company_id  VARCHAR(64)  NOT NULL,

    CONSTRAINT uq_entitlement_projection_company_module UNIQUE (company_id, module_key)
);

ALTER TABLE entitlement_projection ENABLE ROW LEVEL SECURITY;
ALTER TABLE entitlement_projection FORCE ROW LEVEL SECURITY;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_policies
         WHERE tablename = 'entitlement_projection'
           AND policyname = 'entitlement_projection_tenant_isolation'
    ) THEN
        EXECUTE $q$
            CREATE POLICY entitlement_projection_tenant_isolation ON entitlement_projection
                USING      (company_id = current_setting('app.current_tenant', true))
                WITH CHECK (company_id = current_setting('app.current_tenant', true))
        $q$;
    END IF;
END $$;
