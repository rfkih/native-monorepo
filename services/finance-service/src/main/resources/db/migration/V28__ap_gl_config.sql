-- finance-service V28 — AP GL wiring: chart-of-account, role maps, posting templates (Phase 2 AP).
--
-- ============================================================================================================================
-- ILLUSTRATIVE PLACEHOLDER — SME-GATED — REPLACE VIA HIGHER-VERSION DATA (same pattern as V13/V25).
-- ============================================================================================================================
-- Adds the AP control account (2000) + recoverable input-VAT asset (1300), maps the new AP / VAT_INPUT
-- roles, and seeds the BILL_POSTED / BILL_PAYMENT_MADE / BILL_VOID posting templates. Reuses EXPENSE
-- (→5000, V13) and CASH_CLEARING (→1900, V13). The AP account, VAT regime/rate, and mappings are all
-- ILLUSTRATIVE — an accountant swaps them via higher-version rows.
-- ============================================================================================================================

-- ---------------------------------------------------------------------------
-- 1. Widen the event_kind CHECK to accept the three AP kinds (drop/recreate, per V16/V25).
-- ---------------------------------------------------------------------------
ALTER TABLE posting_template DROP CONSTRAINT ck_posting_template_event_kind;

ALTER TABLE posting_template
    ADD CONSTRAINT ck_posting_template_event_kind
        CHECK (event_kind IN (
            'SALE', 'EXPENSE', 'LABOR', 'SALE_VOID', 'SALE_REFUND',
            'INVOICE_ISSUED', 'PAYMENT_RECEIVED', 'INVOICE_VOID',
            'BILL_POSTED', 'BILL_PAYMENT_MADE', 'BILL_VOID'));

-- ---------------------------------------------------------------------------
-- 2. Widen the amount_basis CHECK to add NET (the expense/AP net leg selector).
-- ---------------------------------------------------------------------------
ALTER TABLE posting_template_line DROP CONSTRAINT ck_template_amount_basis;

ALTER TABLE posting_template_line
    ADD CONSTRAINT ck_template_amount_basis
        CHECK (amount_basis IN (
            'GROSS', 'GROSS_REVENUE', 'DISCOUNT', 'SERVICE_CHARGE', 'TAX', 'NET'));

-- ---------------------------------------------------------------------------
-- 3. New illustrative COA accounts.
-- ---------------------------------------------------------------------------
-- 2000 — Accounts Payable control (LIABILITY). Credited on post, debited on payment. Flows into the
--         balance sheet automatically (LIABILITY, credit-normal) via glTrialBalanceAsOf.
INSERT INTO chart_of_account (account_code, name, account_type)
VALUES ('2000', 'Accounts Payable (ILLUSTRATIVE — SME-gated)', 'LIABILITY')
ON CONFLICT (account_code) DO NOTHING;

-- 1300 — VAT Input Recoverable (ASSET). The debit for the tax leg of a posted bill — VAT receivable
--         from the tax authority. The PPN regime + rate + real account are SME-gated.
INSERT INTO chart_of_account (account_code, name, account_type)
VALUES ('1300', 'VAT Input Recoverable — AP (ILLUSTRATIVE — regime + rate + account SME-gated)',
        'ASSET')
ON CONFLICT (account_code) DO NOTHING;

-- ---------------------------------------------------------------------------
-- 4. role_account_map: AP → 2000, VAT_INPUT → 1300 (version 1, illustrative).
--    EXPENSE → 5000 (V13) and CASH_CLEARING → 1900 (V13) are already mapped and reused here.
-- ---------------------------------------------------------------------------
INSERT INTO role_account_map
    (id, account_role, gl_account_code, version, uses_illustrative, effective_from, effective_to)
VALUES
    (gen_random_uuid(), 'AP',        '2000', 1, TRUE, DATE '2000-01-01', DATE '9999-12-31'),
    (gen_random_uuid(), 'VAT_INPUT', '1300', 1, TRUE, DATE '2000-01-01', DATE '9999-12-31');

