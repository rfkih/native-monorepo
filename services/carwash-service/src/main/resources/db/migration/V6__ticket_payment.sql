-- carwash-service V6 — POS-parity checkout: the ticket aggregate, its priced lines, and its
-- payment(s). Mirrors restaurant-service's order/order_line/payment shape (V1-V3) column-for-column
-- where the concepts overlap; "ticket" replaces "order" as the carwash checkout's aggregate name.
--
--   1. carwash_ticket      — the checkout aggregate: pricing totals (Money = BIGINT minor units +
--                             CHAR(3) currency, never a float — rule 8), the resolved tax rule
--                             version + illustrative-rules flag (V5), and the idempotency key that
--                             makes a retried checkout resolve to the same row (exactly one
--                             SaleRecorded).
--   2. carwash_ticket_line — one row per priced item (package or addon) on a ticket, a snapshot of
--                             its name + price at checkout time (receipt-reproducible even if the
--                             catalog row changes later).
--   3. carwash_payment     — one row per tender against a ticket, mirroring restaurant V3's payment
--                             column types where they overlap (cash captures synchronously with
--                             tendered/change; digital tenders are provider_pending until captured).
--
-- Every table: 6 Auditable columns + ENABLE/FORCE ROW LEVEL SECURITY + a <table>_tenant_isolation
-- policy keyed to app.current_tenant, exactly as V1/V4/V5.

-- ---------------------------------------------------------------------------
-- 1. carwash_ticket
-- ---------------------------------------------------------------------------
CREATE TABLE carwash_ticket (
    id                      UUID         NOT NULL PRIMARY KEY,

    -- The carwash outlet (org_unit) this ticket was opened at; the SaleRecorded business_id.
    business_id             UUID         NOT NULL,

    -- The wash bay it ran on (free text, e.g. "bay-1").
    bay                     VARCHAR(64)  NOT NULL,

    -- Optional vehicle plate captured at check-in; NULL when not recorded.
    vehicle_plate           VARCHAR(32)  NULL,

    -- Optional washer attribution: the staff_profile (V4) selected at checkout, and a snapshot of
    -- that profile's linked employee_id at the moment of checkout (the profile's link may change
    -- later; the ticket keeps the value that was true when commission attribution happened).
    staff_profile_id        UUID         NULL,
    washer_employee_id      UUID         NULL,

    -- Money (libs/money) = integer minor units + ISO-4217 code. NEVER a float (rule 8). Pricing
    -- breakdown: subtotal (sum of line prices) less discount, plus service charge, plus tax, equals
    -- total — enforced by the CHECK constraint below.
    subtotal_minor          BIGINT       NOT NULL,
    discount_minor          BIGINT       NOT NULL DEFAULT 0,
    service_charge_minor    BIGINT       NOT NULL,
    tax_minor               BIGINT       NOT NULL,
    total_minor             BIGINT       NOT NULL,
    currency                CHAR(3)      NOT NULL,

    -- The tax_charge_rule (V5) rule_version resolved at checkout, NULL when no rule resolved (zero
    -- tax via fall-through). uses_illustrative_rules mirrors restaurant: TRUE when the resolved rule
    -- (if any) carries provenance='ILLUSTRATIVE_PLACEHOLDER', propagated onto SaleRecorded.
    tax_rule_version        VARCHAR(64)  NULL,
    uses_illustrative_rules BOOLEAN      NOT NULL,

    -- The recorded sale this ticket produced. Revenue is recognised AT CAPTURE (ADR 0006): a cash
    -- checkout captures synchronously and stamps this in the same transaction; a digital tender
    -- (QRIS/CARD) leaves it NULL at checkout and stamps it when the pending payment is captured.
    -- An abandoned digital ticket therefore never has a sale.
    sale_id                 UUID         NULL,

    occurred_at             TIMESTAMPTZ  NOT NULL,

    -- Producer idempotency: exactly one ticket per (company, client request id). A retried checkout
    -- resolves to the same row -> exactly one SaleRecorded + one MetricPublished.
    idempotency_key         TEXT         NOT NULL,

    -- Auditable (libs/tenant) — present on every Native table (rule 4).
    created_at              TIMESTAMPTZ  NOT NULL,
    created_by              VARCHAR(255) NOT NULL,
    updated_at              TIMESTAMPTZ  NOT NULL,
    updated_by              VARCHAR(255) NOT NULL,
    version                 BIGINT       NOT NULL,
    company_id              VARCHAR(64)  NOT NULL,

    CONSTRAINT uq_carwash_ticket_company_idempotency UNIQUE (company_id, idempotency_key),

    -- Pricing must reconcile: subtotal - discount + service charge + tax = total.
    CONSTRAINT ck_carwash_ticket_total_balances
        CHECK (subtotal_minor - discount_minor + service_charge_minor + tax_minor = total_minor)
);

ALTER TABLE carwash_ticket ENABLE ROW LEVEL SECURITY;
ALTER TABLE carwash_ticket FORCE ROW LEVEL SECURITY;

CREATE POLICY carwash_ticket_tenant_isolation ON carwash_ticket
    USING      (company_id = current_setting('app.current_tenant', true))
    WITH CHECK (company_id = current_setting('app.current_tenant', true));

