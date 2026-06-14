-- org-service baseline (M1.2).
--
-- Three tables:
--   * `company`  — the tenant keystone (the legal employer / console scope), carrying
--                  the mandatory Auditable columns + the company's base_currency
--                  (ISO-4217, immutable) and default_language, both set at creation.
--   * `org_unit` — the self-referencing org tree under the company (business_unit |
--                  branch | outlet | team), the first node of which (the business) is
--                  created together with the company.
--   * `outbox`   — the transactional-outbox table the libs/events OutboxWriter writes
--                  to; the CompanyCreated event row commits atomically with the company.
--
-- The application connects as a NON-superuser role (without BYPASSRLS), so the RLS
-- policy is genuinely enforced; FORCE ROW LEVEL SECURITY makes the policy apply even
-- to the table owner / migration role, so no connection silently bypasses tenant
-- isolation (CLAUDE.md rule 5 — defense in depth).
--
-- Tenant bootstrap nuance: creating a company CREATES a new tenant. The create-company
-- flow generates the new company_id and runs the whole insert (company + first org_unit
-- + outbox row) INSIDE a TenantContext scope bound to that NEW id, so the auto-RLS
-- aspect sets app.current_tenant to the new id and the RLS WITH CHECK passes on the
-- bootstrap insert (the row's company_id equals the session tenant by construction).

-- ---------------------------------------------------------------------------
-- company (the tenant keystone)
-- ---------------------------------------------------------------------------
CREATE TABLE company (
    id                UUID         NOT NULL PRIMARY KEY,
    name              VARCHAR(255) NOT NULL,

    -- The company's base (functional) currency: an ISO-4217 alphabetic code, fixed
    -- at creation and IMMUTABLE thereafter (CLAUDE.md "Settings live at creation").
    -- CHAR(3) (bpchar); the @JdbcTypeCode(CHAR) mapping keeps ddl-auto=validate happy.
    base_currency     CHAR(3)      NOT NULL,

    -- The company default language (e.g. "en"/"id"); a per-user override lives on the
    -- user profile, not here. Set at creation; the dashboard never toggles it.
    default_language  VARCHAR(16)  NOT NULL,

    -- The legal employer this company is (consolidation + entitlement boundary).
    legal_employer_id UUID         NOT NULL,

    -- Auditable (libs/tenant) — present on every Native table.
    created_at        TIMESTAMPTZ  NOT NULL,
    created_by        VARCHAR(255) NOT NULL,
    updated_at        TIMESTAMPTZ  NOT NULL,
    updated_by        VARCHAR(255) NOT NULL,
    version           BIGINT       NOT NULL,
    company_id        VARCHAR(64)  NOT NULL
);

-- Defense in depth: tenant isolation enforced a second time by the database.
ALTER TABLE company ENABLE ROW LEVEL SECURITY;
-- FORCE so the policy applies even to the table owner / migration role.
ALTER TABLE company FORCE ROW LEVEL SECURITY;

-- A row is visible/writable only when its company_id equals the session tenant.
-- current_setting(..., true) returns NULL (not an error) when the GUC is unset, so
-- with no tenant bound the predicate is false for every row -> fail closed. On the
-- bootstrap insert the create-company flow has bound the NEW company_id as the
-- session tenant, so company_id = current_setting(...) holds and WITH CHECK passes.
CREATE POLICY company_tenant_isolation ON company
    USING (company_id = current_setting('app.current_tenant', true))
    WITH CHECK (company_id = current_setting('app.current_tenant', true));

-- ---------------------------------------------------------------------------
-- org_unit (the self-referencing org tree under the company)
-- ---------------------------------------------------------------------------
CREATE TABLE org_unit (
    id          UUID         NOT NULL PRIMARY KEY,
    name        VARCHAR(255) NOT NULL,

    -- The org-unit kind: business_unit | branch | outlet | team. Stored as text;
    -- the OrgUnitType enum is the source of truth (mapped EnumType.STRING).
    type        VARCHAR(32)  NOT NULL,

    -- Self-referencing parent (NULL for a top-level node such as the first business).
    parent_id   UUID         NULL,

    -- Auditable (libs/tenant) — present on every Native table.
    created_at  TIMESTAMPTZ  NOT NULL,
    created_by  VARCHAR(255) NOT NULL,
    updated_at  TIMESTAMPTZ  NOT NULL,
    updated_by  VARCHAR(255) NOT NULL,
    version     BIGINT       NOT NULL,
    company_id  VARCHAR(64)  NOT NULL
);

ALTER TABLE org_unit ENABLE ROW LEVEL SECURITY;
ALTER TABLE org_unit FORCE ROW LEVEL SECURITY;

CREATE POLICY org_unit_tenant_isolation ON org_unit
    USING (company_id = current_setting('app.current_tenant', true))
    WITH CHECK (company_id = current_setting('app.current_tenant', true));

-- A self-referencing parent lookup and tenant-scoped child scans are the access path.
CREATE INDEX idx_org_unit_parent ON org_unit (parent_id);

-- ---------------------------------------------------------------------------
-- transactional outbox (consumed by Debezium / StubRelay)
-- ---------------------------------------------------------------------------
-- Columns match libs/events OutboxRecord / OutboxWriter exactly. published_at is
-- set by the relay (ignored by Debezium). This table is NOT Auditable and is NOT
-- under RLS: it is infrastructure the relay tails, and its rows already carry
-- company_id for downstream tenant scoping; the writer always runs inside the
-- company's transaction, which is itself RLS-scoped.
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

-- The relay polls for UNPUBLISHED rows in occurrence order. A PARTIAL index
-- (WHERE published_at IS NULL) indexes only the rows the relay still has to ship,
-- so it stays tiny and the index does not bloat with every already-published row.
CREATE INDEX idx_outbox_unpublished ON outbox (occurred_at, id) WHERE published_at IS NULL;
