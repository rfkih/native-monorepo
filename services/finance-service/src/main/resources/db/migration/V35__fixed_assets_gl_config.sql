-- finance-service V35 — Fixed-assets & deferrals GL wiring: chart-of-account + role maps (Phase 6, ADR 0020).
--
-- ============================================================================================================================
-- ILLUSTRATIVE PLACEHOLDER — SME-GATED — REPLACE VIA HIGHER-VERSION DATA (same pattern as V13/V25/V28/V30/V32).
-- ============================================================================================================================
-- Adds the fixed-asset cost (1500) + accumulated-depreciation contra (1590) + depreciation-expense
-- (5500) + prepaid-expense (1400) + deferred-revenue (2400) accounts, and maps the five new roles.
-- No posting_template and no event_kind widening: acquisition, deferral creation, and each monthly
-- amortization posting are AD-HOC balanced 2-leg JournalEntries built directly in the writers and
-- resolved via RoleAccountResolver — the Bank/Tax approach (V30/V32). EXPENSE (→5000, V13), REVENUE
-- (→4000, V13) and CASH_CLEARING (→1900, V13) are reused as the amortization/opening contras.
--
-- 1500 (fixed-asset cost) is the first NON-CURRENT asset account: the cash-flow statement classifies
-- its movements as INVESTING (capex) via the role-resolved investing set in CashFlowReader; 1590
-- stays in OPERATING (the non-cash depreciation add-back).
-- ============================================================================================================================

-- ---------------------------------------------------------------------------
-- New illustrative COA accounts.
-- ---------------------------------------------------------------------------
-- 1400 — Prepaid Expense (ASSET). Debited when an expense is paid up front; credited monthly as it
--         amortizes to EXPENSE (5000).
INSERT INTO chart_of_account (account_code, name, account_type)
VALUES ('1400', 'Prepaid Expense (ILLUSTRATIVE — SME-gated)', 'ASSET')
ON CONFLICT (account_code) DO NOTHING;

-- 1500 — Fixed Assets, Cost (ASSET, non-current). Debited at acquisition (capex).
INSERT INTO chart_of_account (account_code, name, account_type)
VALUES ('1500', 'Fixed Assets — Cost (ILLUSTRATIVE — SME-gated)', 'ASSET')
ON CONFLICT (account_code) DO NOTHING;

-- 1590 — Accumulated Depreciation (contra-ASSET, typed ASSET — its balance runs credit-side).
INSERT INTO chart_of_account (account_code, name, account_type)
VALUES ('1590', 'Accumulated Depreciation (contra-asset; ILLUSTRATIVE — SME-gated)', 'ASSET')
ON CONFLICT (account_code) DO NOTHING;

-- 2400 — Deferred Revenue (LIABILITY). Credited when revenue is received up front; debited monthly
--         as it is recognized to REVENUE (4000).
INSERT INTO chart_of_account (account_code, name, account_type)
VALUES ('2400', 'Deferred Revenue (ILLUSTRATIVE — SME-gated)', 'LIABILITY')
ON CONFLICT (account_code) DO NOTHING;

-- 5500 — Depreciation Expense (EXPENSE). Debited by each monthly depreciation run.
INSERT INTO chart_of_account (account_code, name, account_type)
VALUES ('5500', 'Depreciation Expense (ILLUSTRATIVE — SME-gated)', 'EXPENSE')
ON CONFLICT (account_code) DO NOTHING;

-- ---------------------------------------------------------------------------
-- role_account_map (version 1, illustrative). EXPENSE → 5000, REVENUE → 4000, CASH_CLEARING → 1900
-- (all V13) are reused as contras.
-- ---------------------------------------------------------------------------
INSERT INTO role_account_map
    (id, account_role, gl_account_code, version, uses_illustrative, effective_from, effective_to)
VALUES
    (gen_random_uuid(), 'FIXED_ASSET_COST',         '1500', 1, TRUE, DATE '2000-01-01', DATE '9999-12-31'),
    (gen_random_uuid(), 'ACCUMULATED_DEPRECIATION', '1590', 1, TRUE, DATE '2000-01-01', DATE '9999-12-31'),
    (gen_random_uuid(), 'DEPRECIATION_EXPENSE',     '5500', 1, TRUE, DATE '2000-01-01', DATE '9999-12-31'),
    (gen_random_uuid(), 'PREPAID_EXPENSE',          '1400', 1, TRUE, DATE '2000-01-01', DATE '9999-12-31'),
    (gen_random_uuid(), 'DEFERRED_REVENUE',         '2400', 1, TRUE, DATE '2000-01-01', DATE '9999-12-31');

-- ---------------------------------------------------------------------------
-- SME CONFIRMATION REQUIRED (do not remove until confirmed):
-- ---------------------------------------------------------------------------
-- A. Useful lives + salvage values are user-entered and unvalidated against Indonesian tax
--    depreciation classes (Kelompok 1-4 / bangunan); commercial-vs-tax-book depreciation is
--    single-book in this slice.
-- B. The start convention is full-month, beginning the month AFTER acquisition — SME must confirm
--    (mid-month proration and the acquisition-month convention differ by policy).
-- C. Straight-line only; declining-balance (saldo menurun) is a later method.
-- D. All five account codes are illustrative placeholders that must map to the client's real COA;
--    per-asset-class cost/accum-dep accounts are a later enhancement (one control pair here, like
--    AR 1200 / AP 2000).
-- E. Asset disposal/sale (gain/loss accounts) is deferred — the status column reserves it.
-- ============================================================================================================================
