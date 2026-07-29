-- finance-service V30 — Bank GL wiring: chart-of-account + role maps (Phase 3, ADR 0016).
--
-- ============================================================================================================================
-- ILLUSTRATIVE PLACEHOLDER — SME-GATED — REPLACE VIA HIGHER-VERSION DATA (same pattern as V13/V25/V28).
-- ============================================================================================================================
-- Adds the single BANK control account (1000) + interest-income / bank-charges accounts, and maps the
-- BANK / INTEREST_INCOME / BANK_CHARGES roles. No posting_template and no event_kind widening: a
-- reconciliation posts an AD-HOC balanced 2-line JournalEntry (Dr BANK / Cr contra, or the reverse for
-- a withdrawal) built directly in ReconciliationWriter and resolving accounts via RoleAccountResolver.
-- CASH_CLEARING (→1900, V13) is reused as the sweep contra.
-- ============================================================================================================================

-- ---------------------------------------------------------------------------
-- New illustrative COA accounts.
-- ---------------------------------------------------------------------------
-- 1000 — Bank (ASSET). The single control account ALL bank accounts post to; per-account balances
--         live in the bank_account sub-ledger (ADR 0016). Flows into the balance sheet (debit-normal).
INSERT INTO chart_of_account (account_code, name, account_type)
VALUES ('1000', 'Bank (ILLUSTRATIVE — SME-gated)', 'ASSET')
ON CONFLICT (account_code) DO NOTHING;

-- 4100 — Interest Income (REVENUE). The contra for a deposit reconciled as INTEREST.
INSERT INTO chart_of_account (account_code, name, account_type)
VALUES ('4100', 'Interest Income (ILLUSTRATIVE — SME-gated)', 'REVENUE')
ON CONFLICT (account_code) DO NOTHING;

-- 5400 — Bank Charges (EXPENSE). The contra for a withdrawal reconciled as BANK_FEE.
INSERT INTO chart_of_account (account_code, name, account_type)
VALUES ('5400', 'Bank Charges (ILLUSTRATIVE — SME-gated)', 'EXPENSE')
ON CONFLICT (account_code) DO NOTHING;

-- ---------------------------------------------------------------------------
-- role_account_map: BANK → 1000, INTEREST_INCOME → 4100, BANK_CHARGES → 5400 (v1, illustrative).
--   CASH_CLEARING → 1900 (V13) is reused as the CLEARING-category contra.
-- ---------------------------------------------------------------------------
INSERT INTO role_account_map
    (id, account_role, gl_account_code, version, uses_illustrative, effective_from, effective_to)
VALUES
    (gen_random_uuid(), 'BANK',            '1000', 1, TRUE, DATE '2000-01-01', DATE '9999-12-31'),
    (gen_random_uuid(), 'INTEREST_INCOME', '4100', 1, TRUE, DATE '2000-01-01', DATE '9999-12-31'),
    (gen_random_uuid(), 'BANK_CHARGES',    '5400', 1, TRUE, DATE '2000-01-01', DATE '9999-12-31');

-- ---------------------------------------------------------------------------
-- SME CONFIRMATION REQUIRED (do not remove until confirmed):
-- ---------------------------------------------------------------------------
-- A. The single BANK control account (1000) must map to the client's real COA; if per-bank-account GL
--    accounts are required, that is a later enhancement (this slice keeps one control account +
--    sub-ledger, mirroring AR 1200 / AP 2000).
-- B. Interest-income (4100) and bank-charges (5400) account codes are illustrative placeholders.
-- ============================================================================================================================
