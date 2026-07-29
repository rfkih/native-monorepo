-- finance-service V27 — Accounts Payable sub-ledger (Phase 2 AP). The vendor-facing mirror of AR (V24).
--
-- Introduces the vendor/party dimension for purchases. AP bills post to the existing double-entry GL
-- (Dr expense (net) + Dr input VAT (tax) / Cr AP (total)) in the SAME transaction as the sub-ledger
-- write; these tables exist so aging-by-vendor is possible. See ADR 0015.
--
-- Conventions (mirror V24): every table extends Auditable (6 cols) + ENABLE/FORCE ROW LEVEL SECURITY,
-- tenant policy on app.current_tenant (rules 4 + 5, fail-closed). Money = BIGINT minor + CHAR(3),
-- never a float (rule 8). vendor_id / bill_id are same-service FKs (finance owns AP) — not cross-service.
--
-- ILLUSTRATIVE: input VAT (recoverable) is computed at an ILLUSTRATIVE placeholder rate in BillWriter
-- (uses_illustrative_rules=TRUE); the real rate/regime is SME-gated (same gate as AR output VAT).
--
-- The payment-idempotency key (AR learned this in a follow-up V26) is baked in here from the start:
-- bill_payment.idempotency_key + a partial UNIQUE index scoped to (company_id, bill_id, idempotency_key).

-- ---------------------------------------------------------------------------
-- vendor (the AP party — the bill-from counterparty)
-- ---------------------------------------------------------------------------
CREATE TABLE vendor (
    id          UUID         NOT NULL PRIMARY KEY,
    name        VARCHAR(255) NOT NULL,
    email       VARCHAR(320),          -- business contact; nullable
    tax_id      VARCHAR(64),           -- NPWP / tax registration id; nullable
    active      BOOLEAN      NOT NULL DEFAULT TRUE,

    -- Auditable (libs/tenant).
    created_at  TIMESTAMPTZ  NOT NULL,
    created_by  VARCHAR(255) NOT NULL,
    updated_at  TIMESTAMPTZ  NOT NULL,
    updated_by  VARCHAR(255) NOT NULL,
    version     BIGINT       NOT NULL,
    company_id  VARCHAR(64)  NOT NULL
);

ALTER TABLE vendor ENABLE ROW LEVEL SECURITY;
ALTER TABLE vendor FORCE ROW LEVEL SECURITY;

CREATE POLICY vendor_tenant_isolation ON vendor
    USING      (company_id = current_setting('app.current_tenant', true))
    WITH CHECK (company_id = current_setting('app.current_tenant', true));

CREATE INDEX idx_vendor_company ON vendor (company_id);

-- ---------------------------------------------------------------------------
-- bill (the AP document — draft → posted → (partially) paid | void)
-- ---------------------------------------------------------------------------
CREATE TABLE bill (
    id                      UUID         NOT NULL PRIMARY KEY,
    vendor_id               UUID         NOT NULL REFERENCES vendor (id),

    -- Human-facing internal number, assigned on POST (NULL while DRAFT). Postgres treats NULLs as
    -- distinct under the UNIQUE below, so multiple drafts coexist.
    bill_number             VARCHAR(64),

    status                  VARCHAR(16)  NOT NULL DEFAULT 'DRAFT',

    bill_date               DATE,        -- set on post
    due_date                DATE,        -- set on post (bill_date + terms)

    -- Money (minor units + ISO-4217). subtotal = Σ line totals; tax = illustrative input VAT;
    -- total = subtotal + tax; paid = Σ payments. NEVER a float (rule 8).
    currency                CHAR(3)      NOT NULL,
    subtotal_minor          BIGINT       NOT NULL DEFAULT 0,
    tax_minor               BIGINT       NOT NULL DEFAULT 0,
    total_minor             BIGINT       NOT NULL DEFAULT 0,
    paid_minor              BIGINT       NOT NULL DEFAULT 0,

    uses_illustrative_rules BOOLEAN      NOT NULL DEFAULT FALSE,

    -- The GL journal entry raised on post (Dr expense (+ VAT input) / Cr AP); NULL while DRAFT.
    journal_entry_id        UUID,

    -- Auditable.
    created_at  TIMESTAMPTZ  NOT NULL,
    created_by  VARCHAR(255) NOT NULL,
    updated_at  TIMESTAMPTZ  NOT NULL,
    updated_by  VARCHAR(255) NOT NULL,
    version     BIGINT       NOT NULL,
    company_id  VARCHAR(64)  NOT NULL,

    CONSTRAINT ck_bill_status
        CHECK (status IN ('DRAFT', 'POSTED', 'PARTIALLY_PAID', 'PAID', 'VOID')),
    CONSTRAINT uq_bill_company_number UNIQUE (company_id, bill_number)
);

