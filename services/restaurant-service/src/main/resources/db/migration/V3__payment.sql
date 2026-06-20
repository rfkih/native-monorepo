-- restaurant-service POS payments (ADR 0006).
--
-- One `payment` row per tender against an order. Mirrors V1/V2's Auditable + ENABLE/FORCE RLS
-- pattern exactly. Cash captures synchronously (status CAPTURED, with tendered + change); QRIS/card
-- start PENDING (provider_pending = TRUE — the flagged-data marker) and become CAPTURED only when
-- the sale is recorded at capture, so an abandoned digital tender yields no revenue.
--
-- Shaped for split/multi-tender later via an ADDITIVE `payment_seq` column — no column here is
-- removed or retyped by that change.

CREATE TABLE payment (
    id               UUID         NOT NULL PRIMARY KEY,

    -- The order being paid (a child of restaurant_order, but a first-class aggregate of its own).
    order_id         UUID         NOT NULL REFERENCES restaurant_order (id),
    business_id      UUID         NOT NULL,

    -- CASH | QRIS | CARD (TenderType, EnumType.STRING).
    tender_type      VARCHAR(16)  NOT NULL,

    -- PENDING | CAPTURED | VOIDED | REFUNDED | PARTIALLY_REFUNDED | ABANDONED | FAILED
    -- (Payment.Status, EnumType.STRING).
    status           VARCHAR(24)  NOT NULL,

    -- The tender amount as Money (libs/money) = integer minor units + ISO-4217 code. NEVER a float.
    amount_minor     BIGINT       NOT NULL,
    currency         CHAR(3)      NOT NULL,

    -- Cash only: the physical cash handed over, and the change returned (whole rupiah for IDR).
    -- NULL for digital tenders (no cash is tendered). change_minor is never negative (CHECK below).
    tendered_minor   BIGINT       NULL,
    change_minor     BIGINT       NULL,

    -- Cumulative amount refunded so far (minor units), for partial-refund bookkeeping. A refund may
    -- never exceed the captured amount (CHECK below); a full refund leaves refunded_minor = amount.
    refunded_minor   BIGINT       NOT NULL DEFAULT 0,

    -- Provider transaction reference. For the flagged-pending DigitalProvider this is a placeholder
    -- ("PENDING-PLACEHOLDER-<uuid>"); for cash it is NULL.
    provider_ref     VARCHAR(128) NULL,

    -- The flagged-data marker: TRUE for any digital tender until a real PSP adapter lands (ADR 0007);
    -- cash is always FALSE. Never present a provider_pending payment to a user as settled.
    provider_pending BOOLEAN      NOT NULL DEFAULT FALSE,

    -- The recorded sale this tender produced (set at capture; NULL while PENDING).
    sale_id          UUID         NULL,

    captured_at      TIMESTAMPTZ  NULL,
    occurred_at      TIMESTAMPTZ  NOT NULL,

    -- Producer idempotency: exactly one payment per (company, client request id).
    idempotency_key  TEXT         NOT NULL,

    -- Auditable (libs/tenant) — present on every Native table.
    created_at       TIMESTAMPTZ  NOT NULL,
    created_by       VARCHAR(255) NOT NULL,
    updated_at       TIMESTAMPTZ  NOT NULL,
    updated_by       VARCHAR(255) NOT NULL,
    version          BIGINT       NOT NULL,
    company_id       VARCHAR(64)  NOT NULL,

    CONSTRAINT uq_payment_company_idempotency UNIQUE (company_id, idempotency_key),

    CONSTRAINT ck_payment_tender_type CHECK (tender_type IN ('CASH', 'QRIS', 'CARD')),

    CONSTRAINT ck_payment_status CHECK (status IN (
        'PENDING', 'CAPTURED', 'VOIDED', 'REFUNDED', 'PARTIALLY_REFUNDED', 'ABANDONED', 'FAILED')),

    -- A cash tender MUST carry tendered + a non-negative change; a digital tender carries neither.
    CONSTRAINT ck_payment_cash_change CHECK (
        (tender_type <> 'CASH')
        OR (tendered_minor IS NOT NULL AND change_minor IS NOT NULL AND change_minor >= 0)),

    -- A refund can never exceed the captured amount.
    CONSTRAINT ck_payment_refunded CHECK (refunded_minor >= 0 AND refunded_minor <= amount_minor)
);

ALTER TABLE payment ENABLE ROW LEVEL SECURITY;
ALTER TABLE payment FORCE ROW LEVEL SECURITY;

CREATE POLICY payment_tenant_isolation ON payment
    USING (company_id = current_setting('app.current_tenant', true))
    WITH CHECK (company_id = current_setting('app.current_tenant', true));

-- Access paths: payments for an order (receipt), and the pending-tender sweep (company + status).
CREATE INDEX idx_payment_order_id ON payment (order_id);
CREATE INDEX idx_payment_company_status ON payment (company_id, status);
