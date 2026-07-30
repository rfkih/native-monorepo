-- carwash-service V7 — local cache of org-service user→outlet assignments
-- (restaurant-service V14, copied verbatim; only this header names the local consumers).
--
-- `user_outlet_assignment_ref` is a read model fed by the `UserOutletAssignmentChanged`
-- event that org-service publishes via its outbox.  The consumer upserts idempotently:
-- ASSIGNED → active = true, UNASSIGNED → active = false, targeting the tenant-composite
-- UNIQUE constraint (company_id, user_id, org_unit_id) so a re-delivered event is a
-- no-op (ON CONFLICT DO UPDATE of the same values).
--
-- Design notes:
--   * Rule 1 (database-per-service): org_unit_id and user_id are opaque references;
--     no FK to org-service, no cross-service joins, ever.
--   * Rule 2 (no sync calls): OutletAccessGuard (invoked by TicketWriter) reads exclusively
--     from this table — it never calls org-service to validate a cashier's outlet
--     assignment synchronously.
--   * Rule 4 (Auditable): the table carries all six Auditable columns; it is a derived
--     read model so it is NOT hash-chain-audited — Debezium CDC covers history.
--   * Rule 5 (RLS): ENABLE + FORCE ROW LEVEL SECURITY + tenant_isolation policy,
--     identical predicate to all other carwash-service tables.
--   * Rule 8 (money): this table holds no monetary values; not applicable.
--   * Enforcement policy: cashier default-closed (must be in this table with active=true
--     for the requested org_unit_id); owner/manager bypass is applied in OutletAccessGuard
--     before the guard query, so this table is never queried for those roles.
--   * Ids only — no PII stored here.

-- ---------------------------------------------------------------------------
-- user_outlet_assignment_ref (org-service assignment cache)
-- ---------------------------------------------------------------------------
-- PK = assignment_id, the canonical UUID carried on the event (the org-service
-- user_outlet_assignment row id).  A cross-tenant assignment_id collision —
-- astronomically unlikely with UUIDv4 — fails closed on the PK unique violation
-- and lands in the DLT rather than corrupting another tenant's row; the composite
-- UNIQUE constraint (company_id, user_id, org_unit_id) is the upsert target.
CREATE TABLE user_outlet_assignment_ref (

    -- The org-service assignment row id, carried verbatim from the event.
    -- Primary key: one row per assignment lifecycle (org-service owns this UUID).
    assignment_id   UUID         NOT NULL PRIMARY KEY,

    -- Keycloak subject (sub claim) of the assigned user.  Opaque string reference;
    -- no FK (database-per-service rule 1).
    user_id         VARCHAR(64)  NOT NULL,

    -- The org-unit (outlet) UUID from org-service.  Opaque reference; no FK.
    org_unit_id     UUID         NOT NULL,

    -- Post-change state: true = ASSIGNED, false = UNASSIGNED.  The consumer always
    -- upserts the current state so repeated delivery of the same event is idempotent.
    active          BOOLEAN      NOT NULL,

    -- Auditable (libs/tenant) — present on every Native table (rule 4).
    created_at      TIMESTAMPTZ  NOT NULL,
    created_by      VARCHAR(255) NOT NULL,
    updated_at      TIMESTAMPTZ  NOT NULL,
    updated_by      VARCHAR(255) NOT NULL,
    version         BIGINT       NOT NULL,
    company_id      VARCHAR(64)  NOT NULL,

    -- Tenant-composite upsert key.  The consumer targets ON CONFLICT ON CONSTRAINT
    -- uq_user_outlet_assignment_ref_scope rather than the bare PK so that a
    -- (negligible-probability) assignment_id collision across two tenants fails closed
    -- on the PK instead of UPDATE-ing another tenant's row.  RLS further enforces
    -- that any UPDATE can only touch the session tenant's rows (belt + suspenders).
    CONSTRAINT uq_user_outlet_assignment_ref_scope
        UNIQUE (company_id, user_id, org_unit_id)
);

ALTER TABLE user_outlet_assignment_ref ENABLE ROW LEVEL SECURITY;
ALTER TABLE user_outlet_assignment_ref FORCE ROW LEVEL SECURITY;

-- Tenant isolation: every SELECT/INSERT/UPDATE/DELETE is filtered to the session
-- tenant.  current_setting('app.current_tenant', true) returns NULL when no GUC is
-- set, so the predicate is false for every row — fail closed.
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_policies
         WHERE tablename  = 'user_outlet_assignment_ref'
           AND policyname = 'user_outlet_assignment_ref_tenant_isolation'
    ) THEN
        EXECUTE $q$
            CREATE POLICY user_outlet_assignment_ref_tenant_isolation
                ON user_outlet_assignment_ref
                USING      (company_id = current_setting('app.current_tenant', true))
                WITH CHECK (company_id = current_setting('app.current_tenant', true))
        $q$;
    END IF;
END $$;

-- Hot guard-lookup index: OrderWriter evaluates
--   EXISTS (SELECT 1 FROM user_outlet_assignment_ref
--            WHERE company_id = ? AND user_id = ? AND org_unit_id = ? AND active = true)
-- This partial index covers (company_id, user_id) and filters to active=true rows only,
-- keeping the index small as UNASSIGNED (active=false) rows are cold data.
CREATE INDEX IF NOT EXISTS idx_user_outlet_assignment_ref_active
    ON user_outlet_assignment_ref (company_id, user_id)
    WHERE active = TRUE;
