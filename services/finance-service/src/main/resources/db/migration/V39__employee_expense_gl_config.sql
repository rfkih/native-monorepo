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
-- Also creates employee_expense_claim_ledger: ONE row per claim (UNIQUE(company_id, claim_id))
-- carrying the recognition / void / settlement facts as they land, in ANY arrival order. It is BOTH
-- the settle-once guard (ADR 0030 SS7 — a second ExpenseReimbursementSettled for a claim, a Kafka
-- re-delivery or a payroll-supersession re-emission, is a logged no-op) AND the ADR 0030 SS4
-- employee-payable drill-down source: an out-of-order or lost approval is now DETECTABLE (a
-- settlement with no matching recognition self-heals the row with a loud WARN instead of silently
-- having nothing to reconcile against).
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
-- 5. employee_expense_claim_ledger — ONE row per claim, the settle-once guard AND the ADR 0030 SS4
--    employee-payable drill-down source (review W1/S3). Auditable + FORCE RLS (tenant-scoped,
--    unlike the global-reference tables above).
-- ---------------------------------------------------------------------------
-- Column groups (each landed by a different consumer, in ANY arrival order):
--   identity     — claim_id / employee_id / org_unit_id / amount_minor / amount_currency: set ONCE
--                  by whichever consumer creates the row first (APPROVAL in the normal in-order
--                  case; SETTLEMENT when it arrives before the approval — see below); immutable
--                  thereafter (both events describe the SAME claim, so they always agree).
--   recognition  — recognized_at / recognition_entry_id: stamped by the APPROVAL consumer.
--   void         — voided_at / void_entry_id: stamped by the VOID consumer.
--   settlement   — settled_at / settlement_kind / payroll_run_id / run_seq / settlement_entry_id:
--                  stamped by the SETTLEMENT consumer.
-- *_entry_id columns point at journal_entry.id (no FK — journal_entry is a same-service table but
-- this column intentionally stays a plain UUID reference, mirroring bill.journal_entry_id/
-- invoice.journal_entry_id elsewhere in this schema, so a reference can be recorded before the
-- entry — never the case here — without an ordering constraint).
--
-- Reconciliation semantics (ADR 0030 SS7, the settle-once guard, generalized):
--   * APPROVAL lands first (the normal order): INSERT with recognition fields; NULL void/settlement.
--   * SETTLEMENT lands on an existing, unsettled row: UPDATE the settlement fields onto it.
--   * SETTLEMENT lands with NO row (settlement before approval, or a lost approval): INSERT a row
--     with ONLY settlement fields (recognition NULL) — a LOUD WARN logged (claim id only, no
--     amounts) flags account 2600 as unbacked by a recognition entry until the approval arrives.
--   * SETTLEMENT lands on an already-settled row (settled_at NOT NULL): the settle-once no-op — a
--     Kafka re-delivery or a payroll-supersession re-emission, logged INFO, never a double post.
--   * A late APPROVAL lands on a row a settlement already self-healed: UPDATE the recognition
--     fields onto it — logged INFO ("approval arrived after settlement, reconciled").
--   * VOID checks settled_at on this row exactly as the old settle-once guard did (an
--     already-settled claim is a loud logged skip — money already moved); a VOID with NO row is
--     the same loud-WARN pattern (approval missing or late) but still posts the contra.
CREATE TABLE employee_expense_claim_ledger (
    id                     UUID         NOT NULL PRIMARY KEY,
    claim_id               UUID         NOT NULL,
    employee_id            UUID         NOT NULL,
    org_unit_id            UUID         NOT NULL,
    amount_minor           BIGINT       NOT NULL,
    amount_currency        CHAR(3)      NOT NULL,

    recognized_at          TIMESTAMPTZ,
    recognition_entry_id   UUID,

    voided_at              TIMESTAMPTZ,
    void_entry_id          UUID,

    settled_at             TIMESTAMPTZ,
    settlement_kind        VARCHAR(16),
    payroll_run_id         UUID,        -- set only when settlement_kind = PAYROLL
    run_seq                INTEGER,     -- set only when settlement_kind = PAYROLL
    settlement_entry_id    UUID,

    -- Auditable (libs/tenant): 6 cols on every Native table (rule 4).
    created_at             TIMESTAMPTZ  NOT NULL,
    created_by             VARCHAR(255) NOT NULL,
    updated_at             TIMESTAMPTZ  NOT NULL,
    updated_by             VARCHAR(255) NOT NULL,
    version                BIGINT       NOT NULL,
    company_id             VARCHAR(64)  NOT NULL,

    CONSTRAINT ck_employee_expense_claim_ledger_settlement_kind
        CHECK (settlement_kind IS NULL OR settlement_kind IN ('DIRECT', 'PAYROLL'))
);

ALTER TABLE employee_expense_claim_ledger ENABLE ROW LEVEL SECURITY;
ALTER TABLE employee_expense_claim_ledger FORCE ROW LEVEL SECURITY;

CREATE POLICY employee_expense_claim_ledger_tenant_isolation ON employee_expense_claim_ledger
    USING      (company_id = current_setting('app.current_tenant', true))
    WITH CHECK (company_id = current_setting('app.current_tenant', true));

-- The settle-once guard itself (unchanged mechanism, now on this table): at most one row per
-- (tenant, claim). A concurrent racer inserting a FRESH row for the SAME claim — two settlements
-- racing before any approval has landed, e.g. the payroll-supersession re-emission race, ADR 0030
-- SS7 — trips this constraint; the writer's transaction aborts and the caller recovers with a
-- separate-transaction re-read (the SaleWriter/AssignmentWriter/GiftCardSaleWriter conflict-recovery
-- idiom), never a double post. A settlement landing on an ALREADY-EXISTING unsettled row is a plain
-- UPDATE, not an insert, so it is not this constraint's concern (best-effort protected by the
-- inherited Auditable @Version optimistic lock instead — a residual, not exercised by this phase's
-- tests).
CREATE UNIQUE INDEX uq_employee_expense_claim_ledger_claim
    ON employee_expense_claim_ledger (company_id, claim_id);

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
