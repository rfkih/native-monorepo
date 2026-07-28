-- org-service V8 — per-console-login PAGE GRANTS (adjustable page access).
--
-- Records which console pages a login (identified by its Keycloak subject id) may open. This is
-- SUBTRACTIVE, UI-LEVEL gating layered on top of the role surface:
--   * ZERO grant rows for a user = the FULL surface their roles allow (grandfather semantics —
--     mirrors user_outlet_assignment: absence of restriction = unrestricted).
--   * One or more rows = RESTRICTED to exactly those page keys (still intersected with the role
--     surface — a grant can only narrow, never widen).
--   * Owner/manager bypass grants entirely (they always see the full dashboard).
--
-- SECURITY BOUNDARY (deliberate — see the ADR): grants gate the CONSOLE only. The API authz
-- boundary remains the gateway's role check; a user who still holds a role can call that role's
-- APIs directly regardless of their page grants. Grants are a usability/curation tool, not a
-- security control. This is why there is NO event and NO downstream consumer: enforcement is
-- entirely console + org-local.
--
-- Modeled on V5 user_outlet_assignment (same VARCHAR(64) sub, Auditable, FORCE RLS, unique-set)
-- but WITHOUT effective dating — a page grant is a simple present/absent boolean, so a logical
-- delete flips `active` (history preserved) with no date range to reason about.

CREATE TABLE user_page_grant (
    id          UUID         NOT NULL PRIMARY KEY,

    -- The Keycloak subject id of the console login (stable, non-PII; VARCHAR(64) per V5).
    user_id     VARCHAR(64)  NOT NULL,

    -- The page key granted (pos | kitchen | menu | me). Whitelisted by the aggregate/service, not
    -- a DB constraint (org-service intentionally carries no CHECK/FK on business vocab — matches
    -- the org_unit.type precedent).
    page_key    VARCHAR(64)  NOT NULL,

    -- Whether the grant is currently active. A logical delete flips this to false (history kept);
    -- re-granting reopens the existing row (respects the unique-set constraint).
    active      BOOLEAN      NOT NULL DEFAULT TRUE,

    -- Auditable (libs/tenant) — present on every Native table (rule 4). Types verbatim from V5.
    created_at  TIMESTAMPTZ  NOT NULL,
    created_by  VARCHAR(255) NOT NULL,
    updated_at  TIMESTAMPTZ  NOT NULL,
    updated_by  VARCHAR(255) NOT NULL,
    version     BIGINT       NOT NULL,
    company_id  VARCHAR(64)  NOT NULL,

    -- At most one row per (company, user, page): re-granting reopens rather than duplicates.
    CONSTRAINT uq_user_page_grant_set UNIQUE (company_id, user_id, page_key)
);

-- ---------------------------------------------------------------------------
-- Row-level security — tenant isolation (identical predicate to V1/V2/V3/V5)
-- ---------------------------------------------------------------------------
ALTER TABLE user_page_grant ENABLE ROW LEVEL SECURITY;
ALTER TABLE user_page_grant FORCE ROW LEVEL SECURITY;

CREATE POLICY user_page_grant_tenant_isolation ON user_page_grant
    USING  (company_id = current_setting('app.current_tenant', true))
    WITH CHECK (company_id = current_setting('app.current_tenant', true));

-- ---------------------------------------------------------------------------
-- Index — the hot "my grants" path (both the /me read and the per-user editor read).
-- ---------------------------------------------------------------------------
CREATE INDEX idx_user_page_grant_user
    ON user_page_grant (company_id, user_id);
