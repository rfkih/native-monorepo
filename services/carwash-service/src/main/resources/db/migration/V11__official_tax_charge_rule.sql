-- carwash-service V11 — go-live: supersede the illustrative tax rule with the OFFICIAL rule (ADR 0042).
--
-- ============================================================================================================================
-- PROVENANCE FLIP ONLY — RATE UNCHANGED
-- ============================================================================================================================
-- This migration does NOT change the rate. It inserts a higher-`rule_version` row with
-- provenance='OFFICIAL' for the same rule_key / rate_bp / service_charge_in_tax_base as the
-- ILLUSTRATIVE_PLACEHOLDER row seeded by V5:
--   VAT_CARWASH: rate_bp 1100 (11%), service_charge_in_tax_base TRUE — UNCHANGED.
--
-- `rule_version = 'OFFICIAL-2026.1'` sorts lexicographically after 'ILLUSTRATIVE-2026.1' ('O' > 'I'),
-- so TaxChargeRuleRepository.findEffective (ORDER BY rule_version DESC LIMIT 1) now resolves this
-- row. TaxChargeService sets usesIllustrative=true iff the resolved row's provenance equals
-- 'ILLUSTRATIVE_PLACEHOLDER' — with this row resolved, usesIllustrative becomes false. No Java code
-- change is required.
--
-- The V5 ILLUSTRATIVE_PLACEHOLDER row is left in place as an audit trail — it is NOT deleted or
-- deactivated, matching the "REPLACE VIA HIGHER-VERSION DATA" guidance already documented in V5.
-- ============================================================================================================================

-- tax_charge_rule has FORCE ROW LEVEL SECURITY. Migrations run as the DB owner (SUPERUSER or
-- BYPASSRLS-granted role), so we must set the tenant GUC for the INSERT's WITH CHECK to pass.
-- SET LOCAL applies only to this transaction (the Flyway migration's implicit transaction). This is a
-- fresh INSERT (not an UPDATE against existing rows), so the RLS-migration-backfill gotcha — a
-- FORCE-RLS UPDATE silently matching 0 rows without a NO FORCE/FORCE wrap — does not apply here;
-- SET LOCAL alone is sufficient, exactly as V5 does it.
SET LOCAL app.current_tenant = '11111111-1111-1111-1111-111111111111';

-- VAT_CARWASH: 11% (1100 bp) — OFFICIAL, confirmed for go-live. Rate unchanged from V5.
INSERT INTO tax_charge_rule (
    id, rule_key, rule_version, rate_bp, service_charge_in_tax_base,
    provenance, source_note, currency,
    effective_from, effective_to, active,
    created_at, created_by, updated_at, updated_by, version, company_id
) VALUES (
    gen_random_uuid(),
    'VAT_CARWASH',
    'OFFICIAL-2026.1',
    1100,        -- 11% — unchanged from the ILLUSTRATIVE-2026.1 rate
    TRUE,        -- service charge in tax base: unchanged from the ILLUSTRATIVE-2026.1 row
    'OFFICIAL',
    'Official production rate confirmed for go-live (ADR 0042).',
    'IDR',
    DATE '2026-01-01',
    DATE '9999-12-31',
    TRUE,
    now(), 'system-migration', now(), 'system-migration', 0,
    '11111111-1111-1111-1111-111111111111'
);
