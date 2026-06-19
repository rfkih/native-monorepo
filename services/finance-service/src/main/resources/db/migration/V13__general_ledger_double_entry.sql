-- finance-service V13 — double-entry General Ledger (accounting foundation).
--
-- ============================================================================================================================
-- ILLUSTRATIVE PLACEHOLDER — SME-GATED — REPLACE VIA HIGHER-VERSION DATA
-- ============================================================================================================================
-- The posting templates and role→account mappings seeded at the bottom of this migration carry
--     uses_illustrative = TRUE
-- They are INTENTIONAL PLACEHOLDERS that make the double-entry pipeline end-to-end and balanced
-- TODAY, while the real Indonesian COA (SAK-EMKM), account mappings, and PPN/PPh treatment are
-- being gathered from an accounting + tax SME.
--
-- To replace with real rules (NO code change required):
--   INSERT INTO posting_template (id, event_kind, version, uses_illustrative, effective_from, effective_to)
--       VALUES (gen_random_uuid(), 'SALE', 2, FALSE, <date>, DATE '9999-12-31');
--   INSERT INTO posting_template_line (...) -- the SME-designed lines for this template
--   INSERT INTO role_account_map (id, account_role, gl_account_code, version, uses_illustrative, effective_from, effective_to)
--       VALUES (..., 'REVENUE', '<real-account>', 2, FALSE, <date>, DATE '9999-12-31');
-- Higher-version rows are preferred by the resolver (ORDER BY version DESC LIMIT 1), so existing
-- illustrative rows remain as an audit trail and DO NOT need to be deleted.
-- ============================================================================================================================
--
-- This migration is ADDITIVE (never edit an applied migration). It:
--   1. Adds chart_of_account row 1900 (Illustrative Cash/Bank Clearing, ASSET) — the placeholder
--      clearing account that offsets REVENUE and EXPENSE lines in the illustrative seeds.
--   2. Creates journal_entry + journal_line (Auditable + FORCE RLS, tenant policy on
--      app.current_tenant; UNIQUE source_event_id; UNIQUE (entry_id, line_no); single-sided CHECK;
--      FK line.account_code → chart_of_account; indexes).
--   3. Creates posting_template + posting_template_line + role_account_map (GLOBAL reference data —
--      NOT Auditable, NOT RLS — versioned + effective-dated, 9999-12-31 sentinel; same global-ref
--      pattern as mapping_rule from V2).
--   4. Seeds the three illustrative templates (SALE, EXPENSE, LABOR) and their role→account maps.
--
-- DESIGN: journal_entry + journal_line are the double-entry GL; they live ALONGSIDE the existing
-- dimensional ledger_posting (which feeds the P&L read models and the within-company close) and are
-- written in the SAME transaction as each ledger_posting. The GL adds balance-sheet capability
-- (trial balance, BS, tax) without touching or replacing the dimensional ledger.

-- ---------------------------------------------------------------------------
-- 1. Chart-of-account: illustrative clearing account
-- ---------------------------------------------------------------------------
-- A minimal placeholder ASSET account that the illustrative SALE and EXPENSE templates
-- use as the offsetting debit/credit leg. The real cash/AR/AP accounts are SME-supplied.
INSERT INTO chart_of_account (account_code, name, account_type)
VALUES ('1900', 'Illustrative Cash/Bank Clearing (PLACEHOLDER — replace via SME)', 'ASSET')
ON CONFLICT (account_code) DO NOTHING;

