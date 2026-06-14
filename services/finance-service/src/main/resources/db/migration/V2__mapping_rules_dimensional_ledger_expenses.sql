-- finance-service V2 (#21) — mapping rules + dimensional ledger + expenses.
--
-- This is an ADDITIVE expand migration over V1 (never edit an applied migration). It:
--   1. adds the chart_of_account + mapping_rule reference tables and seeds a minimal
--      default chart and the default REVENUE / EXPENSE mapping rules (versioned,
--      effective-dated with the 9999-12-31 open-ended sentinel);
--   2. makes ledger_posting DIMENSIONAL by adding a gl_account_code column (every
--      posting now carries the account it resolved to via mapping_rule), backfilling
--      the existing REVENUE rows to the seeded revenue account before enforcing NOT NULL;
--   3. adds the consolidated_pnl read model (revenue + expense by company_id/period/
--      currency, net = revenue - expense), the P&L the dashboard reads.
--
-- DESIGN NOTE — chart_of_account and mapping_rule are GLOBAL reference data, not
-- tenant-scoped business rows, so they are deliberately NOT Auditable and NOT under
-- RLS (exactly like the processed_event consumer-infra table). They are the same
-- default chart + default GL mappings for every tenant in this slice — configuration,
-- not a company's books. The TENANT business data — ledger_posting and consolidated_pnl
-- — stays Auditable + under RLS as before. Keeping the reference data global means the
-- consumer can resolve a gl_account without binding it into a tenant's RLS scope (the
-- mapping is the same regardless of tenant), while a posting it produces is still written
-- under the event's company_id and gated by the ledger RLS policy. A future milestone can
-- promote these to per-company overrides (company_id nullable, tenant row shadows the
-- global default) without a breaking change.
--
-- GL mapping is DATA, never hardcoded in Java (ENGINEERING-STANDARDS §7 / config) —
-- the resolver reads these rows; it embeds no account codes.

-- ---------------------------------------------------------------------------
-- chart_of_account (global reference data)
-- ---------------------------------------------------------------------------
-- A minimal default chart: one account per posting kind we post in this slice plus a
-- suspense account for an unresolvable expense gl_hint (so money is never silently
-- dropped — see mapping_rule + the consumer). account_code is the natural key the
-- ledger's gl_account_code dimension references.
CREATE TABLE chart_of_account (
    account_code  VARCHAR(32)  NOT NULL PRIMARY KEY,
    name          VARCHAR(255) NOT NULL,

    -- ASSET | LIABILITY | EQUITY | REVENUE | EXPENSE (the five classic account classes).
    account_type  VARCHAR(16)  NOT NULL,

    CONSTRAINT ck_chart_of_account_type
        CHECK (account_type IN ('ASSET', 'LIABILITY', 'EQUITY', 'REVENUE', 'EXPENSE'))
);

INSERT INTO chart_of_account (account_code, name, account_type) VALUES
    ('4000', 'Sales Revenue',          'REVENUE'),
    ('5000', 'General Expense',        'EXPENSE'),
    ('5100', 'Cost of Goods Sold',     'EXPENSE'),
    ('5200', 'Supplies Expense',       'EXPENSE'),
    ('5300', 'Utilities Expense',      'EXPENSE'),
    -- The suspense account an unresolvable expense gl_hint posts to: money lands on the
    -- books (never dropped) but is quarantined here for an operator to reclassify.
    ('9999', 'Suspense — Unmapped Expense', 'EXPENSE');

-- ---------------------------------------------------------------------------
-- mapping_rule (global reference data — VERSIONED + EFFECTIVE-DATED)
-- ---------------------------------------------------------------------------
-- Maps a posting CRITERION to a chart_of_account. The criterion is (posting_type, gl_hint):
--   * REVENUE postings ignore gl_hint (gl_hint = '' the empty-criterion sentinel) and map
--     to the revenue account;
--   * EXPENSE postings map per gl_hint, with an EXPENSE + '' default catch-all for a hint
--     that matches no specific rule (but IS a known/expected fallback). A gl_hint that
--     resolves to NEITHER a specific rule NOR the default is genuinely unmappable and the
--     consumer routes it to the suspense account / DLT (see the consumer).
--
-- VERSIONED + EFFECTIVE-DATED: each (posting_type, gl_hint) criterion can have several
-- versions over time; effective_from/effective_to (the open-ended one = the 9999-12-31
-- sentinel, never NULL) bound when a version is in force. RESOLUTION picks the version
-- whose [effective_from, effective_to] window contains the event's occurred_at date — so
-- an event before a rule-version boundary resolves to the old account and one after it to
-- the new account (proven by test (c)).
CREATE TABLE mapping_rule (
    id              UUID         NOT NULL PRIMARY KEY,

    -- The criterion this rule keys on.
    posting_type    VARCHAR(32)  NOT NULL,
    -- The gl_hint the criterion matches; '' (empty string) is the "no/any hint" sentinel
    -- (REVENUE rules and the EXPENSE default catch-all use it). Not NULL so the unique
    -- key and equality match are simple and index-friendly.
    gl_hint         VARCHAR(64)  NOT NULL DEFAULT '',

    version         INTEGER      NOT NULL,

    -- The resolved account: a chart_of_account.account_code.
    gl_account_code VARCHAR(32)  NOT NULL REFERENCES chart_of_account (account_code),

    -- Effective-dating window. effective_to open-ended = 9999-12-31 sentinel, never NULL
    -- (ENGINEERING-STANDARDS §2.5 — keeps the range predicate index-friendly).
    effective_from  DATE         NOT NULL,
    effective_to    DATE         NOT NULL DEFAULT DATE '9999-12-31',

    CONSTRAINT ck_mapping_rule_posting_type
        CHECK (posting_type IN ('REVENUE', 'EXPENSE')),
    -- One version per criterion: (posting_type, gl_hint, version) is unique.
    CONSTRAINT uq_mapping_rule_criterion_version
        UNIQUE (posting_type, gl_hint, version)
);