-- ---------------------------------------------------------------------------
-- 5. Posting templates (version 1, illustrative).
-- ---------------------------------------------------------------------------
INSERT INTO posting_template (id, event_kind, version, uses_illustrative, effective_from, effective_to) VALUES
    (gen_random_uuid(), 'BILL_POSTED',       1, TRUE, DATE '2000-01-01', DATE '9999-12-31'),
    (gen_random_uuid(), 'BILL_PAYMENT_MADE', 1, TRUE, DATE '2000-01-01', DATE '9999-12-31'),
    (gen_random_uuid(), 'BILL_VOID',         1, TRUE, DATE '2000-01-01', DATE '9999-12-31');

-- BILL_POSTED: Dr EXPENSE (NET) / Dr VAT_INPUT (TAX) / Cr AP (GROSS = total).
--   Balance: total = net + tax. The TAX line zero-omits (JournalPostingService) for a non-taxable
--   bill, collapsing to Dr EXPENSE / Cr AP with net == total.
INSERT INTO posting_template_line (id, template_id, line_no, account_role, side, amount_basis)
SELECT gen_random_uuid(), pt.id, 1, 'EXPENSE',   'DEBIT',  'NET'
  FROM posting_template pt WHERE pt.event_kind = 'BILL_POSTED' AND pt.version = 1;
INSERT INTO posting_template_line (id, template_id, line_no, account_role, side, amount_basis)
SELECT gen_random_uuid(), pt.id, 2, 'VAT_INPUT', 'DEBIT',  'TAX'
  FROM posting_template pt WHERE pt.event_kind = 'BILL_POSTED' AND pt.version = 1;
INSERT INTO posting_template_line (id, template_id, line_no, account_role, side, amount_basis)
SELECT gen_random_uuid(), pt.id, 3, 'AP',        'CREDIT', 'GROSS'
  FROM posting_template pt WHERE pt.event_kind = 'BILL_POSTED' AND pt.version = 1;

-- BILL_PAYMENT_MADE: Dr AP (GROSS = payment amount) / Cr CASH_CLEARING (GROSS = payment amount).
INSERT INTO posting_template_line (id, template_id, line_no, account_role, side, amount_basis)
SELECT gen_random_uuid(), pt.id, 1, 'AP',            'DEBIT',  'GROSS'
  FROM posting_template pt WHERE pt.event_kind = 'BILL_PAYMENT_MADE' AND pt.version = 1;
INSERT INTO posting_template_line (id, template_id, line_no, account_role, side, amount_basis)
SELECT gen_random_uuid(), pt.id, 2, 'CASH_CLEARING', 'CREDIT', 'GROSS'
  FROM posting_template pt WHERE pt.event_kind = 'BILL_PAYMENT_MADE' AND pt.version = 1;

-- BILL_VOID: contra of post — Dr AP (total) / Cr EXPENSE (net) / Cr VAT_INPUT (tax).
INSERT INTO posting_template_line (id, template_id, line_no, account_role, side, amount_basis)
SELECT gen_random_uuid(), pt.id, 1, 'AP',        'DEBIT',  'GROSS'
  FROM posting_template pt WHERE pt.event_kind = 'BILL_VOID' AND pt.version = 1;
INSERT INTO posting_template_line (id, template_id, line_no, account_role, side, amount_basis)
SELECT gen_random_uuid(), pt.id, 2, 'EXPENSE',   'CREDIT', 'NET'
  FROM posting_template pt WHERE pt.event_kind = 'BILL_VOID' AND pt.version = 1;
INSERT INTO posting_template_line (id, template_id, line_no, account_role, side, amount_basis)
SELECT gen_random_uuid(), pt.id, 3, 'VAT_INPUT', 'CREDIT', 'TAX'
  FROM posting_template pt WHERE pt.event_kind = 'BILL_VOID' AND pt.version = 1;

-- ---------------------------------------------------------------------------
-- SME CONFIRMATION REQUIRED (do not remove until confirmed):
-- ---------------------------------------------------------------------------
-- A. AP control account (2000) must be mapped to the client's real SAK-EMKM COA.
-- B. Input VAT: PPN 11% is the illustrative default applied in BillWriter; the regime, rate, and the
--    recoverable-asset account (1300) must be confirmed by a tax SME (and whether it is recoverable).
-- C. The bill net posts to a single EXPENSE account (5000) in Phase 2; per-line expense-account /
--    cost-centre selection is deferred (ADR 0015).
-- ============================================================================================================================
