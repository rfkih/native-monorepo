-- payment-service V2 — payment_settings (ADR 0045): how this company takes QRIS at the till.
--
-- One row per scope: the company DEFAULT row (outlet_id IS NULL) plus at most one OVERRIDE row per
-- outlet. Effective resolution (SettingsReader): outlet row -> company row -> implicit MANUAL.
--
--   * mode        — MANUAL (cashier confirms out-of-band, today's behavior, the implicit default),
--                   STATIC (the merchant's uploaded QRIS image is shown at the till), or
--                   GATEWAY (dynamic QRIS per transaction via the merchant's OWN Midtrans account).
--                   A mode may be set BEFORE its prerequisites (image / credentials) exist — the
--                   effective read exposes availability and the console fails open to MANUAL, so a
--                   half-configured mode can never block a sale (ADR 0045).
--   * static_qr_* — the uploaded image (expense_receipt idiom: bytea + declared-and-verified
--                   content type + sha256 + a byte-size CHECK mirroring the app cap). The image is
--                   a merchant business document (a QR anyone at the till can see), NOT rule-6 PII
--                   — stored unencrypted, served only through the authenticated blob endpoint.
--   * provider/server_key/client_key — GATEWAY credentials, COMPANY-DEFAULT ROW ONLY (an outlet
--                   override may override mode + image, never credentials — SettingsWriter rejects
--                   them, keeping one Midtrans account per company). server_key_encrypted /
--                   client_key_encrypted are AES-256-GCM ciphertext (rule 6 — merchant secrets;
--                   PiiBytesAttributeConverter); server_key_last4 is the ONLY readable trace and
--                   the only thing any read endpoint ever returns.
--
-- RLS: FORCE tenant policy on app.current_tenant (rules 4-5); the app connects as a non-superuser
-- role so the policy genuinely binds. current_setting(..., true) is NULL when unset -> fail closed.

CREATE TABLE payment_settings (
    id                      UUID          NOT NULL PRIMARY KEY,

    -- NULL = the company default row; non-null = a per-outlet override (org business_id).
    outlet_id               UUID          NULL,

    mode                    VARCHAR(16)   NOT NULL,

    -- STATIC: the uploaded QRIS image (all four set together, or all NULL).
    static_qr_content_type  VARCHAR(64)   NULL,
    static_qr_byte_size     INT           NULL,
    static_qr_sha256        CHAR(64)      NULL,
    static_qr_data          BYTEA         NULL,

    -- GATEWAY: the merchant's own PSP account (company default row only).
    provider                VARCHAR(16)   NULL,
    provider_environment    VARCHAR(16)   NULL,
    server_key_encrypted    BYTEA         NULL,
    client_key_encrypted    BYTEA        NULL,
    server_key_last4        VARCHAR(4)    NULL,

    -- Auditable (libs/tenant) — present on every Native table (rule 4).
    created_at              TIMESTAMPTZ   NOT NULL,
    created_by              VARCHAR(255)  NOT NULL,
    updated_at              TIMESTAMPTZ   NOT NULL,
    updated_by              VARCHAR(255)  NOT NULL,
    version                 BIGINT        NOT NULL,
    company_id              VARCHAR(64)   NOT NULL,

    CONSTRAINT ck_payment_settings_mode
        CHECK (mode IN ('MANUAL', 'STATIC', 'GATEWAY')),
    CONSTRAINT ck_payment_settings_provider
        CHECK (provider IS NULL OR provider IN ('MIDTRANS')),
    CONSTRAINT ck_payment_settings_environment
        CHECK (provider_environment IS NULL OR provider_environment IN ('SANDBOX', 'PRODUCTION')),
    -- 2 MiB cap, mirroring PaymentSettings.MAX_QR_IMAGE_BYTES + the multipart max-file-size.
    CONSTRAINT ck_payment_settings_qr_size
        CHECK (static_qr_byte_size IS NULL
               OR (static_qr_byte_size > 0 AND static_qr_byte_size <= 2097152)),
    -- The image columns travel together: either a complete image or none at all.
    CONSTRAINT ck_payment_settings_qr_complete
        CHECK ((static_qr_data IS NULL) = (static_qr_content_type IS NULL)
               AND (static_qr_data IS NULL) = (static_qr_byte_size IS NULL)
               AND (static_qr_data IS NULL) = (static_qr_sha256 IS NULL))
);

-- One company-default row + at most one override per outlet, per tenant.
CREATE UNIQUE INDEX uq_payment_settings_company_default
    ON payment_settings (company_id) WHERE outlet_id IS NULL;
CREATE UNIQUE INDEX uq_payment_settings_outlet
    ON payment_settings (company_id, outlet_id) WHERE outlet_id IS NOT NULL;

ALTER TABLE payment_settings ENABLE ROW LEVEL SECURITY;
ALTER TABLE payment_settings FORCE ROW LEVEL SECURITY;

CREATE POLICY payment_settings_tenant_isolation ON payment_settings
    USING      (company_id = current_setting('app.current_tenant', true))
    WITH CHECK (company_id = current_setting('app.current_tenant', true));
