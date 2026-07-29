-- finance-service V29 — Bank accounts + statement lines (Phase 3, ADR 0016).
--
-- AR receipts, AP payments, and POS sales all post to the CASH_CLEARING account (1900) — cash "in
-- transit" not yet reconciled to a real bank. This adds real bank accounts and the statement lines
-- that get reconciled: reconciling a line posts a balanced transfer that sweeps the clearing balance
-- into the bank (or records a bank-only fee/interest). Non-invasive — AR/AP/POS are untouched.
--
-- Conventions (mirror V24/V27): Auditable (6 cols) + ENABLE/FORCE ROW LEVEL SECURITY, tenant policy
-- on app.current_tenant (rules 4 + 5). Money = BIGINT minor + CHAR(3), never a float (rule 8).
-- bank_account_id is a same-service FK (finance owns the bank sub-ledger).
--
-- All bank accounts share ONE `BANK` GL control account (1000, seeded in V30), exactly as AR uses one
-- 1200 and AP one 2000 — per-account balances live in this sub-ledger, not the GL (ADR 0016).

-- ---------------------------------------------------------------------------
-- bank_account (a company's real bank/cash account)
-- ---------------------------------------------------------------------------
CREATE TABLE bank_account (
    id             UUID         NOT NULL PRIMARY KEY,
    name           VARCHAR(255) NOT NULL,
    -- Stored MASKED to last-4 only (e.g. "****1234"); the FULL bank account number is rule-6 PII and
    -- is NEVER persisted (masked server-side in BankAccount, since finance-service has no column
    -- encryption). Nullable.
    account_number VARCHAR(20),
    currency       CHAR(3)      NOT NULL,
    active         BOOLEAN      NOT NULL DEFAULT TRUE,

    -- Auditable (libs/tenant).
    created_at  TIMESTAMPTZ  NOT NULL,
    created_by  VARCHAR(255) NOT NULL,
    updated_at  TIMESTAMPTZ  NOT NULL,
    updated_by  VARCHAR(255) NOT NULL,
    version     BIGINT       NOT NULL,
    company_id  VARCHAR(64)  NOT NULL
);

ALTER TABLE bank_account ENABLE ROW LEVEL SECURITY;
ALTER TABLE bank_account FORCE ROW LEVEL SECURITY;

CREATE POLICY bank_account_tenant_isolation ON bank_account
    USING      (company_id = current_setting('app.current_tenant', true))
    WITH CHECK (company_id = current_setting('app.current_tenant', true));

CREATE INDEX idx_bank_account_company ON bank_account (company_id);

-- ---------------------------------------------------------------------------
-- bank_statement_line (a line from the bank statement, reconciled to the GL)
-- ---------------------------------------------------------------------------
-- amount_minor is SIGNED: positive = deposit (money into the account), negative = withdrawal.
-- On reconcile, reconciled_category picks the contra (CLEARING sweep / BANK_FEE / INTEREST) and a
-- balanced transfer journal entry is posted and linked here.
CREATE TABLE bank_statement_line (
    id                  UUID         NOT NULL PRIMARY KEY,
    bank_account_id     UUID         NOT NULL REFERENCES bank_account (id),
    line_date           DATE         NOT NULL,

    -- Money (signed minor units + ISO-4217). NEVER a float (rule 8).
    amount_minor        BIGINT       NOT NULL,
    currency            CHAR(3)      NOT NULL,

    description         VARCHAR(500),
    reference           VARCHAR(128),

    status              VARCHAR(16)  NOT NULL DEFAULT 'UNRECONCILED',
    -- Set on reconcile: which contra the line was posted against.
    reconciled_category VARCHAR(16),
    -- The balanced transfer journal entry raised on reconcile; NULL while UNRECONCILED.
    journal_entry_id    UUID,

    -- Auditable.
    created_at  TIMESTAMPTZ  NOT NULL,
    created_by  VARCHAR(255) NOT NULL,
    updated_at  TIMESTAMPTZ  NOT NULL,
    updated_by  VARCHAR(255) NOT NULL,
    version     BIGINT       NOT NULL,
    company_id  VARCHAR(64)  NOT NULL,

    CONSTRAINT ck_bank_line_amount   CHECK (amount_minor <> 0),
    CONSTRAINT ck_bank_line_status   CHECK (status IN ('UNRECONCILED', 'RECONCILED')),
    CONSTRAINT ck_bank_line_category
        CHECK (reconciled_category IS NULL
               OR reconciled_category IN ('CLEARING', 'BANK_FEE', 'INTEREST'))
);

ALTER TABLE bank_statement_line ENABLE ROW LEVEL SECURITY;
ALTER TABLE bank_statement_line FORCE ROW LEVEL SECURITY;

CREATE POLICY bank_statement_line_tenant_isolation ON bank_statement_line
    USING      (company_id = current_setting('app.current_tenant', true))
    WITH CHECK (company_id = current_setting('app.current_tenant', true));

CREATE INDEX idx_bank_line_company_account_status
    ON bank_statement_line (company_id, bank_account_id, status);
