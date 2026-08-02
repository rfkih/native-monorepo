-- finance-service V42 (Track P Phase P7) — the OVERTIME labor GL wiring: a new chart-of-account row
-- + one mapping_rule row so the '5130-OVERTIME' gl_account hint employee-service's PayrollRunWriter
-- now emits (Track P Phase P7's per-component LaborCostAllocated bucket split — reconciliation #4)
-- resolves to a real account instead of failing safe to the labor-clearing/expense suspense.
--
-- Mirrors V3's exact idiom (labor-cost postings): chart_of_account + mapping_rule are GLOBAL
-- reference data (not Auditable, not RLS); LaborCostPostingWriter/GlAccountResolver already resolve
-- a labor gl_account hint DATA-DRIVEN via mapping_rule (ENGINEERING-STANDARDS §7) — no Java change
-- is needed in finance-service for this migration to take effect.
--
-- 6110 verified free: grepping every V*.sql chart_of_account INSERT before this migration shows the
-- seeded 61xx codes are 6100 (V3, BPJS Employer Expense) and 6120 (V3, Commission Expense); 6110 is
-- unused. ILLUSTRATIVE — SME-gated exactly like the other labor accounts (an accountant swaps the
-- code via a higher-version mapping_rule row, no code change).

INSERT INTO chart_of_account (account_code, name, account_type) VALUES
    ('6110', 'Overtime Expense', 'EXPENSE');

INSERT INTO mapping_rule
    (id, posting_type, gl_hint, version, gl_account_code, effective_from, effective_to) VALUES
    (gen_random_uuid(), 'EXPENSE', '5130-OVERTIME', 1, '6110', DATE '2000-01-01', DATE '9999-12-31');
