-- carwash-service V5 — per-tenant tax and service-charge configuration (POS-parity pricing).
-- Column-for-column mirror of restaurant-service V5__tax_charge_rule.sql — same resolver contract
-- (ORDER BY rule_version DESC picks the highest-version active row effective at a given date), same
-- provenance/RLS/audit shape, so a single shared resolver strategy works across verticals.
--
-- ============================================================================================================================
-- ILLUSTRATIVE PLACEHOLDER — SME-GATED — REPLACE VIA HIGHER-VERSION DATA
-- ============================================================================================================================
-- The rule seeded at the bottom of this migration is an INTENTIONAL ILLUSTRATIVE PLACEHOLDER.
--
-- WHAT IS NOT CONFIRMED:
--   1. REGIME: Whether carwash services are subject to national PPN (VAT, currently 11%), a regional
--      indirect levy (comparable in spirit to PB1 for restaurants — rates vary by municipality), or
--      some combination is UNCONFIRMED. The seeded key "VAT_CARWASH" and rate 1100 bp (11%) are
--      illustrative only — do NOT assert this as the confirmed regime.
--   2. SERVICE CHARGE: Carwash tickets carry a service_charge_minor column (V6), but NO
--      SERVICE_CHARGE rule row is seeded here — the resolver's no-rule fall-through yields zero, so
--      no ticket accrues a service charge until an SME-reviewed rule is inserted for this key.
--   3. TAX BASE COMPOSITION: service_charge_in_tax_base is set TRUE on the seeded row purely for
--      schema/resolver consistency with restaurant-service; it has no observable effect today since
--      no service charge is ever computed without a SERVICE_CHARGE rule. If/when a carwash service
--      charge rule is introduced, this flag must be revisited by an SME at that time.
--   4. RATE: 11% is an illustrative round number aligned to the current national PPN rate for
--      development only — NOT a confirmed statement that carwash falls under PPN vs a regional levy.
--
-- To replace with a real rule (NO code change required):
--   INSERT INTO tax_charge_rule (..., rule_version, rate_bp, provenance, source_note, effective_from)
--       VALUES (..., 'OFFICIAL-2027.1', <real_rate>, 'OFFICIAL', '<SME sign-off note>', <date>);
-- The resolver (ORDER BY rule_version DESC) prefers the higher-version row automatically.
-- The ILLUSTRATIVE row remains as an audit trail and does NOT need to be deleted.
-- ============================================================================================================================

CREATE TABLE tax_charge_rule (
    id                          UUID         NOT NULL PRIMARY KEY,
    rule_key                    VARCHAR(64)  NOT NULL,
    rule_version                VARCHAR(64)  NOT NULL,

    -- Rate in basis points (1 bp = 0.01%; 1100 bp = 11%). NEVER a float (rule 8).
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
-- ILLUSTRATIVE SEED — for the demo tenant (11111111-1111-1111-1111-111111111111) only.
-- The row below is provenance='ILLUSTRATIVE_PLACEHOLDER'. The rate and regime are NOT verified.
-- No SERVICE_CHARGE row is seeded (see note 2 above): the no-rule fall-through yields zero.
-- ============================================================================================================================

-- tax_charge_rule has FORCE ROW LEVEL SECURITY. Migrations run as the DB owner (SUPERUSER or
-- BYPASSRLS-granted role), so we must set the tenant GUC for the INSERT's WITH CHECK to pass.
-- SET LOCAL applies only to this transaction (the Flyway migration's implicit transaction). This is
-- a fresh INSERT (not an UPDATE against existing rows), so the RLS-migration-backfill gotcha — a
-- FORCE-RLS UPDATE silently matching 0 rows without a NO FORCE/FORCE wrap — does not apply here;
-- SET LOCAL alone is sufficient, exactly as restaurant-service V5 does it.
SET LOCAL app.current_tenant = '11111111-1111-1111-1111-111111111111';

-- VAT_CARWASH: 11% (1100 bp) — illustrative carwash indirect-tax rate.
-- SME NOTE: The applicable regime for carwash services (national PPN 11% vs a regional indirect
-- levy on vehicle-service businesses, comparable in spirit to restaurant PB1) is UNCONFIRMED and
-- must be verified by an accounting + tax SME before going live. The rate happens to equal the
-- current national PPN rate, but this is NOT an assertion that PPN is the correct regime.
INSERT INTO tax_charge_rule (
    id, rule_key, rule_version, rate_bp, service_charge_in_tax_base,
    provenance, source_note, currency,
    effective_from, effective_to, active,
    created_at, created_by, updated_at, updated_by, version, company_id
) VALUES (
    gen_random_uuid(),
    'VAT_CARWASH',
    'ILLUSTRATIVE-2026.1',
    1100,        -- 11% illustrative; NOT confirmed as national PPN nor a regional levy
    TRUE,        -- service charge in tax base: inert today (no SERVICE_CHARGE rule seeded); SME-gated
    'ILLUSTRATIVE_PLACEHOLDER',
    'ILLUSTRATIVE ONLY — not verified by DJP or a local/regional tax authority. Whether carwash '
    'services fall under national PPN (currently 11%) or a regional indirect levy on vehicle-service '
    'businesses is UNCONFIRMED and must be confirmed by an accounting + tax SME before any '
    'regulatory use. No service-charge rule is seeded for carwash (no-rule fall-through = zero). '
    'Seeded by V5 for development demo (Native carwash POS-parity pricing).',
    'IDR',
    DATE '2026-01-01',
    DATE '9999-12-31',
    TRUE,
    now(), 'system-migration', now(), 'system-migration', 0,
    '11111111-1111-1111-1111-111111111111'
);
