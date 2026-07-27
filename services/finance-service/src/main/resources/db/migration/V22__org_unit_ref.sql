-- finance-service V22 — cached slice of org-service's org tree (Phase 2).
--
-- `org_unit_ref` is a local read model fed by the OrgUnitCreated / OrgUnitChanged events
-- that org-service publishes via its outbox. The consumer upserts idempotently on
-- (company_id, org_unit_id) so a re-delivered event is a no-op. Names and active status
-- may lag until events flow — readers (e.g. /pnl/outlets enriching outletName) MUST
-- tolerate missing rows and treat absence as "name not yet known" rather than an error.
--
-- Design notes:
--   * Rule 1 (database-per-service): no foreign key to org-service; org_unit_id is an
--     opaque reference UUID. No cross-service joins, ever.
--   * Rule 2 (no sync calls): finance resolves org-unit names exclusively via this local
--     read model — it never calls org-service synchronously.
--   * Rule 4 (Auditable): the table extends Auditable (company_id, created_at/by,
--     updated_at/by, version); it is a derived read model so it is NOT hash-chain-audited
--     — Debezium CDC covers history (same ruling as outlet_revenue in V21).
--   * Rule 5 (RLS): FORCE ROW LEVEL SECURITY + tenant_isolation policy identical to the
--     sibling tables (ledger_posting, outlet_revenue) in this database.
--   * Rule 8 (money): this table holds no monetary values; not applicable here.

-- ---------------------------------------------------------------------------
-- org_unit_ref (org-tree reference cache)
-- ---------------------------------------------------------------------------
-- One row per org_unit_id. The consumer upserts on the tenant-composite UNIQUE constraint
-- so multiple deliveries of OrgUnitCreated or OrgUnitChanged for the same org unit are
-- idempotent, and a cross-tenant id collision fails closed instead of relabeling.
CREATE TABLE org_unit_ref (

    -- The org-unit's canonical UUID, as carried on the OrgUnitCreated / OrgUnitChanged
    -- event. Primary key — one row per org unit. Opaque reference; no FK across boundaries.
    org_unit_id     UUID         NOT NULL PRIMARY KEY,

    -- The node type as published by org-service (business_unit | outlet | team, ADR 0012).
    -- Stored opaquely; the /pnl/outlets reader joins on org_unit_id with NO type predicate
    -- (outlet_revenue.business_id is an outlet id by construction since ADR 0012).
    type            VARCHAR(32)  NOT NULL,

    -- The parent org-unit in the org tree, NULL for root nodes (e.g. the top-level
    -- business unit). Nullable; no FK constraint (database-per-service rule 1).
    parent_id       UUID         NULL,

    -- The human-readable display name (e.g. "Drive-Through Outlet").
    -- May lag by up to one event-delivery cycle after a rename — callers must tolerate.
    name            VARCHAR(255) NOT NULL,

    -- Whether the org unit is currently active in org-service. Inactive units are
    -- retained so historical postings can still resolve a name.
    active          BOOLEAN      NOT NULL DEFAULT TRUE,

    -- Auditable (libs/tenant) — present on every Native table (rule 4).
    created_at      TIMESTAMPTZ  NOT NULL,
    created_by      VARCHAR(255) NOT NULL,
    updated_at      TIMESTAMPTZ  NOT NULL,
    updated_by      VARCHAR(255) NOT NULL,
    version         BIGINT       NOT NULL,
    company_id      VARCHAR(64)  NOT NULL,

    -- Tenant-composite upsert key. The consumer's ON CONFLICT targets THIS constraint,
    -- not the bare PK, so a (negligible-probability) org_unit_id collision across two
    -- tenants can never DO UPDATE another tenant's row: the insert instead trips the
    -- PK unique violation and fails closed to the DLT. Tenant isolation is structural,
    -- not probabilistic (belt) — and RLS already filters the update target (suspenders).
    CONSTRAINT uq_org_unit_ref_company_unit UNIQUE (company_id, org_unit_id)
);

ALTER TABLE org_unit_ref ENABLE ROW LEVEL SECURITY;
ALTER TABLE org_unit_ref FORCE ROW LEVEL SECURITY;

-- Tenant isolation: every SELECT/INSERT/UPDATE/DELETE is filtered to the session tenant.
-- finance connects as an unprivileged role without BYPASSRLS; FORCE ensures the policy
-- binds even the owning role (defense in depth — rule 5).
CREATE POLICY org_unit_ref_tenant_isolation ON org_unit_ref
    USING (company_id = current_setting('app.current_tenant', true))
    WITH CHECK (company_id = current_setting('app.current_tenant', true));

-- Historical note: the /pnl/outlets reader actually joins on org_unit_id (the PK) with no
-- type predicate — this (company_id, type) index predates that and is retained as harmless;
-- tenant-scoped scans still use it.
CREATE INDEX idx_org_unit_ref_company_type ON org_unit_ref (company_id, type);