-- ---------------------------------------------------------------------------
-- 2a. journal_entry (Auditable + FORCE RLS)
-- ---------------------------------------------------------------------------
CREATE TABLE journal_entry (
    id                      UUID         NOT NULL PRIMARY KEY,
    period                  VARCHAR(7)   NOT NULL,
    occurred_at             TIMESTAMPTZ  NOT NULL,
    description             VARCHAR(500) NOT NULL,
    status                  VARCHAR(16)  NOT NULL DEFAULT 'POSTED',
    posting_role            VARCHAR(16)  NOT NULL DEFAULT 'PRIMARY',

    -- ISO-4217 currency; all lines in the entry share this currency.
    currency                CHAR(3)      NOT NULL,

    -- The consumed event UUID — idempotency key; one entry per source event.
    source_event_id         UUID         NOT NULL,

    -- Whether the entry was produced from an illustrative-placeholder posting rule.
    uses_illustrative_rules BOOLEAN      NOT NULL DEFAULT FALSE,

    -- Auditable (libs/tenant): 6 cols on every Native table (rule 4).
    created_at              TIMESTAMPTZ  NOT NULL,
    created_by              VARCHAR(255) NOT NULL,
    updated_at              TIMESTAMPTZ  NOT NULL,
    updated_by              VARCHAR(255) NOT NULL,
    version                 BIGINT       NOT NULL,
    company_id              VARCHAR(64)  NOT NULL,

    CONSTRAINT uq_journal_entry_source_event    UNIQUE (source_event_id),
    CONSTRAINT ck_journal_entry_status          CHECK (status IN ('POSTED')),
    CONSTRAINT ck_journal_entry_posting_role    CHECK (posting_role IN ('PRIMARY', 'REVERSAL'))
);

ALTER TABLE journal_entry ENABLE ROW LEVEL SECURITY;
ALTER TABLE journal_entry FORCE ROW LEVEL SECURITY;

CREATE POLICY journal_entry_tenant_isolation ON journal_entry
    USING      (company_id = current_setting('app.current_tenant', true))
    WITH CHECK (company_id = current_setting('app.current_tenant', true));

-- Aggregate the P&L-period trial balance (the canonical read path).
CREATE INDEX idx_journal_entry_company_period ON journal_entry (company_id, period);
-- For entry-id FK lookup from journal_line.
CREATE INDEX idx_journal_entry_id ON journal_entry (id);

-- ---------------------------------------------------------------------------
-- 2b. journal_line (Auditable + FORCE RLS)
-- ---------------------------------------------------------------------------
CREATE TABLE journal_line (
    id              UUID        NOT NULL PRIMARY KEY,
    entry_id        UUID        NOT NULL REFERENCES journal_entry (id),
    line_no         INTEGER     NOT NULL,

    -- FK to chart_of_account: every line must reference a valid account.
    account_code    VARCHAR(32) NOT NULL REFERENCES chart_of_account (account_code),

    -- Two longs + ONE currency (NOT two MoneyEmbeddable — avoids a Hibernate duplicate-column clash).
    -- Money is NEVER a float (CLAUDE.md rule 8).
    debit_minor     BIGINT      NOT NULL,
    credit_minor    BIGINT      NOT NULL,
    currency        CHAR(3)     NOT NULL,

    -- Auditable (libs/tenant): 6 cols on every Native table (rule 4).
    created_at      TIMESTAMPTZ NOT NULL,
    created_by      VARCHAR(255) NOT NULL,
    updated_at      TIMESTAMPTZ NOT NULL,
    updated_by      VARCHAR(255) NOT NULL,
    version         BIGINT      NOT NULL,
    company_id      VARCHAR(64) NOT NULL,

    CONSTRAINT uq_journal_line_entry_line   UNIQUE (entry_id, line_no),
    -- Single-sided invariant: exactly one of debit/credit is zero, the other strictly positive.
    CONSTRAINT ck_journal_line_single_sided CHECK ((debit_minor = 0) <> (credit_minor = 0))
);

ALTER TABLE journal_line ENABLE ROW LEVEL SECURITY;
ALTER TABLE journal_line FORCE ROW LEVEL SECURITY;

CREATE POLICY journal_line_tenant_isolation ON journal_line
    USING      (company_id = current_setting('app.current_tenant', true))
    WITH CHECK (company_id = current_setting('app.current_tenant', true));

