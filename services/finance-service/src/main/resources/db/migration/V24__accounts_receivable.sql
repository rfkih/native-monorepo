-- finance-service V24 — Accounts Receivable sub-ledger (Phase 1 AR).
--
-- Introduces the FIRST customer/party dimension in Native. AR invoices post to the existing
-- double-entry GL (journal_entry / journal_line) in the SAME transaction as the sub-ledger write;
-- these tables exist so aging-by-customer is possible (a journal_line carries only account_code, no
-- counterparty dimension — see ADR 0014).
--
-- Conventions (mirror V21 / V13):
--   * Every table extends Auditable (6 cols) + is under ENABLE/FORCE ROW LEVEL SECURITY, tenant
--     policy keyed on app.current_tenant (rules 4 + 5). Fail-closed: current_setting(...,true) is
--     NULL with no tenant bound → predicate false.
--   * Money is integer minor units (BIGINT) + ISO-4217 currency CHAR(3); NEVER a float (rule 8).
--   * customer_id / invoice_id are SAME-service FKs (finance owns AR) — not cross-service (rule 1).
--
-- ILLUSTRATIVE: output tax (PPN) on an invoice is computed at an ILLUSTRATIVE placeholder rate in
-- InvoiceWriter (the invoice carries uses_illustrative_rules=TRUE); the real rate/regime is
-- SME-gated, the same gate as the POS PB1/PPN tax (ADR 0006). No tax rate is stored here.

-- ---------------------------------------------------------------------------
-- customer (the AR party — the first customer/counterparty dimension in Native)
-- ---------------------------------------------------------------------------
CREATE TABLE customer (
    id          UUID         NOT NULL PRIMARY KEY,
    name        VARCHAR(255) NOT NULL,
    email       VARCHAR(320),          -- business contact address; nullable
    tax_id      VARCHAR(64),           -- NPWP / tax registration id; nullable
    active      BOOLEAN      NOT NULL DEFAULT TRUE,

    -- Auditable (libs/tenant) — present on every Native table.
    created_at  TIMESTAMPTZ  NOT NULL,
    created_by  VARCHAR(255) NOT NULL,
    updated_at  TIMESTAMPTZ  NOT NULL,
    updated_by  VARCHAR(255) NOT NULL,
    version     BIGINT       NOT NULL,
    company_id  VARCHAR(64)  NOT NULL
);

ALTER TABLE customer ENABLE ROW LEVEL SECURITY;
ALTER TABLE customer FORCE ROW LEVEL SECURITY;

CREATE POLICY customer_tenant_isolation ON customer
    USING      (company_id = current_setting('app.current_tenant', true))
    WITH CHECK (company_id = current_setting('app.current_tenant', true));

CREATE INDEX idx_customer_company ON customer (company_id);

-- ---------------------------------------------------------------------------
-- invoice (the AR document — draft → issued → (partially) paid | void)
-- ---------------------------------------------------------------------------
CREATE TABLE invoice (
    id                      UUID         NOT NULL PRIMARY KEY,
    customer_id             UUID         NOT NULL REFERENCES customer (id),

    -- Human-facing sequential number, assigned on ISSUE (NULL while DRAFT). Postgres treats NULLs as
    -- distinct under the UNIQUE below, so multiple drafts (all NULL) coexist without conflict.
    invoice_number          VARCHAR(64),

    status                  VARCHAR(16)  NOT NULL DEFAULT 'DRAFT',

    issue_date              DATE,        -- set on issue
    due_date                DATE,        -- set on issue (issue_date + terms)

    -- Money (minor units + ISO-4217). subtotal = Σ line totals; tax = illustrative output VAT;
    -- total = subtotal + tax; paid = Σ payments. NEVER a float (rule 8).
    currency                CHAR(3)      NOT NULL,
    subtotal_minor          BIGINT       NOT NULL DEFAULT 0,
    tax_minor               BIGINT       NOT NULL DEFAULT 0,
    total_minor             BIGINT       NOT NULL DEFAULT 0,
    paid_minor              BIGINT       NOT NULL DEFAULT 0,

    -- TRUE when tax was computed from the ILLUSTRATIVE placeholder rate (SME-gated).
    uses_illustrative_rules BOOLEAN      NOT NULL DEFAULT FALSE,

    -- The GL journal entry raised on issue (Dr AR / Cr revenue (+ VAT)); NULL while DRAFT.
    journal_entry_id        UUID,

    -- Auditable.
    created_at  TIMESTAMPTZ  NOT NULL,
    created_by  VARCHAR(255) NOT NULL,
    updated_at  TIMESTAMPTZ  NOT NULL,
    updated_by  VARCHAR(255) NOT NULL,
    version     BIGINT       NOT NULL,
    company_id  VARCHAR(64)  NOT NULL,

    CONSTRAINT ck_invoice_status
        CHECK (status IN ('DRAFT', 'ISSUED', 'PARTIALLY_PAID', 'PAID', 'VOID')),
    CONSTRAINT uq_invoice_company_number UNIQUE (company_id, invoice_number)
);

