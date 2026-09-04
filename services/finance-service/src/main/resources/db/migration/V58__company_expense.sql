-- ADR 0072 — the company-expense input: one submit records the money in the GL AND (for an
-- INVENTORY-kind expense) instructs restaurant-service to receive the stock via the
-- InventoryPurchaseRecorded outbox event. This is the first first-party company-expense surface:
-- until now "Pengeluaran" was employee claims only and the sole purchase-money input was an AP
-- bill. Money legs (periodic default): GENERAL -> Dr resolveExpense(gl_hint) / Cr CASH_CLEARING;
-- INVENTORY -> Dr COGS(5100) / Cr CASH_CLEARING. Perpetual-active swaps the INVENTORY debit to
-- GRNI_CLEARING(2050), cleared later by StockReceived's Dr 1100 / Cr 2050.
--
-- Rule 4: Auditable columns on every table. Rule 5: ENABLE + FORCE RLS, tenant-scoped policy.
-- Money: integer minor units + ISO-4217 CHAR(3) (rule 8).

CREATE TABLE company_expense (
    id                    UUID PRIMARY KEY,
    -- Assigned at record time under a per-tenant advisory lock ("EXP-00001", the bill-number
    -- idiom); UNIQUE per tenant as the database backstop.
    expense_no            VARCHAR(16)  NOT NULL,
    kind                  VARCHAR(16)  NOT NULL CHECK (kind IN ('GENERAL', 'INVENTORY')),
    -- The outlet dimension for the P&L ledger posting (validated against the org_unit_ref read
    -- model at input). NOT the stock-side outlet: that truth is ingredient.business_id.
    business_id           UUID         NOT NULL,
    -- GENERAL only; '' = the catch-all expense account. INVENTORY rows store '' (the kind, not a
    -- hint, drives the account there).
    gl_hint               VARCHAR(64)  NOT NULL DEFAULT '',
    description           VARCHAR(500) NOT NULL,
    -- The money total in minor units. For INVENTORY this equals the sum of the lines' value_minor
    -- (enforced by the writer; both sides are integers so the equality is exact).
    amount_minor          BIGINT       NOT NULL CHECK (amount_minor > 0),
    currency              CHAR(3)      NOT NULL,
    occurred_at           TIMESTAMPTZ  NOT NULL,
    status                VARCHAR(16)  NOT NULL CHECK (status IN ('POSTED', 'VOID')),
    -- The posted journal entry (Dr expense-or-GRNI / Cr CASH_CLEARING) and, once voided, its
    -- contra. Money-side only: a void never auto-reverses stock (ADR 0072 §4, fix-forward).
    journal_entry_id      UUID         NOT NULL,
    void_journal_entry_id UUID,
    -- Client-supplied replay key (nullable): a retry of the SAME submit returns the existing row;
    -- the partial-unique index is the race backstop. Same-key-different-payload is a 409.
    idempotency_key       VARCHAR(64),

    company_id            VARCHAR(64)  NOT NULL,
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by            VARCHAR(128) NOT NULL,
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_by            VARCHAR(128) NOT NULL,
    version               BIGINT       NOT NULL DEFAULT 0,

    CONSTRAINT uq_company_expense_no UNIQUE (company_id, expense_no)
);

CREATE UNIQUE INDEX uq_company_expense_idempotency
    ON company_expense (company_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;

-- The list read path: newest first within the tenant.
CREATE INDEX idx_company_expense_occurred ON company_expense (company_id, occurred_at DESC);

ALTER TABLE company_expense ENABLE ROW LEVEL SECURITY;
ALTER TABLE company_expense FORCE ROW LEVEL SECURITY;
CREATE POLICY company_expense_tenant_isolation ON company_expense
    USING      (company_id = current_setting('app.current_tenant', true))
    WITH CHECK (company_id = current_setting('app.current_tenant', true));

-- One row per ingredient line of an INVENTORY-kind expense. line id doubles as the
-- goods_receipt.idempotency_key on the restaurant side (the per-line replay anchor), and as the
-- line_id on the InventoryPurchaseRecorded wire.
CREATE TABLE company_expense_line (
    id              UUID PRIMARY KEY,
    expense_id      UUID         NOT NULL REFERENCES company_expense (id),
    line_no         INT          NOT NULL CHECK (line_no > 0),
    -- Opaque restaurant-service ingredient reference + a display-name snapshot (finance may not
    -- join another service's DB — rule 1; the name is for finance-side lists/receipts only).
    ingredient_id   UUID         NOT NULL,
    ingredient_name VARCHAR(255) NOT NULL,
    -- Quantity in the ingredient's BASE unit (integer — the ADR 0046 no-decimals rule; display
    -- conversion happens in the console).
    qty_base        BIGINT       NOT NULL CHECK (qty_base > 0),
    -- The exact amount paid for THIS line, minor units. Zero is legal (a bundled/bonus item)
    -- as long as the expense total stays > 0.
    value_minor     BIGINT       NOT NULL CHECK (value_minor >= 0),
    currency        CHAR(3)      NOT NULL,

    company_id      VARCHAR(64)  NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by      VARCHAR(128) NOT NULL,
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_by      VARCHAR(128) NOT NULL,
    version         BIGINT       NOT NULL DEFAULT 0,

    CONSTRAINT uq_company_expense_line_no UNIQUE (expense_id, line_no)
);

CREATE INDEX idx_company_expense_line_expense ON company_expense_line (expense_id);

ALTER TABLE company_expense_line ENABLE ROW LEVEL SECURITY;
ALTER TABLE company_expense_line FORCE ROW LEVEL SECURITY;
CREATE POLICY company_expense_line_tenant_isolation ON company_expense_line
    USING      (company_id = current_setting('app.current_tenant', true))
    WITH CHECK (company_id = current_setting('app.current_tenant', true));
