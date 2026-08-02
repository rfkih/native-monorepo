-- Track P Phase P8 (THR — Tunjangan Hari Raya, ADR 0035): the THR off-cycle run type.
--
-- (1) payroll_run.run_type — a run is REGULAR (the monthly cadence, unchanged default) or THR (the
-- H-7 off-cycle religious-holiday allowance run, Permenaker 6/2016). The UNIQUE key that guarded
-- "one run_seq per (company, period)" is swapped for "one run_seq per (company, period, run_type)"
-- so a THR run and a REGULAR run for the SAME period get their OWN independent run_seq counters
-- and can coexist without superseding each other — the P3-review landmine
-- (PayslipLineRepository.ACTIVE_RUN_PREDICATE) is fixed alongside this migration in the SAME
-- commit, never one without the other (see that class's javadoc for the full hazard).
--
-- The DROP+ADD is safe with NO backfill guard needed: every existing row is already run_type =
-- 'REGULAR' (the column default), so the old 3-column key (company_id, period, run_seq) and the new
-- 4-column key (company_id, period, run_type, run_seq) hold the EXACT SAME set of distinct tuples
-- for pre-P8 data — the new key is strictly more specific, never less, so it cannot fail to
-- validate against rows that already satisfied the old one.
ALTER TABLE payroll_run
    ADD COLUMN run_type VARCHAR(16) NOT NULL DEFAULT 'REGULAR'
        CHECK (run_type IN ('REGULAR', 'THR'));

ALTER TABLE payroll_run DROP CONSTRAINT uq_payroll_run_company_period_seq;

ALTER TABLE payroll_run
    ADD CONSTRAINT uq_payroll_run_company_period_type_seq
        UNIQUE (company_id, period, run_type, run_seq);

-- (2) pay_component.run_scope — which run type(s) a component participates in. ALL (the default —
-- every pre-P8 component, e.g. PPH21, keeps applying to every run type unchanged) | REGULAR (BASE,
-- allowances, commission, overtime, unpaid leave, expense reimbursement, every BPJS leg — statutory
-- spec: BPJS NEVER applies to a THR run) | THR (the new THR earning component only). The engine
-- resolves ONLY run_scope-matching components for a given run's run_type (PayrollRunWriter).
ALTER TABLE pay_component
    ADD COLUMN run_scope VARCHAR(16) NOT NULL DEFAULT 'ALL'
        CHECK (run_scope IN ('ALL', 'REGULAR', 'THR'));

-- (3) employee.hire_date — feeds THR proration (Permenaker 6/2016: months of service / 12,
-- capped at 1x for >=12 months). Nullable (a pre-P8 employee has none on file yet); the engine
-- falls back to the employee's earliest assignment effective_from when absent (PayrollRunWriter).
-- NOT PII (a start date, not sensitive data) — a plain column, no encryption.
ALTER TABLE employee ADD COLUMN hire_date DATE NULL;
