-- finance-service V6 (P3d SEAM 2) — the GROUP TRIAL-BALANCE ingest target + the two-GUC CONJUNCTION
-- RLS core.
--
-- SEAM 2 introduces a SECOND, group-scoped GUC (app.current_group) ALONGSIDE the existing
-- app.current_tenant. A group table is visible/writable ONLY when BOTH are bound and match:
--
--     group_id::text = current_setting('app.current_group', true)   -- the group scope
--   AND company_id = current_setting('app.current_tenant', true)    -- the LEAD-company tenant
--
-- company_id on a group table is the group's LEAD company (Auditable rule 4 — every table carries a
-- company_id; the lead OWNS the group read model, exactly as group_ref / group_member do in V5). So a
-- group read/write requires BOTH the group scope AND the lead-company tenant; this is the CONJUNCTION
-- fix for the design-critique RLS Critical (a group table must stay company_id-RLS-enforced, never
-- group_id-only).
--
-- FAIL CLOSED, every partial binding:
--   * app.current_group UNSET (every normal single-tenant request — SEAM 2 sets it only on the narrow
--     group paths) -> the group clause `group_id::text = current_setting('app.current_group', true)`
--     is `group_id::text = NULL` (or `= ''` on a pooled connection where a prior group txn registered
--     the GUC and SET LOCAL reverted it to the empty string) -> NULL/false for every row -> the table
--     matches NOTHING. The single-tenant path is therefore byte-identical: a normal request never
--     sees (or can write) a group row, and never sets app.current_group at all. (Text equality fails
--     closed here without erroring; ::uuid would RAISE on the empty-string residue — see the policy.)
--   * app.current_tenant UNSET -> the company_id clause is false -> matches nothing (the existing
--     fail-closed guarantee, unchanged).
--   * group set but tenant unset, or vice-versa -> the AND is false -> matches nothing.
--
-- Only ONE table here: group_trial_balance, the member trial-balance lines a group accumulates from
-- the TrialBalancePublished event. NO consolidation_ledger / summary / intercompany_match and NO close
-- logic — those are SEAM 3.
--
-- finance connects as the NON-superuser app role (no BYPASSRLS); FORCE ROW LEVEL SECURITY makes the
-- policy bind even the owning/migration role, so no connection bypasses the conjunction (rule 5).

-- ---------------------------------------------------------------------------
-- group_trial_balance — the per-member trial-balance lines a group ingests
-- ---------------------------------------------------------------------------
CREATE TABLE group_trial_balance (
    id                       UUID         NOT NULL PRIMARY KEY,

    -- The consolidation group this line belongs to. The SECOND RLS dimension: a line is visible only
    -- inside its own group's bound scope (app.current_group), in conjunction with the lead tenant.
    group_id                 UUID         NOT NULL,

    -- The MEMBER company whose books this line came from (the TrialBalancePublished company_id). This
    -- is a DIMENSION, NOT the tenant — a group line is owned by the LEAD (company_id below), and the
    -- member is just which member's figures it carries. It is deliberately NOT the RLS key: a member
    -- never reads the group's accumulated trial balance; only the lead (group scope) does.
    member_company_id        UUID         NOT NULL,

    -- The accounting period these lines reconcile for (YYYY-MM), from the event.
    period                   VARCHAR(7)   NOT NULL,

    -- The dimensional GL account + its account class. account_type is the classic five-class
    -- chart_of_account classification (ASSET | LIABILITY | EQUITY | REVENUE | EXPENSE), constrained.
    gl_account_code          VARCHAR(32)  NOT NULL,
    account_type             VARCHAR(16)  NOT NULL,
    posting_type             VARCHAR(32)  NOT NULL,

    -- Money (libs/money) = integer minor units + ISO-4217 code. NEVER a float (rule 8).
    amount_minor             BIGINT       NOT NULL,
    currency                 CHAR(3)      NOT NULL,

    -- Optional related-party / intercompany metadata carried for the SEAM-3 elimination pass. Nullable
    -- — most lines are not intercompany. No amount here; the amount is the Money columns above.
    related_party_counterparty_id UUID    NULL,
    intercompany_ref         VARCHAR(64)  NULL,

    -- Whether the figure is derived from ILLUSTRATIVE_PLACEHOLDER statutory rules (self-describing for
    -- an auditor), mirrored from the event.
    uses_illustrative_rules  BOOLEAN      NOT NULL,

    -- The id of the TrialBalancePublished event that produced this set of lines. UNIQUE per (event,
    -- account/posting line) is overkill; the event carries many lines, so the UNIQUE idempotency
    -- backstop is on (source_event_id, gl_account_code, posting_type): a re-delivered event can never
    -- double-insert a line, the database backstop behind the ProcessedEventStore dedupe (rule 3).
    source_event_id          UUID         NOT NULL,

    -- Auditable (libs/tenant) — company_id is the group's LEAD (the owning tenant; rule 4).
    created_at               TIMESTAMPTZ  NOT NULL,
    created_by               VARCHAR(255) NOT NULL,
    updated_at               TIMESTAMPTZ  NOT NULL,
    updated_by               VARCHAR(255) NOT NULL,
    version                  BIGINT       NOT NULL,
    company_id               VARCHAR(64)  NOT NULL,

    CONSTRAINT ck_group_trial_balance_account_type
        CHECK (account_type IN ('ASSET', 'LIABILITY', 'EQUITY', 'REVENUE', 'EXPENSE')),

    -- The idempotency backstop: at most one row per (event, account, posting kind). A re-delivered
    -- TrialBalancePublished upserts the same rows; the consumer's ProcessedEventStore dedupe makes a
    -- re-delivery a clean no-op, and this UNIQUE prevents a duplicate line even if it slipped past.
    CONSTRAINT uq_group_trial_balance_source_line
        UNIQUE (source_event_id, gl_account_code, posting_type)
);

