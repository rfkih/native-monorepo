-- payment-service V3 — payment_charge (ADR 0045): the dynamic-QRIS gateway charge lifecycle.
--
-- One row per PSP charge against a vertical's PENDING payment (GATEWAY mode, merchant's own
-- Midtrans account). Lifecycle: INITIATED (row committed BEFORE the PSP call, so a webhook can
-- never race an uncommitted charge) -> QR_ISSUED (Midtrans returned the qr_string) ->
-- SUCCEEDED | EXPIRED | CANCELED | FAILED. The SUCCEEDED transition writes the
-- PaymentChargeSucceeded outbox row in the SAME transaction (rule 3); the optimistic version
-- column makes a webhook/sync race produce exactly ONE transition and ONE event.
--
--   * provider_order_id — 'n-' || id, the Midtrans order_id (<= 50 chars); the webhook's lookup
--                         key, unique per tenant.
--   * amount_minor      — the tender RESIDUAL the QR charges (rule 8: BIGINT minor units +
--                         CHAR(3) currency; IDR-only enforced in the writer, 422).
--   * qr_string/qr_url  — what the till renders (the customer-facing QRIS payload).
--   * idempotency_key   — the console's replay key ('charge:<paymentId>:<attempt>'); same key +
--                         different payload -> 409 (the PlatformSettlementWriter discipline).
--   * one LIVE charge per payment — the partial unique below; a retry is legal only after the
--                         previous charge went terminal.
--
-- RLS: FORCE tenant policy (rules 4-5); the webhook path binds a PROVISIONAL tenant from the
-- callback URL's {companyId} and relies on this policy to make forged tenants see nothing.

CREATE TABLE payment_charge (
    id                 UUID          NOT NULL PRIMARY KEY,

    vertical           VARCHAR(16)   NOT NULL,
    payment_id         UUID          NOT NULL,
    -- The vertical's capture key when it differs from payment_id (carwash/barbershop TICKET id).
    reference_id       UUID          NULL,
    business_id        UUID          NOT NULL,

    amount_minor       BIGINT        NOT NULL,
    currency           CHAR(3)       NOT NULL,

    status             VARCHAR(16)   NOT NULL,

    provider           VARCHAR(16)   NOT NULL,
    provider_order_id  VARCHAR(50)   NOT NULL,
    provider_txn_id    VARCHAR(64)   NULL,

    qr_string          TEXT          NULL,
    qr_url             TEXT          NULL,

    expires_at         TIMESTAMPTZ   NULL,
    succeeded_at       TIMESTAMPTZ   NULL,

    idempotency_key    VARCHAR(64)   NOT NULL,

    -- Auditable (libs/tenant) — present on every Native table (rule 4).
    created_at         TIMESTAMPTZ   NOT NULL,
    created_by         VARCHAR(255)  NOT NULL,
    updated_at         TIMESTAMPTZ   NOT NULL,
    updated_by         VARCHAR(255)  NOT NULL,
    version            BIGINT        NOT NULL,
    company_id         VARCHAR(64)   NOT NULL,

    CONSTRAINT ck_payment_charge_vertical
        CHECK (vertical IN ('restaurant', 'carwash', 'barbershop')),
    CONSTRAINT ck_payment_charge_status
        CHECK (status IN ('INITIATED', 'QR_ISSUED', 'SUCCEEDED', 'EXPIRED', 'CANCELED', 'FAILED')),
    CONSTRAINT ck_payment_charge_provider
        CHECK (provider IN ('MIDTRANS')),
    CONSTRAINT ck_payment_charge_amount_positive
        CHECK (amount_minor > 0)
);

CREATE UNIQUE INDEX uq_payment_charge_idem
    ON payment_charge (company_id, idempotency_key);
CREATE UNIQUE INDEX uq_payment_charge_provider_order
    ON payment_charge (company_id, provider_order_id);
-- At most ONE live charge per vertical payment (retry only after the previous one is terminal).
CREATE UNIQUE INDEX uq_payment_charge_active
    ON payment_charge (company_id, payment_id)
    WHERE status IN ('INITIATED', 'QR_ISSUED');
-- The webhook's lookup path (provider_order_id) is covered by its unique index above.

ALTER TABLE payment_charge ENABLE ROW LEVEL SECURITY;
ALTER TABLE payment_charge FORCE ROW LEVEL SECURITY;

CREATE POLICY payment_charge_tenant_isolation ON payment_charge
    USING      (company_id = current_setting('app.current_tenant', true))
    WITH CHECK (company_id = current_setting('app.current_tenant', true));