-- The resolver scans by criterion and filters by the effective window; index that path.
CREATE INDEX idx_mapping_rule_resolution
    ON mapping_rule (posting_type, gl_hint, effective_from, effective_to);

-- Seed the default rules, all effective from a far-past date through the open-ended
-- sentinel (so any event resolves). gl_hint '' is the REVENUE catch-all and the EXPENSE
-- default catch-all.
INSERT INTO mapping_rule
    (id, posting_type, gl_hint, version, gl_account_code, effective_from, effective_to) VALUES
    -- REVENUE -> Sales Revenue (the existing SaleRecorded path).
    (gen_random_uuid(), 'REVENUE', '',          1, '4000', DATE '2000-01-01', DATE '9999-12-31'),
    -- EXPENSE default catch-all -> General Expense.
    (gen_random_uuid(), 'EXPENSE', '',          1, '5000', DATE '2000-01-01', DATE '9999-12-31'),
    -- Per-gl_hint expense rules.
    (gen_random_uuid(), 'EXPENSE', 'cogs',      1, '5100', DATE '2000-01-01', DATE '9999-12-31'),
    (gen_random_uuid(), 'EXPENSE', 'supplies',  1, '5200', DATE '2000-01-01', DATE '9999-12-31'),
    (gen_random_uuid(), 'EXPENSE', 'utilities', 1, '5300', DATE '2000-01-01', DATE '9999-12-31');

-- ---------------------------------------------------------------------------
-- ledger_posting — make it DIMENSIONAL: add gl_account_code
-- ---------------------------------------------------------------------------
-- Expand step: add nullable, backfill, then enforce NOT NULL (a multi-statement add in
-- one migration is fine — the table is append-only and this is its first dimension add).
ALTER TABLE ledger_posting ADD COLUMN gl_account_code VARCHAR(32);

-- Backfill every existing (REVENUE-only) posting to the seeded revenue account so the
-- NOT NULL can be enforced. New rows are stamped by the resolver.
UPDATE ledger_posting SET gl_account_code = '4000' WHERE gl_account_code IS NULL;

ALTER TABLE ledger_posting ALTER COLUMN gl_account_code SET NOT NULL;
ALTER TABLE ledger_posting
    ADD CONSTRAINT fk_ledger_posting_gl_account
        FOREIGN KEY (gl_account_code) REFERENCES chart_of_account (account_code);

-- The P&L aggregation scans the ledger by period and groups by gl_account_code /
-- posting_type within the bound tenant; index the dimension.
CREATE INDEX idx_ledger_posting_gl_account ON ledger_posting (gl_account_code);

-- ---------------------------------------------------------------------------
-- consolidated_pnl (the P&L read model — revenue + expense + net)
-- ---------------------------------------------------------------------------
-- One row per (company_id, period, currency); revenue_minor and expense_minor each
-- accumulate as postings land. net = revenue_minor - expense_minor (computed on read,
-- not stored, so it can never drift from its components). Maintained by the SAME atomic
-- INSERT … ON CONFLICT … DO UPDATE per posting as the existing consolidated_revenue, so
-- there is no read-modify-write window. Auditable + under RLS like consolidated_revenue:
-- an application-maintained (not CDC-derived) read model, so rule-4 + rule-5 apply.
CREATE TABLE consolidated_pnl (
    id              UUID         NOT NULL PRIMARY KEY,

    period          VARCHAR(7)   NOT NULL,

    -- Money is minor units + ISO-4217 code, NEVER a float. Revenue and expense are kept
    -- separately (net is revenue - expense, derived on read).
    revenue_minor   BIGINT       NOT NULL DEFAULT 0,
    expense_minor   BIGINT       NOT NULL DEFAULT 0,
    currency        CHAR(3)      NOT NULL,

    -- Auditable (libs/tenant) — present on every Native table.
    created_at      TIMESTAMPTZ  NOT NULL,
    created_by      VARCHAR(255) NOT NULL,
    updated_at      TIMESTAMPTZ  NOT NULL,
    updated_by      VARCHAR(255) NOT NULL,
    version         BIGINT       NOT NULL,
    company_id      VARCHAR(64)  NOT NULL,

    CONSTRAINT uq_consolidated_pnl_company_period_currency
        UNIQUE (company_id, period, currency)
);

ALTER TABLE consolidated_pnl ENABLE ROW LEVEL SECURITY;
ALTER TABLE consolidated_pnl FORCE ROW LEVEL SECURITY;

CREATE POLICY consolidated_pnl_tenant_isolation ON consolidated_pnl
    USING (company_id = current_setting('app.current_tenant', true))
    WITH CHECK (company_id = current_setting('app.current_tenant', true));
