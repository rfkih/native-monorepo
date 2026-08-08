-- org-service V11 — device_credential (ADR 0049 P3a): the per-outlet Keycloak KIOSK/DEVICE
-- login the Business app stays signed in as.
--
-- SCOPE AND INTENT:
--   * One row per (company_id, org_unit_id) — at most ONE device credential per outlet. The
--     Keycloak user it references is `cashier`-role (POS-capable, never back-office — the
--     existing DASHBOARD_ROLES/OWNER_ROLES gateway route gates already exclude cashier) and
--     carries the `actor_type=device` user attribute (ADR 0049 P0). It is bound to exactly one
--     outlet via the EXISTING user_outlet_assignment table (V5) — no new binding mechanism.
--   * The one-time generated password is held here ENCRYPTED so the owner/manager can re-reveal
--     it later to set up or replace a physical device — a DELIBERATE, bounded exception to
--     "never store a password" (mirrors the ADR 0014 held-temp-password posture in
--     employee-service V8), except this one is NOT purged after first use: a kiosk credential is
--     long-lived and the owner may need it again for re-provisioning.
--
-- PII / CREDENTIAL HYGIENE:
--   * password_enc is AES-256-GCM column-level encrypted at rest via the org-service PiiCipher /
--     PiiAttributeConverter (mirrors employee-service's config/PiiCipher.java — rule 6). The
--     plaintext is NEVER logged and never appears outside the owner/manager reveal endpoint.
--   * keycloak_user_id and username are stable, non-PII identifiers (same posture as
--     user_outlet_assignment.user_id, V5) — no encryption needed.
--
-- ENFORCEMENT:
--   * org_unit_id MUST reference an active OUTLET node. Like user_outlet_assignment (V5), this is
--     an AGGREGATE invariant enforced by DeviceCredentialService before insert, NOT a database FK
--     — org-service intentionally carries no intra-schema FK constraints (org_unit.parent_id, V1,
--     is the precedent).
--   * UNIQUE (company_id, org_unit_id) — at most one device credential per outlet; a second
--     POST for the same outlet is rejected with 409 before ever reaching Keycloak.
--
-- RLS:
--   * ENABLE + FORCE ROW LEVEL SECURITY scoped to company_id = current_setting('app.current_tenant',
--     true) — identical predicate to every other org-service table (V1/V2/V3/V5 pattern). FORCE so
--     the policy binds even the table owner / migration role (defense in depth — rule 5).

-- ---------------------------------------------------------------------------
-- device_credential
-- ---------------------------------------------------------------------------
CREATE TABLE device_credential (
    id                UUID         NOT NULL PRIMARY KEY,

    -- The outlet (org_unit) this device is bound to. No FK constraint — intentional; see header
    -- comment. The service enforces the type=OUTLET + active guard before persisting.
    org_unit_id       UUID         NOT NULL,

    -- The Keycloak subject id (sub) of the minted device/kiosk user. VARCHAR(64) matches the
    -- column type used for user references elsewhere in org-service (created_by/updated_by,
    -- user_outlet_assignment.user_id).
    keycloak_user_id  VARCHAR(64)  NOT NULL,

    -- The device login's Keycloak username (e.g. "till.<outletId>") — stable/unique by
    -- construction (derived from the outlet id, not its mutable name). Not PII.
    username          VARCHAR(255) NOT NULL,

    -- The device login's password, AES-256-GCM encrypted at rest (see header comment). Holds
    -- ciphertext ONLY — the application layer decrypts transparently via PiiAttributeConverter.
    password_enc      VARCHAR(255) NOT NULL,

    -- Auditable (libs/tenant) — present on every Native table (CLAUDE.md rule 4).
    -- Types are verbatim from user_outlet_assignment (V5): TIMESTAMPTZ, VARCHAR(255), BIGINT,
    -- VARCHAR(64).
    created_at        TIMESTAMPTZ  NOT NULL,
    created_by        VARCHAR(255) NOT NULL,
    updated_at        TIMESTAMPTZ  NOT NULL,
    updated_by        VARCHAR(255) NOT NULL,
    version           BIGINT       NOT NULL,
    company_id        VARCHAR(64)  NOT NULL,

    -- At most one device credential per outlet.
    CONSTRAINT uq_device_credential_outlet
        UNIQUE (company_id, org_unit_id)
);

-- ---------------------------------------------------------------------------
-- Row-level security — tenant isolation
-- ---------------------------------------------------------------------------
ALTER TABLE device_credential ENABLE ROW LEVEL SECURITY;
-- FORCE so the policy applies even to the table owner / migration role (defense in depth).
ALTER TABLE device_credential FORCE ROW LEVEL SECURITY;

-- A row is visible/writable only when its company_id equals the session tenant.
-- current_setting(..., true) returns NULL (not an error) when the GUC is unset, so with no
-- tenant bound the predicate is false for every row — fail closed (same guarantee as V1/V2/V3/V5).
CREATE POLICY device_credential_tenant_isolation ON device_credential
    USING  (company_id = current_setting('app.current_tenant', true))
    WITH CHECK (company_id = current_setting('app.current_tenant', true));

-- ---------------------------------------------------------------------------
-- Indexes
-- ---------------------------------------------------------------------------
-- Primary access path: "give me the device credential for this outlet" (create/reset/reveal/
-- delete all key on org_unit_id). The UNIQUE constraint above already creates a supporting index.
