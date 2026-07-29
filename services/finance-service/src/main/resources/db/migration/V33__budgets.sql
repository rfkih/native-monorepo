-- finance-service V33 — Budgets (Phase 5, ADR 0019).
--
-- ============================================================================================
-- A budget is a per-MONTH set of planned amounts per account: one `budget` (name + period) with
-- many `budget_line`s (account_code -> planned amount_minor). It is PLANNING DATA — it never posts
-- to the GL and raises no journal entry. The budget-vs-actual report joins each line's planned
-- amount against the GL-derived actual for the budget's period (glTrialBalance), computing variance.
--
-- Mirrors the AR/AP parent+child DDL (V24 invoice/invoice_line, V27 bill/bill_line): every table
-- extends Auditable (6 cols) + is under ENABLE/FORCE ROW LEVEL SECURITY, tenant policy keyed on
-- app.current_tenant (rules 4 + 5, fail-closed). Money is BIGINT minor units + ISO-4217 CHAR(3),
-- never a float (rule 8). budget_line.account_code FK -> chart_of_account (the global reference
-- table) so a line can only target a real account.
-- ============================================================================================

-- ---------------------------------------------------------------------------
-- budget (the parent — a named monthly budget)
-- ---------------------------------------------------------------------------
CREATE TABLE budget (
    id          UUID         NOT NULL PRIMARY KEY,
    name        VARCHAR(255) NOT NULL,
    -- The month this budget plans for (YYYY-MM); actuals compare against this period's GL activity.
    period      VARCHAR(7)   NOT NULL,
    currency    CHAR(3)      NOT NULL,

    -- Auditable (libs/tenant) — present on every Native table.
    created_at  TIMESTAMPTZ  NOT NULL,
    created_by  VARCHAR(255) NOT NULL,
    updated_at  TIMESTAMPTZ  NOT NULL,
    updated_by  VARCHAR(255) NOT NULL,
    version     BIGINT       NOT NULL,
    company_id  VARCHAR(64)  NOT NULL,

    -- At most one budget of a given name per period per tenant (a duplicate is a 409).
    CONSTRAINT uq_budget_company_name_period UNIQUE (company_id, name, period)
);

ALTER TABLE budget ENABLE ROW LEVEL SECURITY;
ALTER TABLE budget FORCE ROW LEVEL SECURITY;

CREATE POLICY budget_tenant_isolation ON budget
    USING      (company_id = current_setting('app.current_tenant', true))
    WITH CHECK (company_id = current_setting('app.current_tenant', true));

CREATE INDEX idx_budget_company_period ON budget (company_id, period);

-- ---------------------------------------------------------------------------
-- budget_line (the child — one account's planned amount)
-- ---------------------------------------------------------------------------
CREATE TABLE budget_line (
    id           UUID        NOT NULL PRIMARY KEY,
    budget_id    UUID        NOT NULL REFERENCES budget (id),
    -- The account this line plans for; FK to the global chart_of_account (not RLS).
    account_code VARCHAR(32) NOT NULL REFERENCES chart_of_account (account_code),
    amount_minor BIGINT      NOT NULL,
    currency     CHAR(3)     NOT NULL,

    -- Auditable.
    created_at   TIMESTAMPTZ  NOT NULL,
    created_by   VARCHAR(255) NOT NULL,
    updated_at   TIMESTAMPTZ  NOT NULL,
    updated_by   VARCHAR(255) NOT NULL,
    version      BIGINT       NOT NULL,
    company_id   VARCHAR(64)  NOT NULL,

    -- One planned amount per account per budget; a planned amount is a non-negative target.
    CONSTRAINT uq_budget_line_account UNIQUE (budget_id, account_code),
    CONSTRAINT ck_budget_line_amount  CHECK (amount_minor >= 0)
);

ALTER TABLE budget_line ENABLE ROW LEVEL SECURITY;
ALTER TABLE budget_line FORCE ROW LEVEL SECURITY;

CREATE POLICY budget_line_tenant_isolation ON budget_line
    USING      (company_id = current_setting('app.current_tenant', true))
    WITH CHECK (company_id = current_setting('app.current_tenant', true));

CREATE INDEX idx_budget_line_budget ON budget_line (budget_id);
