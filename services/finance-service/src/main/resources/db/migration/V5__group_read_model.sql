-- finance-service V5 (P3d SEAM 1) — the local GROUP read model.
--
-- finance consumes the org-service GroupDefined + GroupMembershipChanged events into a LOCAL group
-- read model (rule 2 — never a synchronous call to org-service). PURE PLUMBING: no consolidation
-- math here; later seams read this reference to drive group consolidation.
--
-- Three tables:
--   * group_ref    — group_id -> {lead_company_id, reporting_currency}. The group reference the
--                    finance dashboard / later consolidation reads. Auditable + FORCE RLS scoped to
--                    the LEAD company (company_id = lead_company_id), consistent with how finance
--                    keeps its other tenant read models (consolidated_revenue, consolidated_pnl):
--                    an application-maintained projection, so rule-4 Auditable + rule-5 RLS apply.
--                    reporting_currency mirrors the IMMUTABLE producer value (ISO-4217, never float).
--   * group_member — group_id -> the effective-dated active member set. One row per (group_id,
--                    member_company_id); effective_to uses the far-future 9999-12-31 sentinel for an
--                    active membership (a REMOVED event CLOSES the window, never hard-deletes).
--                    Auditable + FORCE RLS, same LEAD tenancy as group_ref.
--   * group_lead   — group_id -> lead_company_id. A small CROSS-TENANT reference mapping (NOT
--                    Auditable, NOT under RLS — exactly like chart_of_account / mapping_rule /
--                    processed_event), written alongside group_ref on GroupDefined. It exists ONLY so
--                    the GroupMembershipChanged consumer (whose event carries no lead) can resolve the
--                    owning lead from group_id alone and bind that tenant before the RLS-scoped
--                    group_member write — without a synchronous call and without the chicken-and-egg
--                    of reading an RLS-scoped table to discover its own tenant. It holds only the
--                    public group->lead structural mapping (no amount, no PII).
--
-- SEAM 2 (NOT here) introduces a group-scoped GUC; this seam deliberately stays company_id-scoped to
-- the lead, the existing finance org-derived-read-model precedent.

-- ---------------------------------------------------------------------------
-- group_ref (group -> lead + reporting currency) — Auditable + RLS (lead-scoped)
-- ---------------------------------------------------------------------------
CREATE TABLE group_ref (
    group_id           UUID         NOT NULL PRIMARY KEY,

    -- The lead/owner company. Equals company_id (the read model's tenant), so RLS confines the row
    -- to the lead's finance scope.
    lead_company_id    UUID         NOT NULL,

    -- The group's reporting (presentation) currency: an ISO-4217 code mirrored from the immutable
    -- producer value. CHAR(3) (bpchar); the @JdbcTypeCode(CHAR) mapping keeps ddl-auto=validate happy.
    reporting_currency CHAR(3)      NOT NULL,

    -- Auditable (libs/tenant) — present on every Native table.
    created_at         TIMESTAMPTZ  NOT NULL,
    created_by         VARCHAR(255) NOT NULL,
    updated_at         TIMESTAMPTZ  NOT NULL,
    updated_by         VARCHAR(255) NOT NULL,
    version            BIGINT       NOT NULL,
    company_id         VARCHAR(64)  NOT NULL,

    -- The read model is LEAD-OWNED: its tenant (company_id) IS the group's lead.
    CONSTRAINT ck_group_ref_lead_is_tenant CHECK (company_id = lead_company_id::text)
);

ALTER TABLE group_ref ENABLE ROW LEVEL SECURITY;
ALTER TABLE group_ref FORCE ROW LEVEL SECURITY;

CREATE POLICY group_ref_tenant_isolation ON group_ref
    USING (company_id = current_setting('app.current_tenant', true))
    WITH CHECK (company_id = current_setting('app.current_tenant', true));

-- ---------------------------------------------------------------------------
-- group_member (the effective-dated active member set) — Auditable + RLS (lead-scoped)
-- ---------------------------------------------------------------------------
CREATE TABLE group_member (
    id                UUID         NOT NULL PRIMARY KEY,

    group_id          UUID         NOT NULL,
    member_company_id UUID         NOT NULL,

    -- Effective-dated: an active membership uses the far-future 9999-12-31 sentinel, never NULL.
    effective_from    DATE         NOT NULL,
    effective_to      DATE         NOT NULL DEFAULT DATE '9999-12-31',

    -- Auditable (libs/tenant) — company_id is the group's LEAD (same tenancy as group_ref).
    created_at        TIMESTAMPTZ  NOT NULL,
    created_by        VARCHAR(255) NOT NULL,
    updated_at        TIMESTAMPTZ  NOT NULL,
    updated_by        VARCHAR(255) NOT NULL,
    version           BIGINT       NOT NULL,
    company_id        VARCHAR(64)  NOT NULL,

    -- One row per (group, member): the consumer upserts on it (ADDED opens/re-opens, REMOVED closes).
    CONSTRAINT uq_group_member UNIQUE (group_id, member_company_id)
);

ALTER TABLE group_member ENABLE ROW LEVEL SECURITY;
ALTER TABLE group_member FORCE ROW LEVEL SECURITY;

CREATE POLICY group_member_tenant_isolation ON group_member
    USING (company_id = current_setting('app.current_tenant', true))
    WITH CHECK (company_id = current_setting('app.current_tenant', true));

-- The active-member-set scan for a group; index that access path.
CREATE INDEX idx_group_member_group ON group_member (group_id, effective_to);

-- ---------------------------------------------------------------------------
-- group_lead (group -> lead reference mapping) — NOT Auditable, NOT under RLS
-- ---------------------------------------------------------------------------
-- Cross-tenant reference infrastructure (like chart_of_account / processed_event), keyed solely by
-- the globally-unique group_id. It lets the GroupMembershipChanged consumer resolve the owning lead
-- (the tenant to bind) from group_id alone before the RLS-scoped group_member write — without a sync
-- call and without reading an RLS-scoped table to discover its own tenant. Public structural mapping
-- only (no amount, no PII).
CREATE TABLE group_lead (
    group_id        UUID NOT NULL PRIMARY KEY,
    lead_company_id UUID NOT NULL
);