ALTER TABLE group_trial_balance ENABLE ROW LEVEL SECURITY;
ALTER TABLE group_trial_balance FORCE ROW LEVEL SECURITY;

-- THE CONJUNCTION POLICY (the SEAM-2 Critical fix): a group row is visible/writable ONLY when its
-- group_id matches the bound group AND its company_id matches the bound LEAD tenant. Same expression
-- in USING and WITH CHECK, so a read, an INSERT, and an UPDATE are all gated identically. current_
-- setting(..., true) returns NULL (not an error) when a GUC is unset, so any unset side -> false ->
-- matches nothing (fail closed).
--
-- group_id is a UUID column; the GUC is text, so it is compared as group_id::text (PostgreSQL has no
-- uuid = text operator). company_id is already VARCHAR (text), the existing tenant-GUC comparison.
--
-- TYPE-EXACTNESS — WHERE IT LIVES, AND WHY *NOT* ::uuid HERE. The fragility this fix targets is a
-- future caller binding a non-canonical UUID string (uppercase / braced) that would text-mismatch
-- its own rows. The obvious move — cast the GUC to uuid: group_id = current_setting(...)::uuid — was
-- TRIED and REVERTED, because it BREAKS the byte-identical single-tenant path on a POOLED, REUSED
-- connection: a custom (placeholder) GUC assigned once via SET LOCAL reverts at transaction end NOT
-- to NULL but to the EMPTY STRING '', so a later plain single-tenant transaction on the same backend
-- (which never sets app.current_group) reads '' and '' ::uuid RAISES `invalid input syntax for type
-- uuid: ""` on EVERY group-table query — an error, not a clean fail-closed exclusion (proven by the
-- leak-residue regression test). NULLIF(...,'')::uuid would dodge the error, but the cleaner,
-- single-responsibility fix is to guarantee CANONICAL FORM at the ONE bind point instead: see
-- RlsConnectionInitializer.applyTenantAndGroup, which lowercases/validates the group id to a
-- canonical UUID before SET LOCAL. So both sides of this text comparison are always canonical and
-- the comparison is effectively type-exact, while the policy stays a plain, NULL-safe text equality
-- that fails closed (never errors) on the unset/empty/partial cases:
--   * app.current_group UNSET or '' -> the clause `group_id::text = NULL|''` is NULL|false for every
--     row -> excluded (the single-tenant path, including the pooled-residue case, sees no group row).
--   * app.current_tenant unset -> the company_id half is false -> matches nothing.
--   * group set but tenant unset (or vice-versa) -> the AND is false -> matches nothing.
CREATE POLICY group_trial_balance_group_isolation ON group_trial_balance
    USING (
        group_id::text = current_setting('app.current_group', true)
        AND company_id = current_setting('app.current_tenant', true)
    )
    WITH CHECK (
        group_id::text = current_setting('app.current_group', true)
        AND company_id = current_setting('app.current_tenant', true)
    );

-- The SEAM-3 consolidation pass scans a group's lines for a period; index that access path.
CREATE INDEX idx_group_trial_balance_group_period ON group_trial_balance (group_id, period);