-- ---------------------------------------------------------------------------
-- 2. carwash_ticket_line — one row per priced item (package or addon), snapshot at checkout time.
-- ---------------------------------------------------------------------------
CREATE TABLE carwash_ticket_line (
    id             UUID         NOT NULL PRIMARY KEY,
    ticket_id      UUID         NOT NULL REFERENCES carwash_ticket (id),
    business_id    UUID         NOT NULL,

    -- PACKAGE (wash_package) or ADDON (wash_addon).
    item_type      VARCHAR(16)  NOT NULL,

    -- The catalog row this line was priced from (wash_package.id or wash_addon.id, per item_type).
    -- Not a FK: the two catalog tables are disjoint, and the line is a checkout-time snapshot that
    -- must remain even if the catalog row is later retired.
    item_id        UUID         NOT NULL,

    -- Snapshot of the catalog item's name at checkout time (receipt-reproducible).
    name           VARCHAR(255) NOT NULL,

    -- Money (libs/money) = integer minor units + ISO-4217 code. NEVER a float. Snapshot of the
    -- catalog item's price_minor at checkout time (the catalog price may change later).
    price_minor    BIGINT       NOT NULL,
    currency       CHAR(3)      NOT NULL,

    qty            INT          NOT NULL DEFAULT 1,

    -- Auditable (libs/tenant) — present on every Native table (rule 4).
    created_at     TIMESTAMPTZ  NOT NULL,
    created_by     VARCHAR(255) NOT NULL,
    updated_at     TIMESTAMPTZ  NOT NULL,
    updated_by     VARCHAR(255) NOT NULL,
    version        BIGINT       NOT NULL,
    company_id     VARCHAR(64)  NOT NULL,

    CONSTRAINT ck_carwash_ticket_line_item_type CHECK (item_type IN ('PACKAGE', 'ADDON')),
    CONSTRAINT ck_carwash_ticket_line_qty_positive CHECK (qty > 0)
);

ALTER TABLE carwash_ticket_line ENABLE ROW LEVEL SECURITY;
ALTER TABLE carwash_ticket_line FORCE ROW LEVEL SECURITY;

CREATE POLICY carwash_ticket_line_tenant_isolation ON carwash_ticket_line
    USING      (company_id = current_setting('app.current_tenant', true))
    WITH CHECK (company_id = current_setting('app.current_tenant', true));

-- Hot access path: the lines for a ticket (receipt rendering, checkout recompute).
CREATE INDEX idx_carwash_ticket_line_ticket
    ON carwash_ticket_line (company_id, ticket_id);

-- ---------------------------------------------------------------------------
-- 3. carwash_payment — one row per tender against a ticket.
-- ---------------------------------------------------------------------------
-- Mirrors restaurant-service V3 payment column types where they overlap: cash captures
-- synchronously (status CAPTURED, with tendered + change); a digital tender starts PENDING
-- (provider_pending = TRUE — the flagged-data marker) until captured, so an abandoned digital
-- tender yields no revenue.
CREATE TABLE carwash_payment (
    id                UUID         NOT NULL PRIMARY KEY,
    ticket_id         UUID         NOT NULL REFERENCES carwash_ticket (id),
    business_id       UUID         NOT NULL,

    -- CASH | QRIS | CARD, mirroring restaurant's TenderType.
    tender_type       VARCHAR(16)  NOT NULL,

    -- PENDING | CAPTURED | VOIDED | REFUNDED | PARTIALLY_REFUNDED | ABANDONED | FAILED,
    -- mirroring restaurant's Payment.Status.
    status            VARCHAR(24)  NOT NULL,

    -- Money (libs/money) = integer minor units + ISO-4217 code. NEVER a float.
    amount_minor      BIGINT       NOT NULL,
    currency          CHAR(3)      NOT NULL,

    -- Cash only: the physical cash handed over, and the change returned. NULL for digital tenders.
    -- change_minor is never negative (CHECK below).
    tendered_minor    BIGINT       NULL,
    change_minor      BIGINT       NULL,

    -- Provider transaction reference; NULL for cash.
    provider_ref      VARCHAR(255) NULL,

    -- The flagged-data marker: TRUE for any digital tender until a real PSP adapter lands; cash is
    -- always FALSE. Never present a provider_pending payment to a user as settled.
    provider_pending  BOOLEAN      NOT NULL DEFAULT FALSE,

    -- Auditable (libs/tenant) — present on every Native table (rule 4).
    created_at        TIMESTAMPTZ  NOT NULL,
    created_by        VARCHAR(255) NOT NULL,
    updated_at        TIMESTAMPTZ  NOT NULL,
    updated_by        VARCHAR(255) NOT NULL,
    version           BIGINT       NOT NULL,
    company_id        VARCHAR(64)  NOT NULL,

    CONSTRAINT ck_carwash_payment_tender_type CHECK (tender_type IN ('CASH', 'QRIS', 'CARD')),

    CONSTRAINT ck_carwash_payment_status CHECK (status IN (
        'PENDING', 'CAPTURED', 'VOIDED', 'REFUNDED', 'PARTIALLY_REFUNDED', 'ABANDONED', 'FAILED')),

    CONSTRAINT ck_carwash_payment_change_nonneg
        CHECK (change_minor IS NULL OR change_minor >= 0)
);

ALTER TABLE carwash_payment ENABLE ROW LEVEL SECURITY;
ALTER TABLE carwash_payment FORCE ROW LEVEL SECURITY;

CREATE POLICY carwash_payment_tenant_isolation ON carwash_payment
    USING      (company_id = current_setting('app.current_tenant', true))
    WITH CHECK (company_id = current_setting('app.current_tenant', true));

-- Access paths: payments for a ticket (receipt), and the pending-tender sweep (company + status).
CREATE INDEX idx_carwash_payment_ticket_id ON carwash_payment (ticket_id);
CREATE INDEX idx_carwash_payment_company_status ON carwash_payment (company_id, status);
