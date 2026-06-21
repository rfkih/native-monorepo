-- restaurant-service V5 — per-tenant tax and service-charge configuration (Phase 2 pricing).
--
-- ============================================================================================================================
-- ILLUSTRATIVE PLACEHOLDER — SME-GATED — REPLACE VIA HIGHER-VERSION DATA
-- ============================================================================================================================
-- The rules seeded at the bottom of this migration are INTENTIONAL ILLUSTRATIVE PLACEHOLDERS.
--
-- WHAT IS NOT CONFIRMED:
--   1. REGIME: Whether restaurant sales are subject to PB1 (regional entertainment/restaurant tax,
--      rates vary by municipality), PPN (national VAT, currently 11%), or a combination.
--      The seeded key "PB1_RESTAURANT" and rate 1000 bp (10%) are illustrative only — do NOT
--      assert this as PPN 11%, which is a different national regime.
--   2. SERVICE CHARGE TREATMENT: Whether service charge is revenue (credit REVENUE) or a
--      tip/gratuity liability (credit SERVICE_CHARGE_PAYABLE) is SME- and contract-gated.
--   3. TAX BASE COMPOSITION: Whether service charge is included in the tax base (serviceChargeInTaxBase=true)
--      varies by local regulation — the seeded value of TRUE is illustrative only.
--   4. RATES: 10% (PB1) and 5% (service charge) are illustrative round numbers for development only.
--
-- To replace with real rules (NO code change required):
--   INSERT INTO tax_charge_rule (..., rule_version, rate_bp, provenance, source_note, effective_from)
--       VALUES (..., 'OFFICIAL-2027.1', <real_rate>, 'OFFICIAL', '<SME sign-off note>', <date>);
-- The resolver (ORDER BY rule_version DESC) prefers the higher-version row automatically.
-- The ILLUSTRATIVE rows remain as an audit trail and do NOT need to be deleted.
-- ============================================================================================================================

CREATE TABLE tax_charge_rule (
    id                          UUID         NOT NULL PRIMARY KEY,
    rule_key                    VARCHAR(64)  NOT NULL,
    rule_version                VARCHAR(64)  NOT NULL,

    -- Rate in basis points (1 bp = 0.01%; 1000 bp = 10%). NEVER a float (rule 8).
    rate_bp                     BIGINT       NOT NULL,

    -- Whether service charge is included in the tax base before applying the tax rate.
    -- TRUE  → taxBase = taxableBase + serviceCharge
    -- FALSE → taxBase = taxableBase only
    -- SME-gated: correct treatment depends on jurisdiction and contract.
    service_charge_in_tax_base  BOOLEAN      NOT NULL DEFAULT TRUE,

    -- Provenance: ILLUSTRATIVE_PLACEHOLDER or OFFICIAL (CHECK constraint, no default).
    -- A checkout resolving an ILLUSTRATIVE row sets uses_illustrative_rules=true on SaleRecorded.
    provenance                  VARCHAR(32)  NOT NULL,

    -- Human-readable note: regulation reference, SME sign-off date, etc. Mandatory.
    source_note                 TEXT         NOT NULL,

    -- ISO-4217 currency this rule applies to (e.g. 'IDR').
    currency                    CHAR(3)      NOT NULL,

    effective_from              DATE         NOT NULL,
    effective_to                DATE         NOT NULL DEFAULT DATE '9999-12-31',
    active                      BOOLEAN      NOT NULL DEFAULT TRUE,

    -- Auditable (libs/tenant): 6 cols on every Native table (rule 4).
    created_at                  TIMESTAMPTZ  NOT NULL,
    created_by                  VARCHAR(255) NOT NULL,
    updated_at                  TIMESTAMPTZ  NOT NULL,
    updated_by                  VARCHAR(255) NOT NULL,
    version                     BIGINT       NOT NULL,
    company_id                  VARCHAR(64)  NOT NULL,

    CONSTRAINT ck_tax_charge_rule_provenance
        CHECK (provenance IN ('ILLUSTRATIVE_PLACEHOLDER', 'OFFICIAL')),
    CONSTRAINT ck_tax_charge_rule_rate_bp_nonneg
        CHECK (rate_bp >= 0),
    CONSTRAINT uq_tax_charge_rule_key_version
        UNIQUE (company_id, rule_key, rule_version)
);

ALTER TABLE tax_charge_rule ENABLE ROW LEVEL SECURITY;
ALTER TABLE tax_charge_rule FORCE ROW LEVEL SECURITY;

