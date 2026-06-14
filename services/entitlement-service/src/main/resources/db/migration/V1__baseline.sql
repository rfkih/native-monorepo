-- entitlement-service baseline — owns module_catalog, tenant_entitlement, billing_line
-- (ARCHITECTURE.md §2), plus the transactional outbox and the idempotent-consumer dedupe store.
--
-- Tables:
--   * module_catalog     — the known modules (reference data; NOT tenant-scoped, NOT Auditable).
--                          Seeded with the base modules: restaurant, carwash, laundromat, hr, finance.
--   * tenant_entitlement — which modules a company has, one row per (company_id, module_key) with a
--                          status (ENTITLED|REVOKED) + granted_at/revoked_at. Auditable + RLS.
--   * billing_line       — a billed line per (company_id, module_key, period) with a Money amount
--                          (amount_minor BIGINT + currency CHAR(3), never a float). Auditable + RLS.
--   * outbox             — the transactional-outbox table libs/events OutboxWriter writes to; the
--                          EntitlementGranted/Revoked event row commits atomically with the
--                          entitlement change.
--   * processed_event    — the idempotent-consumer dedupe store (libs/events ProcessedEventStore):
--                          one row per handled event UUID, so a re-delivered CompanyCreated never
--                          double-grants.
--
-- The application connects as a NON-superuser role (without BYPASSRLS), so RLS is genuinely
-- enforced; FORCE ROW LEVEL SECURITY makes the policy apply even to the table owner / migration
-- role, so no connection silently bypasses tenant isolation (CLAUDE.md rule 5 — defense in depth).
-- The CONSUMER grants a new company's defaults inside a TenantContext scope bound to the event's
-- company_id, so the auto-RLS aspect sets app.current_tenant and the WITH CHECK passes on every
-- insert; the write endpoints run under the request's bound tenant.

-- ---------------------------------------------------------------------------
-- module_catalog (the known modules — reference data, global, not tenant-scoped)
-- ---------------------------------------------------------------------------
-- A catalog of modules the platform offers. It is global reference data (the same set for every
-- company), so it is deliberately NOT Auditable and NOT under RLS: it carries no company_id and is
-- not tenant business data. A tenant_entitlement / billing_line module_key is validated against it.
CREATE TABLE module_catalog (
    module_key  VARCHAR(64)  NOT NULL PRIMARY KEY,
    name        VARCHAR(255) NOT NULL,
    description VARCHAR(1024) NOT NULL
);

-- Seed the known base modules (the verticals + the platform modules). Idempotent on the PK.
INSERT INTO module_catalog (module_key, name, description) VALUES
    ('restaurant', 'Restaurant',  'Restaurant operations vertical (orders, sales, menu).'),
    ('carwash',    'Car Wash',    'Car wash operations vertical (washes, bays, upsells).'),
    ('laundromat', 'Laundromat',  'Laundromat operations vertical (cycles, kilo logs).'),
    ('hr',         'HR',          'HR / employee module (records, contracts, payroll, leave).'),
    ('finance',    'Finance',     'Financial core (ledger, consolidation, read models).');

-- ---------------------------------------------------------------------------
-- tenant_entitlement (which modules a company has)
-- ---------------------------------------------------------------------------
CREATE TABLE tenant_entitlement (
    id          UUID         NOT NULL PRIMARY KEY,

    -- The module this entitlement is for; validated against module_catalog at the service layer.
    module_key  VARCHAR(64)  NOT NULL,

    -- ENTITLED | REVOKED. A revoke flips the status (kept as a row, not deleted, so the history and
    -- the unique (company_id, module_key) survive across grant/revoke/re-grant cycles).
    status      VARCHAR(16)  NOT NULL,

    -- When the module was granted / last revoked (UTC). revoked_at is NULL while ENTITLED.
    granted_at  TIMESTAMPTZ  NOT NULL,
    revoked_at  TIMESTAMPTZ  NULL,

    -- Auditable (libs/tenant) — present on every Native table.
    created_at  TIMESTAMPTZ  NOT NULL,
    created_by  VARCHAR(255) NOT NULL,
    updated_at  TIMESTAMPTZ  NOT NULL,
    updated_by  VARCHAR(255) NOT NULL,
    version     BIGINT       NOT NULL,
    company_id  VARCHAR(64)  NOT NULL,

    -- At most one entitlement row per company + module; the grant/revoke flow upserts this key.
    CONSTRAINT uq_tenant_entitlement_company_module UNIQUE (company_id, module_key)
);