-- The GL trial-balance query aggregates by account_code within a period (via entry join).
CREATE INDEX idx_journal_line_account_code ON journal_line (account_code);
-- Entry-level line scan (to reverse a journal, or to load an entry's lines).
CREATE INDEX idx_journal_line_entry_id ON journal_line (entry_id);

-- ---------------------------------------------------------------------------
-- 3a. posting_template (global reference data — NOT Auditable, NOT RLS)
-- ---------------------------------------------------------------------------
-- The SME plug-in: maps an EventKind to an ordered set of posting template lines.
-- Versioned + effective-dated (9999-12-31 sentinel) — the same global-ref pattern as
-- mapping_rule (V2). Higher version + matching effective window wins (ORDER BY version DESC LIMIT 1).
-- uses_illustrative marks whether this template carries placeholder-quality rules.
CREATE TABLE posting_template (
    id               UUID        NOT NULL PRIMARY KEY,
    event_kind       VARCHAR(32) NOT NULL,
    version          INTEGER     NOT NULL,
    uses_illustrative BOOLEAN    NOT NULL DEFAULT TRUE,
    effective_from   DATE        NOT NULL,
    effective_to     DATE        NOT NULL DEFAULT DATE '9999-12-31',

    CONSTRAINT ck_posting_template_event_kind CHECK (event_kind IN ('SALE', 'EXPENSE', 'LABOR')),
    CONSTRAINT uq_posting_template_kind_version UNIQUE (event_kind, version)
);

CREATE INDEX idx_posting_template_resolution
    ON posting_template (event_kind, effective_from, effective_to);

-- ---------------------------------------------------------------------------
-- 3b. posting_template_line (child of posting_template; global reference data)
-- ---------------------------------------------------------------------------
CREATE TABLE posting_template_line (
    id              UUID        NOT NULL PRIMARY KEY,
    template_id     UUID        NOT NULL REFERENCES posting_template (id),
    line_no         INTEGER     NOT NULL,
    account_role    VARCHAR(32) NOT NULL,
    side            VARCHAR(8)  NOT NULL,
    amount_basis    VARCHAR(32) NOT NULL DEFAULT 'GROSS',

    CONSTRAINT uq_template_line_no     UNIQUE (template_id, line_no),
    CONSTRAINT ck_template_line_side   CHECK (side IN ('DEBIT', 'CREDIT')),
    CONSTRAINT ck_template_amount_basis CHECK (amount_basis IN ('GROSS'))
);

-- ---------------------------------------------------------------------------
-- 3c. role_account_map (global reference data — NOT Auditable, NOT RLS)
-- ---------------------------------------------------------------------------
-- Maps a semantic AccountRole to a concrete chart_of_account.account_code.
-- Versioned + effective-dated; the SME plug-in for the real COA mappings.
CREATE TABLE role_account_map (
    id               UUID        NOT NULL PRIMARY KEY,
    account_role     VARCHAR(32) NOT NULL,
    gl_account_code  VARCHAR(32) NOT NULL REFERENCES chart_of_account (account_code),
    version          INTEGER     NOT NULL,
    uses_illustrative BOOLEAN    NOT NULL DEFAULT TRUE,
    effective_from   DATE        NOT NULL,
    effective_to     DATE        NOT NULL DEFAULT DATE '9999-12-31',

    CONSTRAINT uq_role_account_map_role_version UNIQUE (account_role, version)
);

CREATE INDEX idx_role_account_map_resolution
    ON role_account_map (account_role, effective_from, effective_to);

-- ---------------------------------------------------------------------------
-- 4. ILLUSTRATIVE SEEDS — version 1, uses_illustrative = TRUE
-- ============================================================================================================================
-- REPLACE THESE WITH REAL RULES: insert version ≥ 2 rows with uses_illustrative = FALSE.
-- The resolver (ORDER BY version DESC LIMIT 1) will prefer higher-version rows automatically.
-- These version-1 rows remain as an audit trail and do NOT need to be deleted.
-- ============================================================================================================================

-- posting_template seeds
INSERT INTO posting_template (id, event_kind, version, uses_illustrative, effective_from, effective_to) VALUES
    (gen_random_uuid(), 'SALE',    1, TRUE, DATE '2000-01-01', DATE '9999-12-31'),
    (gen_random_uuid(), 'EXPENSE', 1, TRUE, DATE '2000-01-01', DATE '9999-12-31'),
    (gen_random_uuid(), 'LABOR',   1, TRUE, DATE '2000-01-01', DATE '9999-12-31');

-- SALE template: Dr CASH_CLEARING (1900) / Cr REVENUE (4000)
INSERT INTO posting_template_line (id, template_id, line_no, account_role, side, amount_basis)
SELECT gen_random_uuid(), pt.id, 1, 'CASH_CLEARING', 'DEBIT',  'GROSS'
  FROM posting_template pt WHERE pt.event_kind = 'SALE' AND pt.version = 1;
INSERT INTO posting_template_line (id, template_id, line_no, account_role, side, amount_basis)
SELECT gen_random_uuid(), pt.id, 2, 'REVENUE',       'CREDIT', 'GROSS'
  FROM posting_template pt WHERE pt.event_kind = 'SALE' AND pt.version = 1;

-- EXPENSE template: Dr EXPENSE (5000) / Cr CASH_CLEARING (1900)
INSERT INTO posting_template_line (id, template_id, line_no, account_role, side, amount_basis)
SELECT gen_random_uuid(), pt.id, 1, 'EXPENSE',        'DEBIT',  'GROSS'
  FROM posting_template pt WHERE pt.event_kind = 'EXPENSE' AND pt.version = 1;
INSERT INTO posting_template_line (id, template_id, line_no, account_role, side, amount_basis)
SELECT gen_random_uuid(), pt.id, 2, 'CASH_CLEARING',  'CREDIT', 'GROSS'
  FROM posting_template pt WHERE pt.event_kind = 'EXPENSE' AND pt.version = 1;

-- LABOR template: Dr LABOR_EXPENSE (6000) / Cr LABOR_CLEARING (6900)
INSERT INTO posting_template_line (id, template_id, line_no, account_role, side, amount_basis)
SELECT gen_random_uuid(), pt.id, 1, 'LABOR_EXPENSE',  'DEBIT',  'GROSS'
  FROM posting_template pt WHERE pt.event_kind = 'LABOR' AND pt.version = 1;
INSERT INTO posting_template_line (id, template_id, line_no, account_role, side, amount_basis)
SELECT gen_random_uuid(), pt.id, 2, 'LABOR_CLEARING', 'CREDIT', 'GROSS'
  FROM posting_template pt WHERE pt.event_kind = 'LABOR' AND pt.version = 1;

-- role_account_map seeds (version 1, illustrative)
-- CASH_CLEARING → 1900 (Illustrative Cash/Bank Clearing)
-- REVENUE       → 4000 (Sales Revenue, from V2)
-- EXPENSE       → 5000 (General Expense, from V2)
-- LABOR_EXPENSE → 6000 (Salaries Expense, from V3)
-- LABOR_CLEARING→ 6900 (Unallocated-Labor-Clearing, from V3)
-- SUSPENSE      → 9999 (Suspense — Unmapped Expense, from V2)
INSERT INTO role_account_map (id, account_role, gl_account_code, version, uses_illustrative, effective_from, effective_to) VALUES
    (gen_random_uuid(), 'CASH_CLEARING',  '1900', 1, TRUE, DATE '2000-01-01', DATE '9999-12-31'),
    (gen_random_uuid(), 'REVENUE',        '4000', 1, TRUE, DATE '2000-01-01', DATE '9999-12-31'),
    (gen_random_uuid(), 'EXPENSE',        '5000', 1, TRUE, DATE '2000-01-01', DATE '9999-12-31'),
    (gen_random_uuid(), 'LABOR_EXPENSE',  '6000', 1, TRUE, DATE '2000-01-01', DATE '9999-12-31'),
    (gen_random_uuid(), 'LABOR_CLEARING', '6900', 1, TRUE, DATE '2000-01-01', DATE '9999-12-31'),
    (gen_random_uuid(), 'SUSPENSE',       '9999', 1, TRUE, DATE '2000-01-01', DATE '9999-12-31');