CREATE POLICY tax_charge_rule_tenant_isolation ON tax_charge_rule
    USING      (company_id = current_setting('app.current_tenant', true))
    WITH CHECK (company_id = current_setting('app.current_tenant', true));

-- Resolution index: find the highest-version active row effective at a given date.
CREATE INDEX idx_tax_charge_rule_resolution
    ON tax_charge_rule (company_id, rule_key, effective_from, effective_to)
    WHERE active = TRUE;

-- ============================================================================================================================
-- ILLUSTRATIVE SEEDS — for the demo tenant (11111111-1111-1111-1111-111111111111) only.
-- ALL rows below are provenance='ILLUSTRATIVE_PLACEHOLDER'. The rates and regime are NOT verified.
-- ============================================================================================================================

-- tax_charge_rule has FORCE ROW LEVEL SECURITY. Migrations run as the DB owner (SUPERUSER or
-- BYPASSRLS-granted role), so we must set the tenant GUC for the INSERT's WITH CHECK to pass.
-- SET LOCAL applies only to this transaction (the Flyway migration's implicit transaction).
SET LOCAL app.current_tenant = '11111111-1111-1111-1111-111111111111';

-- PB1_RESTAURANT: 10% (1000 bp) — illustrative restaurant tax rate.
-- SME NOTE: This could be PB1 (regional, ~10% common in many cities) OR PPN 11% (national VAT)
-- depending on the restaurant's registration and local regulation. The regime and rate MUST be
-- confirmed by an accounting + tax SME before going live.
INSERT INTO tax_charge_rule (
    id, rule_key, rule_version, rate_bp, service_charge_in_tax_base,
    provenance, source_note, currency,
    effective_from, effective_to, active,
    created_at, created_by, updated_at, updated_by, version, company_id
) VALUES (
    gen_random_uuid(),
    'PB1_RESTAURANT',
    'ILLUSTRATIVE-2026.1',
    1000,        -- 10% illustrative; NOT confirmed as PB1 nor PPN
    TRUE,        -- service charge in tax base: illustrative only; SME-gated
    'ILLUSTRATIVE_PLACEHOLDER',
    'ILLUSTRATIVE ONLY — not verified by DJP or local tax authority. The applicable regime '
    '(PB1 regional ~10% vs PPN national 11%) and whether service charge enters the tax base '
    'must be confirmed by an accounting + tax SME before any regulatory use. '
    'Seeded by V5 for development demo (Native Phase 2 pricing).',
    'IDR',
    DATE '2026-01-01',
    DATE '9999-12-31',
    TRUE,
    now(), 'system-migration', now(), 'system-migration', 0,
    '11111111-1111-1111-1111-111111111111'
);

-- SERVICE_CHARGE: 5% (500 bp) — illustrative service charge rate.
-- SME NOTE: Whether this is revenue (INCOME) or a tip-pool liability (LIABILITY) depends on the
-- restaurant's employment contract and local labour regulations. SERVICE_CHARGE_PAYABLE (liability)
-- is the safer default in many jurisdictions; SERVICE_CHARGE_REVENUE is used when the charge goes
-- directly to the house. An accounting SME must confirm the correct treatment.
INSERT INTO tax_charge_rule (
    id, rule_key, rule_version, rate_bp, service_charge_in_tax_base,
    provenance, source_note, currency,
    effective_from, effective_to, active,
    created_at, created_by, updated_at, updated_by, version, company_id
) VALUES (
    gen_random_uuid(),
    'SERVICE_CHARGE',
    'ILLUSTRATIVE-2026.1',
    500,         -- 5% illustrative; common Indonesian restaurant practice but not statutory
    TRUE,        -- service_charge_in_tax_base: relevant only to the PB1 rule; illustrative
    'ILLUSTRATIVE_PLACEHOLDER',
    'ILLUSTRATIVE ONLY — 5% service charge is a common but non-statutory restaurant practice in '
    'Indonesia. Whether it is revenue or a tip-pool liability (SERVICE_CHARGE_PAYABLE vs '
    'SERVICE_CHARGE_REVENUE) is SME- and contract-gated. Seeded by V5 for development demo '
    '(Native Phase 2 pricing).',
    'IDR',
    DATE '2026-01-01',
    DATE '9999-12-31',
    TRUE,
    now(), 'system-migration', now(), 'system-migration', 0,
    '11111111-1111-1111-1111-111111111111'
);
