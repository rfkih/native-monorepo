-- finance-service V4 (#P3c) — multi-currency / FX rates.
--
-- ADDITIVE expand over V3 (never edit an applied migration). It adds the fx_rate GLOBAL
-- reference table and seeds LOUD, deliberately-fake STUB rates.
--
-- DESIGN NOTE — fx_rate is GLOBAL reference data, modelled EXACTLY like chart_of_account /
-- mapping_rule (V2/V3): an exchange rate (USD->IDR closing for 2026-06) is the SAME for every
-- tenant — it is configuration / market reference data, NOT a company's books. So it is
-- deliberately NOT Auditable and NOT under RLS (no company_id column at all — binding it to RLS
-- would be wrong and would force every tenant to re-seed identical rates). It is read via a plain
-- JdbcTemplate provider with no tenant predicate, exactly like GlAccountResolver reads mapping_rule.
-- The TENANT business data — ledger_posting, consolidated_revenue, consolidated_pnl — stays
-- Auditable + under FORCE RLS as before; a presentation conversion reads an RLS-scoped read-model
-- row AND the global fx_rate, then converts in memory. A future milestone can promote fx_rate to
-- per-company overrides (company_id nullable, a tenant row shadows the global default) without a
-- breaking change.
--
-- ============================================================================================
-- ⚠ ILLUSTRATIVE STUB FX RATES — NOT AUTHORITATIVE. Deliberately round, obviously-fake figures.
--   NEVER present a STUB-derived converted amount as official. Swap to a real provider by
--   INSERTing a new effective-dated row with provenance LIVE/OFFICIAL — this is DATA, not a code
--   change. A future LiveFxRateProvider resolves the same table/shape; conversions then stop being
--   flagged uses_stub_fx automatically. The rate is DATA, never hardcoded in Java.
-- ============================================================================================

-- ---------------------------------------------------------------------------
-- fx_rate (global reference data — VERSIONED + EFFECTIVE-DATED, scaled-integer rate)
-- ---------------------------------------------------------------------------
-- The rate is an EXACT scaled-integer pair: value = rate_unscaled / 10^rate_scale (major units of
-- to_ccy per 1 major unit of from_ccy). NEVER a float/double/numeric — money/rate precision is
-- integer, exactly like the libs/money Money discipline (rule 8). The FxRate value type is
-- reconstructed from these two integer columns by the JDBC RowMapper.
CREATE TABLE fx_rate (
    id             UUID         NOT NULL PRIMARY KEY,

    -- The ordered ISO-4217 currency pair this rate converts FROM -> TO.
    from_ccy       CHAR(3)      NOT NULL,
    to_ccy         CHAR(3)      NOT NULL,

    -- value = rate_unscaled / 10^rate_scale. Strictly positive; scale bounded so an extreme
    -- amount * unscaled stays well within the transient BigDecimal multiply (no long overflow
    -- mid-calc, longValueExact only at the final minor-unit result).
    rate_unscaled  BIGINT       NOT NULL CHECK (rate_unscaled > 0),
    rate_scale     SMALLINT     NOT NULL CHECK (rate_scale BETWEEN 0 AND 12),

    -- CLOSING for balance translation, AVERAGE for P&L (IAS-21), SPOT for ad-hoc / transaction-date.
    rate_type      VARCHAR(16)  NOT NULL CHECK (rate_type IN ('CLOSING', 'AVERAGE', 'SPOT')),

    -- STUB for the seeded illustrative rates; LIVE/OFFICIAL once a real provider feeds them.
    provenance     VARCHAR(16)  NOT NULL CHECK (provenance IN ('STUB', 'LIVE', 'OFFICIAL')),

    -- Human-readable origin ("ILLUSTRATIVE STUB — not an official rate", or "BI middle rate ..." ).
    source_note    VARCHAR(255) NOT NULL,

    version        INTEGER      NOT NULL,

    -- Effective-dating window. effective_to open-ended = 9999-12-31 sentinel, never NULL
    -- (ENGINEERING-STANDARDS §2.5 — same convention as mapping_rule, keeps the range predicate
    -- index-friendly).
    effective_from DATE         NOT NULL,
    effective_to   DATE         NOT NULL DEFAULT DATE '9999-12-31',

    -- One version per pair + type + from-date.
    CONSTRAINT uq_fx_rate
        UNIQUE (from_ccy, to_ccy, rate_type, effective_from, version)
);

-- The resolver scans by (pair, type) and filters by the effective window, taking the highest
-- version (ORDER BY version DESC LIMIT 1) — exactly mirroring idx_mapping_rule_resolution.
CREATE INDEX idx_fx_rate_resolution
    ON fx_rate (from_ccy, to_ccy, rate_type, effective_from, effective_to);

-- ---------------------------------------------------------------------------
-- LOUD STUB seed — deliberately round fakes (≈ mid-2026 ballpark, NOT a real quote).
-- ---------------------------------------------------------------------------
-- USD<->IDR, both CLOSING (balance translation) and AVERAGE (P&L flow translation), effective from
-- a far-past date through the 9999-12-31 sentinel so any as-of date resolves. The inverse legs are
-- the EXACT reciprocals: 1 USD = 16,000 IDR  <=>  1 IDR = 0.0000625 USD (= 625 / 10^7, scale 7).
-- AVERAGE is deliberately a touch different from CLOSING (15,900 vs 16,000) so the closing-vs-
-- average selection by rate_type is visibly exercised by the translation tests.
INSERT INTO fx_rate
    (id, from_ccy, to_ccy, rate_unscaled, rate_scale, rate_type, provenance, source_note,
     version, effective_from, effective_to) VALUES
    -- 1 USD = 16,000 IDR (CLOSING).
    (gen_random_uuid(), 'USD', 'IDR', 16000, 0, 'CLOSING', 'STUB',
     'ILLUSTRATIVE STUB — not an official rate', 1, DATE '2000-01-01', DATE '9999-12-31'),
    -- 1 USD = 15,900 IDR (AVERAGE) — deliberately ≠ closing.
    (gen_random_uuid(), 'USD', 'IDR', 15900, 0, 'AVERAGE', 'STUB',
     'ILLUSTRATIVE STUB — not an official rate', 1, DATE '2000-01-01', DATE '9999-12-31'),
    -- 1 IDR = 0.0000625 USD (CLOSING) = exact inverse of 16,000.
    (gen_random_uuid(), 'IDR', 'USD', 625, 7, 'CLOSING', 'STUB',
     'ILLUSTRATIVE STUB — not an official rate', 1, DATE '2000-01-01', DATE '9999-12-31'),
    -- 1 IDR ≈ 0.0000629 USD (AVERAGE) ≈ inverse of 15,900.
    (gen_random_uuid(), 'IDR', 'USD', 629, 7, 'AVERAGE', 'STUB',
     'ILLUSTRATIVE STUB — not an official rate', 1, DATE '2000-01-01', DATE '9999-12-31');
