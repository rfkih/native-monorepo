-- org-service V3 (P3d SEAM 1) — the consolidation GROUP model + effective-dated membership.
--
-- ADDITIVE expand over V2 (never edit an applied migration). It adds two tenant-scoped tables:
--   * consolidation_group — a named group of companies a LEAD company consolidates, carrying the
--     lead_company_id, the group name, and the reporting_currency (ISO-4217, IMMUTABLE — set once
--     at creation exactly like company.base_currency; the column is write-once via the aggregate's
--     updatable=false mapping).
--   * group_membership     — one company's EFFECTIVE-DATED membership in a group (effective_from /
--     effective_to with the far-future 9999-12-31 open-ended sentinel, never NULL —
--     ENGINEERING-STANDARDS §2.5). A removal CLOSES the window (stamps effective_to); it never
--     hard-deletes, so membership history survives for re-runnable consolidation.
--
-- LEAD-OWNED RLS (rule 5). Both tables are Auditable (rule 4) and under ENABLE/FORCE RLS scoped to
-- the LEAD company: company_id = current_setting('app.current_tenant'), AND a CHECK pins
-- company_id = lead_company_id (for consolidation_group) / company_id = the owning group's lead
-- (enforced in code for group_membership, whose company_id is stamped to the lead). Only the lead
-- administers the group; a MEMBER company — querying its own tenant scope — sees neither the group
-- nor its memberships, and cannot mutate them. The application stamps company_id = lead_company_id
-- from the bound TenantContext (the caller's tenant), so the WITH CHECK passes for the lead and
-- fails closed for anyone else.

-- ---------------------------------------------------------------------------
-- consolidation_group (the group keystone — lead-owned)
-- ---------------------------------------------------------------------------
CREATE TABLE consolidation_group (
    id                 UUID         NOT NULL PRIMARY KEY,

    -- The lead/owner company that administers this group. Equals company_id (the group's tenant),
    -- so RLS confines the group to its lead. Immutable.
    lead_company_id    UUID         NOT NULL,

    name               VARCHAR(255) NOT NULL,

    -- The group's reporting (presentation) currency: an ISO-4217 alphabetic code, fixed at creation
    -- and IMMUTABLE thereafter (CLAUDE.md "Settings live at creation"). CHAR(3) (bpchar); the
    -- @JdbcTypeCode(CHAR) mapping keeps ddl-auto=validate happy. The aggregate maps it updatable=false
    -- so even a Hibernate flush cannot rewrite it — exactly like company.base_currency.
    reporting_currency CHAR(3)      NOT NULL,

    -- Auditable (libs/tenant) — present on every Native table.
    created_at         TIMESTAMPTZ  NOT NULL,
    created_by         VARCHAR(255) NOT NULL,
    updated_at         TIMESTAMPTZ  NOT NULL,
    updated_by         VARCHAR(255) NOT NULL,
    version            BIGINT       NOT NULL,
    company_id         VARCHAR(64)  NOT NULL,

    -- The group is LEAD-OWNED: its tenant (company_id) IS its lead. Pin it so a row can never be
    -- stamped to a tenant other than its own lead.
    CONSTRAINT ck_consolidation_group_lead_is_tenant
        CHECK (company_id = lead_company_id::text)
);

ALTER TABLE consolidation_group ENABLE ROW LEVEL SECURITY;
-- FORCE so the policy applies even to the table owner / migration role (defense in depth).
ALTER TABLE consolidation_group FORCE ROW LEVEL SECURITY;

-- Visible/writable only when company_id (= the lead) equals the session tenant. A member company's
-- session tenant is its own id, which never equals the lead, so the group is invisible to it.
CREATE POLICY consolidation_group_tenant_isolation ON consolidation_group
    USING (company_id = current_setting('app.current_tenant', true))
    WITH CHECK (company_id = current_setting('app.current_tenant', true));

-- ---------------------------------------------------------------------------
-- group_membership (effective-dated membership — lead-owned)
-- ---------------------------------------------------------------------------
CREATE TABLE group_membership (
    id                UUID         NOT NULL PRIMARY KEY,

    group_id          UUID         NOT NULL,
    member_company_id UUID         NOT NULL,

    -- The owning group's LEAD company. Known at insert time (the writer loads the group via
    -- requireOwnedGroup) and STRUCTURALLY pins the tenant: the CHECK below ties company_id to it,
    -- so a row can never be stamped to a tenant other than the group's lead — mirroring
    -- consolidation_group.ck_consolidation_group_lead_is_tenant. Defense in depth: the application
    -- already stamps company_id from the bound TenantContext, this makes it a DB invariant too.
    lead_company_id   UUID         NOT NULL,

    -- Effective-dated: open membership uses the far-future 9999-12-31 sentinel, never NULL.
    effective_from    DATE         NOT NULL,
    effective_to      DATE         NOT NULL DEFAULT DATE '9999-12-31',

    -- Auditable (libs/tenant) — present on every Native table. company_id is the LEAD company (the
    -- owning group's lead), stamped from the bound tenant, so a member cannot read/mutate it.
    created_at        TIMESTAMPTZ  NOT NULL,
    created_by        VARCHAR(255) NOT NULL,
    updated_at        TIMESTAMPTZ  NOT NULL,
    updated_by        VARCHAR(255) NOT NULL,
    version           BIGINT       NOT NULL,
    company_id        VARCHAR(64)  NOT NULL,

    -- Structural tenancy guard: the membership's tenant (company_id) IS the owning group's lead.
    -- Pins it so a row can never be stamped to a tenant other than that lead — the same guarantee
    -- consolidation_group gives, now enforced for membership rows instead of by code alone.
    CONSTRAINT ck_group_membership_lead_is_tenant
        CHECK (company_id = lead_company_id::text)
);

ALTER TABLE group_membership ENABLE ROW LEVEL SECURITY;
ALTER TABLE group_membership FORCE ROW LEVEL SECURITY;

CREATE POLICY group_membership_tenant_isolation ON group_membership
    USING (company_id = current_setting('app.current_tenant', true))
    WITH CHECK (company_id = current_setting('app.current_tenant', true));

-- The add/remove lookup finds the ACTIVE membership for a (group, member) pair; index that path.
CREATE INDEX idx_group_membership_active
    ON group_membership (group_id, member_company_id, effective_to);

-- "At most one ACTIVE membership per (group, member)" as a true DB invariant, not a read-then-insert
-- convention. PARTIAL UNIQUE only over the OPEN (active) rows — those whose window is the far-future
-- 9999-12-31 sentinel — so the full history (multiple CLOSED rows with stamped effective_to) is
-- left untouched: a member may be added, removed, and re-added, accumulating CLOSED rows, but never
-- carry two open windows at once.
CREATE UNIQUE INDEX uq_group_membership_active
    ON group_membership (group_id, member_company_id)
    WHERE effective_to = DATE '9999-12-31';
