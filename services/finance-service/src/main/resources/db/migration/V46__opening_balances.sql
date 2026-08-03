-- finance-service V46 — OPENING BALANCES & business migration (ADR 0037). Two sections:
--   (A) GLOBAL reference data — equity accounts + role maps so an opening balance sheet has real
--       targets. Same global-ref pattern as V13/V35 (NOT Auditable, NOT RLS, shared by every
--       tenant); versioned + effective-dated + uses_illustrative=TRUE placeholders an SME replaces
--       at a higher version. ON CONFLICT DO NOTHING keeps re-seeds idempotent.
--   (B) TENANT table `company_opening_balance` — the once-only marker recording that a company
--       posted its opening balance sheet (Auditable + FORCE RLS, rule 4/5).
--
-- ============================================================================================================================
-- ILLUSTRATIVE PLACEHOLDER — SME-GATED — account codes 3000/3100/3900 (equity), 1100 (inventory),
-- 2700 (other liabilities/loans) are illustrative placeholders. An SME seeds the real chart +
-- effective-dated role_account_map rows at version >= 2 (uses_illustrative=FALSE). See V13's header
-- for the replace-via-higher-version recipe.
-- ============================================================================================================================
--
-- The opening entry (OpeningBalanceWriter, an ad-hoc balanced JournalEntry — the disposal/tax/asset
-- pattern, NO Kafka event) touches BALANCE-SHEET accounts only (ASSET/LIABILITY/EQUITY; REVENUE and
-- EXPENSE are rejected — prior profit is a Retained Earnings credit, never a re-posted P&L). It
-- auto-plugs its residual to OPENING_BALANCE_EQUITY (3900) so it always balances; brought-forward
-- fixed assets (V47) credit the same 3900 their net book value. `BalanceSheetReader` already
-- classifies EQUITY accounts credit-normal from chart_of_account.account_type, so posted equity
-- flows through with no reader change.
--
-- Money (rule 8): every *_minor column is an integer in minor units; `currency` is one ISO-4217 code
-- per entry. Never a float.

-- ---------------------------------------------------------------------------------------------------------------------------
-- (A) GLOBAL reference data — equity + supporting balance-sheet accounts, and the role maps.
-- ---------------------------------------------------------------------------------------------------------------------------

INSERT INTO chart_of_account (account_code, name, account_type) VALUES
    ('3000', 'Owner''s / Share Capital (PLACEHOLDER — replace via SME)',          'EQUITY'),
    ('3100', 'Retained Earnings — prior years (PLACEHOLDER — replace via SME)',   'EQUITY'),
    ('3900', 'Opening Balance Equity (PLACEHOLDER — replace via SME)',            'EQUITY'),
    ('1100', 'Inventory (PLACEHOLDER — replace via SME)',                         'ASSET'),
    ('2700', 'Other Liabilities / Loans (PLACEHOLDER — replace via SME)',         'LIABILITY')
ON CONFLICT (account_code) DO NOTHING;

INSERT INTO role_account_map
    (id, account_role, gl_account_code, version, uses_illustrative, effective_from, effective_to)
VALUES
    (gen_random_uuid(), 'OPENING_BALANCE_EQUITY', '3900', 1, TRUE, DATE '2000-01-01', DATE '9999-12-31'),
    (gen_random_uuid(), 'OWNER_CAPITAL',          '3000', 1, TRUE, DATE '2000-01-01', DATE '9999-12-31'),
    (gen_random_uuid(), 'RETAINED_EARNINGS',      '3100', 1, TRUE, DATE '2000-01-01', DATE '9999-12-31')
ON CONFLICT (account_role, version) DO NOTHING;

-- ---------------------------------------------------------------------------------------------------------------------------
-- (B) TENANT table — the once-only opening-balance marker (mirrors platform_settlement's shape).
-- ---------------------------------------------------------------------------------------------------------------------------

CREATE TABLE company_opening_balance (
    id                UUID         NOT NULL PRIMARY KEY,

    -- The posted opening JournalEntry (Dr assets / Cr liabilities + equity + the OBE plug) — plain
    -- UUID, no FK (the settlement-table family convention: the entry is built + persisted in the
    -- SAME transaction as this row, so ordering already guarantees existence).
    opening_entry_id  UUID         NOT NULL,

    -- The go-live / as-of date the user chose; `period` = periodOf(as_of_date) (UTC, YYYY-MM), the
    -- real month the entry posts into (never a "OPENING" sentinel — statements filter period<=asOf
    -- lexicographically, and a sentinel would sort after every real month).
    as_of_date        DATE         NOT NULL,
    period            VARCHAR(7)   NOT NULL,

    -- Audit info. `total_minor` = Σdebit of the posted entry. `plug_minor` = the signed residual
    -- credited to Opening Balance Equity (negative = it was a debit; zero = the user's sheet already
    -- balanced). Money rule 8.
    total_minor       BIGINT       NOT NULL,
    plug_minor        BIGINT       NOT NULL,
    currency          CHAR(3)      NOT NULL,

    -- The replay payload fingerprint: SHA-256 (hex) of as-of + currency + the canonicalized
    -- (sorted) opening lines. A same-key replay whose fingerprint differs is a 409 conflict (not a
    -- silent 200 with the original) — stronger than comparing the total alone, which two different
    -- line-sets could share.
    payload_fingerprint CHAR(64)   NOT NULL,

    -- The Idempotency-Key that produced this row: keyless -> 400, same-key + same payload -> 200
    -- (this row), same-key + different payload -> 409 conflict, a DIFFERENT key -> 409 already-recorded
    -- (once-only; there is no amend/revise flow).
    idempotency_key   VARCHAR(64)  NOT NULL,

    -- Auditable (libs/tenant): 6 cols on every Native table (rule 4).
    created_at        TIMESTAMPTZ  NOT NULL,
    created_by        VARCHAR(255) NOT NULL,
    updated_at        TIMESTAMPTZ  NOT NULL,
    updated_by        VARCHAR(255) NOT NULL,
    version           BIGINT       NOT NULL,
    company_id        VARCHAR(64)  NOT NULL,

    CONSTRAINT ck_company_opening_balance_total CHECK (total_minor > 0),

    -- ONCE-ONLY per company: a company records its opening balance sheet exactly once. This is also
    -- the DB backstop behind the app-level already-recorded 409.
    CONSTRAINT uq_company_opening_balance_company UNIQUE (company_id)
);

ALTER TABLE company_opening_balance ENABLE ROW LEVEL SECURITY;
ALTER TABLE company_opening_balance FORCE ROW LEVEL SECURITY;

CREATE POLICY company_opening_balance_tenant_isolation ON company_opening_balance
    USING      (company_id = current_setting('app.current_tenant', true))
    WITH CHECK (company_id = current_setting('app.current_tenant', true));
