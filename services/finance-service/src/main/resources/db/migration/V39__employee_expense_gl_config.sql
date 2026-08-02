-- finance-service V39 — Employee expense-claims GL wiring: chart-of-account, role map, posting
-- templates, and the settle-once guard table (ADR 0030, expense-claims program, phase E2).
--
-- ============================================================================================================================
-- ILLUSTRATIVE PLACEHOLDER — SME-GATED — REPLACE VIA HIGHER-VERSION DATA (same pattern as V13/V25/V28)
-- ============================================================================================================================
-- Adds the Employee Expense Payable control account (2600 — verified collision-free across V2-V38: the
-- highest seeded 26xx account before this migration was none, the highest 2xxx was 2510), maps the new
-- EMPLOYEE_EXPENSE_PAYABLE role, and seeds the three EXPENSE_CLAIM_* posting templates. EXPENSE_CLAIM_
-- APPROVED reuses the generic EXPENSE role (-> 5000, V13) exactly like the EXPENSE_RECORDED template
-- does — the claim's specific `gl_hint` is resolved separately, on write, into the DIMENSIONAL
-- ledger_posting only (GlAccountResolver.resolveExpense, mirroring ExpensePostingWriter); the
-- double-entry GL leg stays on the generic role, unchanged from every other EXPENSE-shaped template in
-- this codebase. EXPENSE_CLAIM_SETTLED reuses CASH_CLEARING (-> 1900, V13). The 2600 account code and
-- the VOID/SETTLED template shapes are ILLUSTRATIVE — an accountant swaps them via higher-version rows.
--
-- Also creates employee_expense_settlement: the settle-once guard row (ADR 0030 SS7) a per-claim
-- UNIQUE(company_id, claim_id) makes any second ExpenseReimbursementSettled for a claim — a Kafka
-- re-delivery or a payroll-supersession re-emission — a logged no-op instead of a double-post.
-- ============================================================================================================================

-- ---------------------------------------------------------------------------
-- 1. Widen the event_kind CHECK to accept the three new EXPENSE_CLAIM_* kinds (drop/recreate, per
--    V16/V25/V28/V37). These are BRAND-NEW event kinds with no existing seeded rows/live traffic, so
--    (unlike V37's SALE-v3 hazard) there is no deployment-order risk: nothing resolves
--    EXPENSE_CLAIM_APPROVED/VOID/SETTLED until this migration's own companion Java (EventKind + the
--    empexpense writers) lands in the SAME change.
-- ---------------------------------------------------------------------------
ALTER TABLE posting_template DROP CONSTRAINT ck_posting_template_event_kind;

ALTER TABLE posting_template
    ADD CONSTRAINT ck_posting_template_event_kind
        CHECK (event_kind IN (
            'SALE', 'EXPENSE', 'LABOR', 'SALE_VOID', 'SALE_REFUND',
            'INVOICE_ISSUED', 'PAYMENT_RECEIVED', 'INVOICE_VOID',
            'BILL_POSTED', 'BILL_PAYMENT_MADE', 'BILL_VOID',
            'GIFT_CARD_SALE',
            'EXPENSE_CLAIM_APPROVED', 'EXPENSE_CLAIM_VOID', 'EXPENSE_CLAIM_SETTLED'));

-- ---------------------------------------------------------------------------
-- 2. New illustrative COA account.
-- ---------------------------------------------------------------------------
-- 2600 — Employee Expense Payable (LIABILITY). Credited when a manager approves a claim (the expense
--         recognition), debited when the claim is settled (DIRECT pay or a POSTED payroll run).
--         Verified free: grepping every V*.sql account_code literal before this migration shows the
--         highest seeded 2xxx code is 2510 (V37); no 26xx code exists.
INSERT INTO chart_of_account (account_code, name, account_type)
VALUES ('2600', 'Employee Expense Payable (ILLUSTRATIVE — SME-gated)', 'LIABILITY')
ON CONFLICT (account_code) DO NOTHING;

-- ---------------------------------------------------------------------------
-- 3. role_account_map: EMPLOYEE_EXPENSE_PAYABLE -> 2600 (version 1, illustrative).
--    EXPENSE -> 5000 (V13) and CASH_CLEARING -> 1900 (V13) are already mapped and reused here.
-- ---------------------------------------------------------------------------
INSERT INTO role_account_map
    (id, account_role, gl_account_code, version, uses_illustrative, effective_from, effective_to)
VALUES
    (gen_random_uuid(), 'EMPLOYEE_EXPENSE_PAYABLE', '2600', 1, TRUE, DATE '2000-01-01', DATE '9999-12-31');

-- ---------------------------------------------------------------------------
-- 4. Posting templates (version 1, illustrative).
-- ---------------------------------------------------------------------------
INSERT INTO posting_template (id, event_kind, version, uses_illustrative, effective_from, effective_to) VALUES
    (gen_random_uuid(), 'EXPENSE_CLAIM_APPROVED', 1, TRUE, DATE '2000-01-01', DATE '9999-12-31'),
    (gen_random_uuid(), 'EXPENSE_CLAIM_VOID',     1, TRUE, DATE '2000-01-01', DATE '9999-12-31'),
    (gen_random_uuid(), 'EXPENSE_CLAIM_SETTLED',  1, TRUE, DATE '2000-01-01', DATE '9999-12-31');

