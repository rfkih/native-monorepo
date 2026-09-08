-- ============================================================================================================================
-- V62 — who pays out the non-marketplace tenders (ADR 0076 phase 1)
-- ============================================================================================================================
-- V61 gave the receivable sub-ledger a `source_code` — WHO pays a balance out. For a marketplace row
-- that fact needs no configuration: the payer IS the channel (ShopeeFood is paid by Shopee, GoFood
-- by Gojek), so the writer takes source_code from channel_code.
--
-- QRIS and card have no such channel. A merchant's QR is issued by one acquirer, and WHICH acquirer
-- is a fact only the merchant knows: a Shopee QR settles together with ShopeeFood in a single
-- transfer, a Xendit QR settles on its own. This table is where the merchant states it.
--
-- ---------------------------------------------------------------------------------------------
-- Why this lives in finance and not beside the channel CRUD
-- ---------------------------------------------------------------------------------------------
-- Sales channels are managed in restaurant-service (`sales_channel`, V24). Finance cannot read that
-- table (hard rule 1: database-per-service) and must not call the service to ask (hard rule 2: no
-- synchronous calls between business services). Settlement is finance's own bounded context, so the
-- payer mapping it needs is finance's own row. The console's picker offers the source codes finance
-- can already see in its sub-ledger, plus free text — no cross-service read.
--
-- MARKETPLACE is deliberately NOT a valid kind here: configuring it would let a row's payer drift
-- away from its own channel_code with nothing to reconcile the two.
-- ============================================================================================================================

CREATE TABLE settlement_source_config (
    id           UUID         NOT NULL PRIMARY KEY,

    -- Which tender family this row answers for. MARKETPLACE is excluded on purpose (see above).
    source_kind  VARCHAR(16)  NOT NULL,

    -- The payer. Matches `platform_receivable.source_code`, so a QRIS balance groups into the same
    -- payout as the marketplace channel of the same name.
    source_code  VARCHAR(32)  NOT NULL,

    -- Auditable (libs/tenant) — present on every Native table (rule 4).
    created_at   TIMESTAMPTZ  NOT NULL,
    created_by   VARCHAR(255) NOT NULL,
    updated_at   TIMESTAMPTZ  NOT NULL,
    updated_by   VARCHAR(255) NOT NULL,
    version      BIGINT       NOT NULL,
    company_id   VARCHAR(64)  NOT NULL,

    CONSTRAINT ck_settlement_source_config_kind
        CHECK (source_kind IN ('QRIS', 'CARD')),

    -- One payer per tender family per tenant. v1 assumes a merchant runs ONE QRIS acquirer at a
    -- time (ADR 0076 consequences); running two at once is mis-attributed, detectable as a
    -- sub-ledger balance that never settles, and fixable additively by carrying the acquirer on the
    -- sale itself.
    CONSTRAINT uq_settlement_source_config_kind UNIQUE (company_id, source_kind)
);

ALTER TABLE settlement_source_config ENABLE ROW LEVEL SECURITY;
ALTER TABLE settlement_source_config FORCE ROW LEVEL SECURITY;

CREATE POLICY settlement_source_config_tenant_isolation ON settlement_source_config
    USING      (company_id = current_setting('app.current_tenant', true))
    WITH CHECK (company_id = current_setting('app.current_tenant', true));

-- No extra index: every read is by the (company_id, source_kind) unique key above, and a per-tenant
-- listing is served by that same index's leading company_id column.

-- ============================================================================================================================
-- Ships EMPTY. An unconfigured company keeps every QRIS balance under its own payer (source_code
-- 'QRIS'), which settles on its own exactly as today — configuring a payer is what MERGES it into
-- that payer's payout, never a precondition for money being booked correctly.
-- ============================================================================================================================
