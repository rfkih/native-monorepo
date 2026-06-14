-- org-service V2 — the FULL org tree + the legal_employer aggregate (task #18).
--
-- Expands the minimal M1.2 slice (a flat org_unit + an implied legal_employer_id ==
-- company_id) into:
--   * org_unit gains legal_employer_id, an active flag, and effective-dated columns
--     (effective_from / effective_to with the far-future 9999-12-31 open-ended
--     sentinel, never NULL — ENGINEERING-STANDARDS §2.5). The self-referencing tree
--     (business_unit > branch > outlet > team) already exists via parent_id; the
--     parent->child TYPE rules are enforced in the OrgUnit aggregate.
--   * a new legal_employer table — the legal employing entity org-service owns; one
--     per company is created in the bootstrap (its id == company_id), Auditable + RLS.
--
-- Additive / expand only (Flyway migrations are immutable): the new org_unit columns
-- carry defaults so the migration is safe even against pre-existing rows; the
-- application always sets them explicitly on insert.

-- ---------------------------------------------------------------------------
-- legal_employer (the legal employing entity)
-- ---------------------------------------------------------------------------
CREATE TABLE legal_employer (
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

ALTER TABLE legal_employer ENABLE ROW LEVEL SECURITY;
-- FORCE so the policy applies even to the table owner / migration role (defense in depth).
ALTER TABLE legal_employer FORCE ROW LEVEL SECURITY;

CREATE POLICY legal_employer_tenant_isolation ON legal_employer
    USING (company_id = current_setting('app.current_tenant', true))
    WITH CHECK (company_id = current_setting('app.current_tenant', true));

-- ---------------------------------------------------------------------------
-- org_unit — gain legal_employer_id, active, and effective-dated columns
-- ---------------------------------------------------------------------------
-- The legal employer this node belongs to. NOT NULL; backfilled to company_id for any
-- pre-existing row (in the bootstrap legal_employer_id == company_id), then the default
-- is dropped so every new insert must supply it explicitly.
ALTER TABLE org_unit ADD COLUMN legal_employer_id UUID;
UPDATE org_unit SET legal_employer_id = company_id::uuid WHERE legal_employer_id IS NULL;
ALTER TABLE org_unit ALTER COLUMN legal_employer_id SET NOT NULL;

-- Whether the node is currently active (a deactivation flips it to false).
ALTER TABLE org_unit ADD COLUMN active BOOLEAN NOT NULL DEFAULT TRUE;

-- Effective-dated rows: open-ended effective_to uses the far-future 9999-12-31 sentinel,
-- never NULL (keeps range predicates index-friendly — ENGINEERING-STANDARDS §2.5).
ALTER TABLE org_unit ADD COLUMN effective_from DATE NOT NULL DEFAULT CURRENT_DATE;
ALTER TABLE org_unit ADD COLUMN effective_to   DATE NOT NULL DEFAULT DATE '9999-12-31';
