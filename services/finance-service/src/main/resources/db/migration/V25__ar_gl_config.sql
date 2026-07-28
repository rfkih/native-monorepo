-- finance-service V25 — AR GL wiring: chart-of-account, role maps, posting templates (Phase 1 AR).
--
-- ============================================================================================================================
-- ILLUSTRATIVE PLACEHOLDER — SME-GATED — REPLACE VIA HIGHER-VERSION DATA (same pattern as V13/V17).
-- ============================================================================================================================
-- Adds the AR control account (1200) + output-VAT liability (2200), maps the new AR / VAT_OUTPUT
-- roles, and seeds the INVOICE_ISSUED / PAYMENT_RECEIVED / INVOICE_VOID posting templates. The AR
-- account, the VAT regime/rate, and the mappings are all ILLUSTRATIVE — an accountant swaps them by
-- inserting higher-version rows (uses_illustrative=FALSE); the resolver prefers the highest version.
--
-- No amount_basis widening is needed: the AR templates reuse GROSS (grand total), GROSS_REVENUE
-- (net / subtotal) and TAX — all already accepted by ck_template_amount_basis (V17).
-- ============================================================================================================================

-- ---------------------------------------------------------------------------
-- 1. Widen the event_kind CHECK to accept the three AR kinds (drop/recreate, per V16).
-- ---------------------------------------------------------------------------
ALTER TABLE posting_template DROP CONSTRAINT ck_posting_template_event_kind;

ALTER TABLE posting_template
    ADD CONSTRAINT ck_posting_template_event_kind
        CHECK (event_kind IN (
            'SALE', 'EXPENSE', 'LABOR', 'SALE_VOID', 'SALE_REFUND',
            'INVOICE_ISSUED', 'PAYMENT_RECEIVED', 'INVOICE_VOID'));

-- ---------------------------------------------------------------------------
-- 2. New illustrative COA accounts.
-- ---------------------------------------------------------------------------
-- 1200 — Accounts Receivable control (ASSET). Debited on issue, credited on payment. Flows into the
--         balance sheet automatically (ASSET, debit-normal) via glTrialBalanceAsOf.
INSERT INTO chart_of_account (account_code, name, account_type)
VALUES ('1200', 'Accounts Receivable (ILLUSTRATIVE — SME-gated)', 'ASSET')
ON CONFLICT (account_code) DO NOTHING;

-- 2200 — Output VAT Payable (LIABILITY). The credit for the tax leg of an issued invoice. The PPN
--         regime + rate + real account are SME-gated.
INSERT INTO chart_of_account (account_code, name, account_type)
VALUES ('2200', 'VAT Output Payable — AR (ILLUSTRATIVE — regime + rate + account SME-gated)',
        'LIABILITY')
ON CONFLICT (account_code) DO NOTHING;

-- ---------------------------------------------------------------------------
-- 3. role_account_map: AR → 1200, VAT_OUTPUT → 2200 (version 1, illustrative).
--    REVENUE → 4000 (V13) and CASH_CLEARING → 1900 (V13) are already mapped and reused here.
-- ---------------------------------------------------------------------------
INSERT INTO role_account_map
    (id, account_role, gl_account_code, version, uses_illustrative, effective_from, effective_to)
VALUES
    (gen_random_uuid(), 'AR',         '1200', 1, TRUE, DATE '2000-01-01', DATE '9999-12-31'),
    (gen_random_uuid(), 'VAT_OUTPUT', '2200', 1, TRUE, DATE '2000-01-01', DATE '9999-12-31');

-- ---------------------------------------------------------------------------
-- 4. Posting templates (version 1, illustrative).
-- ---------------------------------------------------------------------------
INSERT INTO posting_template (id, event_kind, version, uses_illustrative, effective_from, effective_to) VALUES
    (gen_random_uuid(), 'INVOICE_ISSUED',   1, TRUE, DATE '2000-01-01', DATE '9999-12-31'),
    (gen_random_uuid(), 'PAYMENT_RECEIVED', 1, TRUE, DATE '2000-01-01', DATE '9999-12-31'),
    (gen_random_uuid(), 'INVOICE_VOID',     1, TRUE, DATE '2000-01-01', DATE '9999-12-31');