ALTER TABLE invoice ENABLE ROW LEVEL SECURITY;
ALTER TABLE invoice FORCE ROW LEVEL SECURITY;

CREATE POLICY invoice_tenant_isolation ON invoice
    USING      (company_id = current_setting('app.current_tenant', true))
    WITH CHECK (company_id = current_setting('app.current_tenant', true));

CREATE INDEX idx_invoice_company_status   ON invoice (company_id, status);
CREATE INDEX idx_invoice_company_customer ON invoice (company_id, customer_id);
-- Aging scans outstanding invoices by due_date within the bound tenant.
CREATE INDEX idx_invoice_company_due      ON invoice (company_id, due_date);

-- ---------------------------------------------------------------------------
-- invoice_line (one billed item; line_total = unit_price × quantity, computed server-side)
-- ---------------------------------------------------------------------------
CREATE TABLE invoice_line (
    id               UUID         NOT NULL PRIMARY KEY,
    invoice_id       UUID         NOT NULL REFERENCES invoice (id),
    line_no          INTEGER      NOT NULL,
    description      VARCHAR(500) NOT NULL,
    -- Whole-unit quantity in Phase 1 (fractional quantities are a later enhancement). Money is never
    -- a float; line_total_minor = unit_price_minor × quantity is exact (Money.multiply).
    quantity         INTEGER      NOT NULL,
    unit_price_minor BIGINT       NOT NULL,
    line_total_minor BIGINT       NOT NULL,
    currency         CHAR(3)      NOT NULL,

    -- Auditable.
    created_at  TIMESTAMPTZ  NOT NULL,
    created_by  VARCHAR(255) NOT NULL,
    updated_at  TIMESTAMPTZ  NOT NULL,
    updated_by  VARCHAR(255) NOT NULL,
    version     BIGINT       NOT NULL,
    company_id  VARCHAR(64)  NOT NULL,

    CONSTRAINT uq_invoice_line_no  UNIQUE (invoice_id, line_no),
    CONSTRAINT ck_invoice_line_qty CHECK (quantity > 0)
);

ALTER TABLE invoice_line ENABLE ROW LEVEL SECURITY;
ALTER TABLE invoice_line FORCE ROW LEVEL SECURITY;

CREATE POLICY invoice_line_tenant_isolation ON invoice_line
    USING      (company_id = current_setting('app.current_tenant', true))
    WITH CHECK (company_id = current_setting('app.current_tenant', true));

CREATE INDEX idx_invoice_line_invoice ON invoice_line (invoice_id);

-- ---------------------------------------------------------------------------
-- invoice_payment (a receipt against an invoice; drives paid_minor + status)
-- ---------------------------------------------------------------------------
CREATE TABLE invoice_payment (
    id               UUID         NOT NULL PRIMARY KEY,
    invoice_id       UUID         NOT NULL REFERENCES invoice (id),
    amount_minor     BIGINT       NOT NULL,
    currency         CHAR(3)      NOT NULL,
    paid_at          TIMESTAMPTZ  NOT NULL,
    method           VARCHAR(32)  NOT NULL DEFAULT 'CASH',

    -- The GL journal entry raised for this payment (Dr cash-clearing / Cr AR).
    journal_entry_id UUID,

    -- Auditable.
    created_at  TIMESTAMPTZ  NOT NULL,
    created_by  VARCHAR(255) NOT NULL,
    updated_at  TIMESTAMPTZ  NOT NULL,
    updated_by  VARCHAR(255) NOT NULL,
    version     BIGINT       NOT NULL,
    company_id  VARCHAR(64)  NOT NULL,

    CONSTRAINT ck_invoice_payment_amount CHECK (amount_minor > 0)
);

ALTER TABLE invoice_payment ENABLE ROW LEVEL SECURITY;
ALTER TABLE invoice_payment FORCE ROW LEVEL SECURITY;

CREATE POLICY invoice_payment_tenant_isolation ON invoice_payment
    USING      (company_id = current_setting('app.current_tenant', true))
    WITH CHECK (company_id = current_setting('app.current_tenant', true));

CREATE INDEX idx_invoice_payment_invoice ON invoice_payment (invoice_id);
