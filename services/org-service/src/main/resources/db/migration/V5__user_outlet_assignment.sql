-- org-service V5 — console-user → outlet assignment (Phase 4 of org-outlet-scoping).
--
-- This table records which outlets a console user (identified by their Keycloak subject id) may
-- work at within a company. It is the authoritative assignment store owned by org-service as
-- tenant business data.
--
-- SCOPE AND INTENT:
--   * This is the CONSOLE-USER ↔ OUTLET assignment. It is NOT the HR employee assignment (which
--     lives in employee-service and carries contract/payroll data — that is a separate bounded
--     context). The Keycloak subject id is stable, non-PII, and safe to store here.
--   * Read by the console for the POS outlet picker and (Phase 5) consumed by the verticals via
--     the UserOutletAssignmentChanged event for server-side enforcement.
--   * Contains only ids (user_id = KC sub, org_unit_id = outlet node). No personal data lives
--     here — rule 6 (PII encryption) does not apply.
--
-- ENFORCEMENT:
--   * org_unit_id MUST reference an active OUTLET node (type = 'outlet', active = true). This
--     invariant is enforced by the UserOutletAssignmentService aggregate before insert/update, NOT
--     by a database foreign key. Reason: org-service intentionally carries no intra-schema FK
--     constraints — org_unit.parent_id (V1) is the precedent. The constraint is an aggregate rule,
--     not a structural DB rule, which keeps migrations additive and decoupled.
--
-- EFFECTIVE DATING:
--   * effective_from / effective_to use the far-future 9999-12-31 open-ended sentinel, never NULL
--     (ENGINEERING-STANDARDS §2.5 — keeps range predicates index-friendly).
--   * Revoking an assignment stamps effective_to to today; it does NOT hard-delete, so assignment
--     history is preserved for audit and re-runnable consolidation.
--
-- RLS:
--   * ENABLE + FORCE ROW LEVEL SECURITY scoped to company_id = current_setting('app.current_tenant',
--     true) — identical predicate to every other org-service table (V1/V2/V3 pattern).
--   * FORCE so the policy binds even the table owner / migration role (defense in depth — CLAUDE.md
--     rule 5).

-- ---------------------------------------------------------------------------
-- user_outlet_assignment
-- ---------------------------------------------------------------------------
CREATE TABLE user_outlet_assignment (
    id              UUID         NOT NULL PRIMARY KEY,

    -- The Keycloak subject id of the console user. VARCHAR(64) matches the column type used for
    -- user references elsewhere in org-service (e.g. created_by / updated_by on every Auditable).
    user_id         VARCHAR(64)  NOT NULL,

    -- The org_unit node that must be an active OUTLET. No FK constraint — intentional; see header
    -- comment. The aggregate enforces the type=outlet + active=true guard before persisting.
    org_unit_id     UUID         NOT NULL,

    -- Effective-dated: open assignment uses the far-future 9999-12-31 sentinel, never NULL.
    effective_from  DATE         NOT NULL,
    effective_to    DATE         NOT NULL DEFAULT DATE '9999-12-31',

    -- Whether this assignment is currently active. Flipped to false on a logical delete; the
    -- effective_to is simultaneously stamped. Kept as a fast filter for the hot "my outlets" query
    -- without a date-range predicate.
    active          BOOLEAN      NOT NULL DEFAULT TRUE,

    -- Auditable (libs/tenant) — present on every Native table (CLAUDE.md rule 4).
    -- Types are verbatim from org_unit (V1): TIMESTAMPTZ, VARCHAR(255), BIGINT, VARCHAR(64).
    created_at      TIMESTAMPTZ  NOT NULL,
    created_by      VARCHAR(255) NOT NULL,
    updated_at      TIMESTAMPTZ  NOT NULL,
    updated_by      VARCHAR(255) NOT NULL,
    version         BIGINT       NOT NULL,
    company_id      VARCHAR(64)  NOT NULL,

    -- The assignment set key: one active window per (company, user, outlet) at most.
    -- A UNIQUE constraint (not partial) covers the full tuple — a user cannot hold two rows for
    -- the same outlet within the same company regardless of effective dates. To re-assign after
    -- removal the existing row is reopened (effective_to reset to 9999-12-31), not duplicated.
    CONSTRAINT uq_user_outlet_assignment_set
        UNIQUE (company_id, user_id, org_unit_id)
);

-- ---------------------------------------------------------------------------
-- Row-level security — tenant isolation
-- ---------------------------------------------------------------------------
ALTER TABLE user_outlet_assignment ENABLE ROW LEVEL SECURITY;
-- FORCE so the policy applies even to the table owner / migration role (defense in depth).
ALTER TABLE user_outlet_assignment FORCE ROW LEVEL SECURITY;

-- A row is visible/writable only when its company_id equals the session tenant.
-- current_setting(..., true) returns NULL (not an error) when the GUC is unset, so with no
-- tenant bound the predicate is false for every row — fail closed (same guarantee as V1/V2/V3).
CREATE POLICY user_outlet_assignment_tenant_isolation ON user_outlet_assignment
    USING  (company_id = current_setting('app.current_tenant', true))
    WITH CHECK (company_id = current_setting('app.current_tenant', true));

-- ---------------------------------------------------------------------------
-- Indexes
-- ---------------------------------------------------------------------------
-- Primary access path: "give me all outlets assigned to this user in this company."
-- Covers the POS outlet picker and Phase-5 enforcement read-model hydration.
CREATE INDEX idx_user_outlet_assignment_user
    ON user_outlet_assignment (company_id, user_id);

-- Secondary path: "give me all users assigned to this outlet" (Team page outlet column, admin view).
CREATE INDEX idx_user_outlet_assignment_outlet
    ON user_outlet_assignment (company_id, org_unit_id);
