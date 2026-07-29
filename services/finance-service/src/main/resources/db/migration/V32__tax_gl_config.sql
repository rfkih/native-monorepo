-- finance-service V32 — Tax GL wiring: chart-of-account + role maps (Phase 4 Tax / PPN, ADR 0017).
--
-- ============================================================================================================================
-- ILLUSTRATIVE PLACEHOLDER — SME-GATED — REPLACE VIA HIGHER-VERSION DATA (same pattern as V13/V25/V28/V30).
-- ============================================================================================================================
-- Adds the net VAT-payable settlement account (2300) + the excess-input-VAT carryforward asset (1310),
-- and maps the new VAT_PAYABLE / VAT_CREDIT_CARRYFORWARD roles. No posting_template and no event_kind
-- widening: filing a PPN return posts an AD-HOC balanced JournalEntry built directly in TaxFilingWriter
-- (Dr VAT_OUTPUT 2200 / Cr VAT_INPUT 1300 / net → VAT_PAYABLE 2300 (payable) or VAT_CREDIT_CARRYFORWARD
-- 1310 (creditable)); settlement posts Dr VAT_PAYABLE / Cr CASH_CLEARING, all resolving accounts via
-- RoleAccountResolver — exactly the Bank-reconciliation approach (V30). VAT_OUTPUT (→2200, V25),
-- VAT_INPUT (→1300, V28) and CASH_CLEARING (→1900, V13) are reused.
-- ============================================================================================================================

-- ---------------------------------------------------------------------------
-- New illustrative COA accounts.
-- ---------------------------------------------------------------------------
-- 2300 — VAT Payable, settlement (LIABILITY). The net PPN owed to the tax authority for a filed
--         period whose output VAT exceeds its input VAT; cleared to zero on settlement. Flows into the
--         balance sheet automatically (LIABILITY, credit-normal) via glTrialBalanceAsOf.
INSERT INTO chart_of_account (account_code, name, account_type)
VALUES ('2300', 'VAT Payable — PPN settlement (ILLUSTRATIVE — regime + account SME-gated)', 'LIABILITY')
ON CONFLICT (account_code) DO NOTHING;

-- 1310 — VAT Credit Carryforward (ASSET). Excess recoverable input VAT carried forward to the next
--         period (the Indonesian default: dikompensasikan). Refund (restitusi) eligibility, year-end
--         handling, and the exact treatment are SME-gated.
INSERT INTO chart_of_account (account_code, name, account_type)
VALUES ('1310', 'VAT Credit Carryforward — PPN (ILLUSTRATIVE — regime + refund policy SME-gated)',
        'ASSET')
ON CONFLICT (account_code) DO NOTHING;

-- ---------------------------------------------------------------------------
-- role_account_map: VAT_PAYABLE → 2300, VAT_CREDIT_CARRYFORWARD → 1310 (version 1, illustrative).
--   VAT_OUTPUT → 2200 (V25), VAT_INPUT → 1300 (V28), CASH_CLEARING → 1900 (V13) are reused.
-- ---------------------------------------------------------------------------
INSERT INTO role_account_map
    (id, account_role, gl_account_code, version, uses_illustrative, effective_from, effective_to)
VALUES
    (gen_random_uuid(), 'VAT_PAYABLE',             '2300', 1, TRUE, DATE '2000-01-01', DATE '9999-12-31'),
    (gen_random_uuid(), 'VAT_CREDIT_CARRYFORWARD', '1310', 1, TRUE, DATE '2000-01-01', DATE '9999-12-31');

-- ---------------------------------------------------------------------------
-- SME CONFIRMATION REQUIRED (do not remove until confirmed):
-- ---------------------------------------------------------------------------
-- A. Rate — the 11% PPN applied in InvoiceWriter / BillWriter is illustrative (PPN was 10%→11%, 12%
--    signalled). A real regime needs a rate table + effective dates, not a code constant.
-- B. Net-creditable policy — this slice carries excess input VAT forward to 1310 (the Indonesian
--    default). Refund (restitusi) eligibility, year-end handling and the exact treatment are SME-gated.
-- C. PKP status/threshold — assumes the company is a registered PKP (taxable entrepreneur); no
--    threshold check.
-- D. The settlement account (2300) and carryforward account (1310) codes are illustrative placeholders
--    that must map to the client's real COA.
-- E. e-Faktur — the CSV export is an illustrative flat layout, NOT the certified DJP e-Faktur import
--    schema (no NSFP tax-invoice serial numbers, no certificate, no DJP API). Real e-invoicing
--    integration is deferred.
-- ============================================================================================================================
