-- finance-service V40 — Payroll liability recognition GL wiring: chart-of-account, role map, and
-- payroll_run_ledger run-type + liability control columns (ADR 0032, Track P phase P4).
--
-- ============================================================================================================================
-- ILLUSTRATIVE PLACEHOLDER — SME-GATED — REPLACE VIA HIGHER-VERSION DATA (same pattern as V13/V25/V28/V39)
-- ============================================================================================================================
-- Adds the five payroll-liability control accounts (2610-2690 — verified collision-free across
-- V2-V39: the highest seeded 26xx account before this migration was 2600, V39 employee-expense
-- payable). Maps the five new AccountRoles. NO posting_template rows here, unlike V3/V13/V39's
-- EventKind-driven templates: PayrollLiabilitiesPosted's leg COUNT is variable (a run may carry
-- anywhere from 1 to 5 non-zero liability buckets, and a bucket may sit on the debit side when
-- negative — the December Art-17 refund case), which does not fit the FIXED posting_template
-- shape. finance.labor.service.PayrollLiabilityWriter instead builds an AD-HOC balanced
-- JournalEntry directly via RoleAccountResolver, exactly the TaxSettlementWriter/TaxFilingWriter
-- precedent.
--
-- Also widens payroll_run_ledger (V3, control/state table) with run_type + the liability control
-- columns, and re-keys its UNIQUE constraint + supersession-scan index onto
-- (company_id, period, run_type, run_seq) — the DEFAULT 'REGULAR' keeps every existing row (and
-- every existing LaborCostPostingWriter/PayrollReconciliationWriter behaviour) byte-identical; a
-- future THR run (Track P phase P8, ADR 0035) coexists un-superseded with a REGULAR run of the
-- same run_seq because it carries a different run_type.
-- ============================================================================================================================

-- ---------------------------------------------------------------------------
-- 1. payroll_run_ledger — run_type + liability control columns.
-- ---------------------------------------------------------------------------
-- run_type: DEFAULT 'REGULAR' backfills every existing row (no THR run exists yet). Every writer
-- that upserts this table (LaborCostPostingWriter, PayrollReconciliationWriter, and the new
-- PayrollLiabilityWriter) now stamps it from the event's own run_type field.
ALTER TABLE payroll_run_ledger ADD COLUMN run_type VARCHAR(16) NOT NULL DEFAULT 'REGULAR';

-- liability_entry_id / liability_state: the LIABILITY dimension's OWN lifecycle, tracked
-- separately from the pre-existing `state` column (which stays the LaborCostPostingWriter /
-- PayrollReconciliationWriter reconciliation lifecycle, PENDING -> RECONCILED|RECONCILE_FAILED,
-- any -> SUPERSEDED|CURRENCY_MISMATCH). Sharing one `state` column across three writers would
-- create a cross-writer coordination gap: if LaborCostPostingWriter flips `state` to SUPERSEDED
-- before the liability event for the same prior run arrives, a shared-column supersession scan
-- would never find that prior run's liability entry to reverse. Two independent lifecycles avoid
-- the gap: NULL liability_state means "no PayrollLiabilitiesPosted has landed for this run yet".
ALTER TABLE payroll_run_ledger ADD COLUMN liability_entry_id UUID NULL;
ALTER TABLE payroll_run_ledger ADD COLUMN liability_state VARCHAR(32) NULL;

ALTER TABLE payroll_run_ledger
    ADD CONSTRAINT ck_payroll_run_ledger_liability_state
        CHECK (liability_state IS NULL OR liability_state IN ('POSTED', 'SUPERSEDED'));

-- Re-key the one-control-row-per-run UNIQUE constraint onto (company_id, period, run_type,
-- run_seq) — a future THR run_seq=1 must NOT collide with a REGULAR run_seq=1 for the same period.
ALTER TABLE payroll_run_ledger DROP CONSTRAINT uq_payroll_run_ledger;
ALTER TABLE payroll_run_ledger
    ADD CONSTRAINT uq_payroll_run_ledger UNIQUE (company_id, period, run_type, run_seq);

-- Re-key the supersession-scan index the same way (drop the old 2-column index; the new 3-column
-- index still serves any query that only filters on the (company_id, period) prefix).
DROP INDEX idx_payroll_run_ledger_period;
CREATE INDEX idx_payroll_run_ledger_period ON payroll_run_ledger (company_id, period, run_type);

-- ---------------------------------------------------------------------------
-- 2. New illustrative COA accounts (all LIABILITY — money the company owes, recognised the moment
--    a payroll run posts, settled later via Track P phase P5's payroll_settlement).
-- ---------------------------------------------------------------------------
INSERT INTO chart_of_account (account_code, name, account_type) VALUES
    ('2610', 'PPh21 Payable (ILLUSTRATIVE — SME-gated)',                       'LIABILITY'),
    ('2620', 'BPJS Kesehatan Payable (ILLUSTRATIVE — SME-gated)',              'LIABILITY'),
    ('2630', 'BPJS Ketenagakerjaan Payable (ILLUSTRATIVE — SME-gated)',        'LIABILITY'),
    ('2640', 'Net Wages Payable (ILLUSTRATIVE — SME-gated)',                   'LIABILITY'),
    ('2690', 'Other Payroll Deductions Payable (ILLUSTRATIVE — SME-gated)',    'LIABILITY');

-- ---------------------------------------------------------------------------
-- 3. role_account_map — the five new AccountRoles (version 1, illustrative). LABOR_CLEARING (6900)
--    is already mapped (V13) and reused unchanged as the Dr leg of the liability entry.
-- ---------------------------------------------------------------------------
INSERT INTO role_account_map
    (id, account_role, gl_account_code, version, uses_illustrative, effective_from, effective_to)
VALUES
    (gen_random_uuid(), 'PPH21_PAYABLE',              '2610', 1, TRUE, DATE '2000-01-01', DATE '9999-12-31'),
    (gen_random_uuid(), 'BPJS_KES_PAYABLE',           '2620', 1, TRUE, DATE '2000-01-01', DATE '9999-12-31'),
    (gen_random_uuid(), 'BPJS_TK_PAYABLE',            '2630', 1, TRUE, DATE '2000-01-01', DATE '9999-12-31'),
    (gen_random_uuid(), 'NET_WAGES_PAYABLE',          '2640', 1, TRUE, DATE '2000-01-01', DATE '9999-12-31'),
    (gen_random_uuid(), 'OTHER_DEDUCTIONS_PAYABLE',   '2690', 1, TRUE, DATE '2000-01-01', DATE '9999-12-31');

-- ---------------------------------------------------------------------------
-- SME CONFIRMATION REQUIRED (do not remove until confirmed):
-- ---------------------------------------------------------------------------
-- A. The five payroll-liability control accounts (2610-2690) must be mapped to the client's real
--    SAK-EMKM COA.
-- B. NET_WAGES_PAYABLE (2640) tracks unpaid net wages only until Track P phase P5's bank-file /
--    settlement lands; until then it accrues every POSTED run with no automated clearing.
-- ============================================================================================================================