-- EXPENSE_CLAIM_APPROVED: Dr EXPENSE (5000, generic role — matches how EventKind.EXPENSE is seeded;
--   the claim's specific gl_hint drives ONLY the dimensional ledger_posting, resolved on write by
--   GlAccountResolver, never this generic double-entry leg) / Cr EMPLOYEE_EXPENSE_PAYABLE (2600).
INSERT INTO posting_template_line (id, template_id, line_no, account_role, side, amount_basis)
SELECT gen_random_uuid(), pt.id, 1, 'EXPENSE',                   'DEBIT',  'GROSS'
  FROM posting_template pt WHERE pt.event_kind = 'EXPENSE_CLAIM_APPROVED' AND pt.version = 1;
INSERT INTO posting_template_line (id, template_id, line_no, account_role, side, amount_basis)
SELECT gen_random_uuid(), pt.id, 2, 'EMPLOYEE_EXPENSE_PAYABLE',  'CREDIT', 'GROSS'
  FROM posting_template pt WHERE pt.event_kind = 'EXPENSE_CLAIM_APPROVED' AND pt.version = 1;

-- EXPENSE_CLAIM_VOID: the exact contra of the approval — Dr EMPLOYEE_EXPENSE_PAYABLE (2600) /
--   Cr EXPENSE (5000).
INSERT INTO posting_template_line (id, template_id, line_no, account_role, side, amount_basis)
SELECT gen_random_uuid(), pt.id, 1, 'EMPLOYEE_EXPENSE_PAYABLE',  'DEBIT',  'GROSS'
  FROM posting_template pt WHERE pt.event_kind = 'EXPENSE_CLAIM_VOID' AND pt.version = 1;
INSERT INTO posting_template_line (id, template_id, line_no, account_role, side, amount_basis)
SELECT gen_random_uuid(), pt.id, 2, 'EXPENSE',                   'CREDIT', 'GROSS'
  FROM posting_template pt WHERE pt.event_kind = 'EXPENSE_CLAIM_VOID' AND pt.version = 1;

-- EXPENSE_CLAIM_SETTLED: a balance-sheet-only move (the expense was recognised at approval) —
--   Dr EMPLOYEE_EXPENSE_PAYABLE (2600) / Cr CASH_CLEARING (1900).
INSERT INTO posting_template_line (id, template_id, line_no, account_role, side, amount_basis)
SELECT gen_random_uuid(), pt.id, 1, 'EMPLOYEE_EXPENSE_PAYABLE',  'DEBIT',  'GROSS'
  FROM posting_template pt WHERE pt.event_kind = 'EXPENSE_CLAIM_SETTLED' AND pt.version = 1;
INSERT INTO posting_template_line (id, template_id, line_no, account_role, side, amount_basis)
SELECT gen_random_uuid(), pt.id, 2, 'CASH_CLEARING',             'CREDIT', 'GROSS'
  FROM posting_template pt WHERE pt.event_kind = 'EXPENSE_CLAIM_SETTLED' AND pt.version = 1;

-- ---------------------------------------------------------------------------
-- 5. employee_expense_settlement — the settle-once guard (ADR 0030 SS7). Auditable + FORCE RLS
--    (tenant-scoped, unlike the global-reference tables above).
-- ---------------------------------------------------------------------------
CREATE TABLE employee_expense_settlement (
    id                UUID         NOT NULL PRIMARY KEY,
    claim_id          UUID         NOT NULL,
    settlement_kind   VARCHAR(16)  NOT NULL,
    payroll_run_id    UUID,        -- set only when settlement_kind = PAYROLL
    run_seq           INTEGER,     -- set only when settlement_kind = PAYROLL
    journal_entry_id  UUID         NOT NULL,
    settled_at        TIMESTAMPTZ  NOT NULL,

    -- Auditable (libs/tenant): 6 cols on every Native table (rule 4).
    created_at        TIMESTAMPTZ  NOT NULL,
    created_by        VARCHAR(255) NOT NULL,
    updated_at        TIMESTAMPTZ  NOT NULL,
    updated_by        VARCHAR(255) NOT NULL,
    version           BIGINT       NOT NULL,
    company_id        VARCHAR(64)  NOT NULL,

    CONSTRAINT ck_employee_expense_settlement_kind
        CHECK (settlement_kind IN ('DIRECT', 'PAYROLL'))
);

ALTER TABLE employee_expense_settlement ENABLE ROW LEVEL SECURITY;
ALTER TABLE employee_expense_settlement FORCE ROW LEVEL SECURITY;

CREATE POLICY employee_expense_settlement_tenant_isolation ON employee_expense_settlement
    USING      (company_id = current_setting('app.current_tenant', true))
    WITH CHECK (company_id = current_setting('app.current_tenant', true));

-- The settle-once guard itself: at most one settlement row per (tenant, claim). A concurrent racer
-- (a different event id settling the SAME claim — the payroll-supersession re-emission race, ADR 0030
-- SS7) trips this constraint; the writer's transaction aborts and the caller recovers with a
-- separate-transaction re-read (the SaleWriter/AssignmentWriter/GiftCardSaleWriter conflict-recovery
-- idiom), never a double post.
CREATE UNIQUE INDEX uq_employee_expense_settlement_claim
    ON employee_expense_settlement (company_id, claim_id);

-- ---------------------------------------------------------------------------
-- SME CONFIRMATION REQUIRED (do not remove until confirmed):
-- ---------------------------------------------------------------------------
-- A. Employee Expense Payable control account (2600) must be mapped to the client's real SAK-EMKM COA.
-- B. The claim's specific gl_hint category (e.g. 'cogs', 'supplies', 'utilities') resolves ONLY into
--    the dimensional ledger_posting (per-account P&L), exactly like the pre-existing ExpenseRecorded
--    path; the double-entry GL leg stays on the single generic EXPENSE role (5000) for every category.
--    A category-specific double-entry split (like BILL_POSTED's net/tax split) is deferred — same
--    posture V28's footer recorded for AP.
-- ============================================================================================================================