-- INVOICE_ISSUED: Dr AR (GROSS = total) / Cr REVENUE (GROSS_REVENUE = net) / Cr VAT_OUTPUT (TAX).
--   Balance: total = net + tax. The TAX line is zero-omitted (JournalPostingService) for a
--   non-taxable invoice, collapsing to Dr AR / Cr REVENUE with net == total.
INSERT INTO posting_template_line (id, template_id, line_no, account_role, side, amount_basis)
SELECT gen_random_uuid(), pt.id, 1, 'AR',         'DEBIT',  'GROSS'
  FROM posting_template pt WHERE pt.event_kind = 'INVOICE_ISSUED' AND pt.version = 1;
INSERT INTO posting_template_line (id, template_id, line_no, account_role, side, amount_basis)
SELECT gen_random_uuid(), pt.id, 2, 'REVENUE',    'CREDIT', 'GROSS_REVENUE'
  FROM posting_template pt WHERE pt.event_kind = 'INVOICE_ISSUED' AND pt.version = 1;
INSERT INTO posting_template_line (id, template_id, line_no, account_role, side, amount_basis)
SELECT gen_random_uuid(), pt.id, 3, 'VAT_OUTPUT', 'CREDIT', 'TAX'
  FROM posting_template pt WHERE pt.event_kind = 'INVOICE_ISSUED' AND pt.version = 1;

-- PAYMENT_RECEIVED: Dr CASH_CLEARING (GROSS = payment amount) / Cr AR (GROSS = payment amount).
INSERT INTO posting_template_line (id, template_id, line_no, account_role, side, amount_basis)
SELECT gen_random_uuid(), pt.id, 1, 'CASH_CLEARING', 'DEBIT',  'GROSS'
  FROM posting_template pt WHERE pt.event_kind = 'PAYMENT_RECEIVED' AND pt.version = 1;
INSERT INTO posting_template_line (id, template_id, line_no, account_role, side, amount_basis)
SELECT gen_random_uuid(), pt.id, 2, 'AR',            'CREDIT', 'GROSS'
  FROM posting_template pt WHERE pt.event_kind = 'PAYMENT_RECEIVED' AND pt.version = 1;

-- INVOICE_VOID: contra of issue — Dr REVENUE (net) / Dr VAT_OUTPUT (tax) / Cr AR (total).
--   The VAT debit is zero-omitted for a non-taxable invoice, collapsing to Dr REVENUE / Cr AR.
INSERT INTO posting_template_line (id, template_id, line_no, account_role, side, amount_basis)
SELECT gen_random_uuid(), pt.id, 1, 'REVENUE',    'DEBIT',  'GROSS_REVENUE'
  FROM posting_template pt WHERE pt.event_kind = 'INVOICE_VOID' AND pt.version = 1;
INSERT INTO posting_template_line (id, template_id, line_no, account_role, side, amount_basis)
SELECT gen_random_uuid(), pt.id, 2, 'VAT_OUTPUT', 'DEBIT',  'TAX'
  FROM posting_template pt WHERE pt.event_kind = 'INVOICE_VOID' AND pt.version = 1;
INSERT INTO posting_template_line (id, template_id, line_no, account_role, side, amount_basis)
SELECT gen_random_uuid(), pt.id, 3, 'AR',         'CREDIT', 'GROSS'
  FROM posting_template pt WHERE pt.event_kind = 'INVOICE_VOID' AND pt.version = 1;

-- ---------------------------------------------------------------------------
-- SME CONFIRMATION REQUIRED (do not remove until confirmed):
-- ---------------------------------------------------------------------------
-- A. AR control account (1200) must be mapped to the client's real SAK-EMKM COA.
-- B. Output VAT: PPN 11% is the illustrative default applied in InvoiceWriter; the regime, rate, and
--    liability account (2200) must be confirmed by a tax SME.
-- C. Revenue recognition on issue (accrual) vs on payment (cash-basis) must be confirmed for the
--    client's reporting basis; the seed recognises revenue on ISSUE (accrual).
-- ============================================================================================================================
