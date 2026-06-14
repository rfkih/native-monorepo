-- Test-only baseline for the #16 defense-in-depth proof: a single RLS-scoped `widget`
-- table shaped exactly like every Native aggregate (the six Auditable columns + a
-- tenant-RLS policy). The library carries no schema of its own; this exists only so the
-- secured read in the integration test has a real, RLS-governed table to read from.
--
-- The application connects as a NON-superuser role (without BYPASSRLS), so the policy is
-- genuinely enforced; FORCE ROW LEVEL SECURITY makes it apply even to the table owner /
-- migration role, so no connection silently bypasses tenant isolation (CLAUDE.md rule 5).

CREATE TABLE widget (
    id          UUID         NOT NULL PRIMARY KEY,
    name        VARCHAR(255) NOT NULL,

    -- Auditable (libs/tenant) — present on every Native table.
    created_at  TIMESTAMPTZ  NOT NULL,
    created_by  VARCHAR(255) NOT NULL,
    updated_at  TIMESTAMPTZ  NOT NULL,
    updated_by  VARCHAR(255) NOT NULL,
    version     BIGINT       NOT NULL,
    company_id  VARCHAR(64)  NOT NULL
);

ALTER TABLE widget ENABLE ROW LEVEL SECURITY;
ALTER TABLE widget FORCE ROW LEVEL SECURITY;

-- A row is visible/writable only when its company_id equals the session tenant. With no
-- tenant bound the GUC is unset, current_setting(..., true) is NULL, and the predicate is
-- false for every row -> fail closed.
CREATE POLICY widget_tenant_isolation ON widget
    USING (company_id = current_setting('app.current_tenant', true))
    WITH CHECK (company_id = current_setting('app.current_tenant', true));