ALTER TABLE tenant_entitlement ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenant_entitlement FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_entitlement_tenant_isolation ON tenant_entitlement
    USING (company_id = current_setting('app.current_tenant', true))
    WITH CHECK (company_id = current_setting('app.current_tenant', true));

-- The "modules for this company" read scans by company within the bound tenant; the unique
-- constraint above already provides a (company_id, module_key) index for the point lookup.

-- ---------------------------------------------------------------------------
-- billing_line (a billed line per company + module + period)
-- ---------------------------------------------------------------------------
CREATE TABLE billing_line (
    id           UUID         NOT NULL PRIMARY KEY,

    module_key   VARCHAR(64)  NOT NULL,

    -- The billing period: YYYY-MM. One charge per company + module + period.
    period       VARCHAR(7)   NOT NULL,

    -- Money (libs/money) = integer minor units + ISO-4217 code. NEVER a float.
    amount_minor BIGINT       NOT NULL,
    currency     CHAR(3)      NOT NULL,

    -- Auditable (libs/tenant) — present on every Native table.
    created_at   TIMESTAMPTZ  NOT NULL,
    created_by   VARCHAR(255) NOT NULL,
    updated_at   TIMESTAMPTZ  NOT NULL,
    updated_by   VARCHAR(255) NOT NULL,
    version      BIGINT       NOT NULL,
    company_id   VARCHAR(64)  NOT NULL,

    CONSTRAINT uq_billing_line_company_module_period UNIQUE (company_id, module_key, period)
);

ALTER TABLE billing_line ENABLE ROW LEVEL SECURITY;
ALTER TABLE billing_line FORCE ROW LEVEL SECURITY;

CREATE POLICY billing_line_tenant_isolation ON billing_line
    USING (company_id = current_setting('app.current_tenant', true))
    WITH CHECK (company_id = current_setting('app.current_tenant', true));

CREATE INDEX idx_billing_line_period ON billing_line (period);

-- ---------------------------------------------------------------------------
-- transactional outbox (consumed by Debezium / StubRelay)
-- ---------------------------------------------------------------------------
-- Columns match libs/events OutboxRecord / OutboxWriter exactly. published_at is set by the relay
-- (ignored by Debezium). This table is NOT Auditable and is NOT under RLS: it is infrastructure the
-- relay tails, its rows already carry company_id for downstream tenant scoping, and the writer
-- always runs inside the entitlement change's RLS-scoped transaction.
CREATE TABLE outbox (
    id             UUID                     NOT NULL PRIMARY KEY,
    aggregate_type VARCHAR(255)             NOT NULL,
    aggregate_id   VARCHAR(255)             NOT NULL,
    event_type     VARCHAR(255)             NOT NULL,
    payload        BYTEA                    NOT NULL,
    headers        TEXT                     NULL,
    company_id     UUID                     NOT NULL,
    occurred_at    TIMESTAMPTZ              NOT NULL,
    published_at   TIMESTAMPTZ              NULL
);

-- The relay polls for UNPUBLISHED rows in occurrence order. A PARTIAL index (WHERE published_at IS
-- NULL) indexes only the rows the relay still has to ship, so it stays tiny.
CREATE INDEX idx_outbox_unpublished ON outbox (occurred_at, id) WHERE published_at IS NULL;

-- ---------------------------------------------------------------------------
-- processed_event (the idempotent-consumer dedupe store)
-- ---------------------------------------------------------------------------
-- libs/events ProcessedEventStore records each handled event UUID here and skips duplicates
-- (INSERT ... ON CONFLICT DO NOTHING), so a re-delivered CompanyCreated is processed at most once
-- (rule 3 / HR-3). The claim runs INSIDE the same transaction as the grants + outbox writes, so
-- dedupe and side effects commit together.
--
-- Not Auditable and not under RLS: it is consumer infrastructure keyed solely by the globally-unique
-- event id, not tenant-scoped business data.
CREATE TABLE processed_event (
    event_id     UUID                     NOT NULL PRIMARY KEY,
    processed_at TIMESTAMP WITH TIME ZONE NOT NULL
);