ALTER TABLE bill ENABLE ROW LEVEL SECURITY;
ALTER TABLE bill FORCE ROW LEVEL SECURITY;

CREATE POLICY bill_tenant_isolation ON bill
    USING      (company_id = current_setting('app.current_tenant', true))
    WITH CHECK (company_id = current_setting('app.current_tenant', true));

CREATE INDEX idx_bill_company_status ON bill (company_id, status);
CREATE INDEX idx_bill_company_vendor ON bill (company_id, vendor_id);
CREATE INDEX idx_bill_company_due    ON bill (company_id, due_date);

-- ---------------------------------------------------------------------------
-- bill_line (one billed item; line_total = unit_price × quantity, computed server-side)
-- ---------------------------------------------------------------------------
CREATE TABLE bill_line (
    id               UUID         NOT NULL PRIMARY KEY,
    bill_id          UUID         NOT NULL REFERENCES bill (id),
    line_no          INTEGER      NOT NULL,
    description      VARCHAR(500) NOT NULL,
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

    CONSTRAINT uq_bill_line_no  UNIQUE (bill_id, line_no),
    CONSTRAINT ck_bill_line_qty CHECK (quantity > 0)
);

ALTER TABLE bill_line ENABLE ROW LEVEL SECURITY;
ALTER TABLE bill_line FORCE ROW LEVEL SECURITY;

CREATE POLICY bill_line_tenant_isolation ON bill_line
    USING      (company_id = current_setting('app.current_tenant', true))
    WITH CHECK (company_id = current_setting('app.current_tenant', true));

CREATE INDEX idx_bill_line_bill ON bill_line (bill_id);

-- ---------------------------------------------------------------------------
-- bill_payment (a payment made against a bill; drives paid_minor + status)
-- ---------------------------------------------------------------------------
-- idempotency_key + the (company_id, bill_id, idempotency_key) UNIQUE index are baked in from the
-- start (AR needed a follow-up V26): a retried payment with the same key on the same bill is replayed,
-- not double-posted; the same key on a DIFFERENT bill is a distinct payment (scoped by bill_id).
CREATE TABLE bill_payment (
    id               UUID         NOT NULL PRIMARY KEY,
    bill_id          UUID         NOT NULL REFERENCES bill (id),
    amount_minor     BIGINT       NOT NULL,
    currency         CHAR(3)      NOT NULL,
    paid_at          TIMESTAMPTZ  NOT NULL,
    method           VARCHAR(32)  NOT NULL DEFAULT 'CASH',
    idempotency_key  VARCHAR(64),

    -- The GL journal entry raised for this payment (Dr AP / Cr cash-clearing).
    journal_entry_id UUID,

    -- Auditable.
    created_at  TIMESTAMPTZ  NOT NULL,
    created_by  VARCHAR(255) NOT NULL,
    updated_at  TIMESTAMPTZ  NOT NULL,
    updated_by  VARCHAR(255) NOT NULL,
    version     BIGINT       NOT NULL,
    company_id  VARCHAR(64)  NOT NULL,

    CONSTRAINT ck_bill_payment_amount CHECK (amount_minor > 0)
);

ALTER TABLE bill_payment ENABLE ROW LEVEL SECURITY;
ALTER TABLE bill_payment FORCE ROW LEVEL SECURITY;

CREATE POLICY bill_payment_tenant_isolation ON bill_payment
    USING      (company_id = current_setting('app.current_tenant', true))
    WITH CHECK (company_id = current_setting('app.current_tenant', true));

CREATE INDEX idx_bill_payment_bill ON bill_payment (bill_id);

CREATE UNIQUE INDEX uq_bill_payment_company_bill_idem
    ON bill_payment (company_id, bill_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;
