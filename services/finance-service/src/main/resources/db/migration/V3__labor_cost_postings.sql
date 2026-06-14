-- finance-service V3 (#23) — labor-cost postings: consume PayrollPosted + LaborCostAllocated.
--
-- ADDITIVE expand over V2 (never edit an applied migration). It:
--   1. adds the labor dimensions to ledger_posting (payroll_run_id, run_seq, posting_role,
--      uses_illustrative_rules, unallocated) so a labor posting is reversible, auditable, and
--      self-describing — amount_minor may now be NEGATIVE (a REVERSAL contra entry);
--   2. adds uses_illustrative_rules to consolidated_pnl (the illustrative flag must reach the
--      read model and is STICKY/monotonic per (company, period, currency) — OR-ed in by the
--      atomic upsert, so once any placeholder-derived labor lands the period stays flagged);
--   3. adds the payroll_run_ledger CONTROL/STATE table (Auditable + RLS): one row per
--      (company_id, period, run_seq) driving reconciliation (allocated_sum vs control total)
--      and supersession (reverse-prior-run-then-repost, append-only);
--   4. seeds the labor chart_of_account accounts finance posts to (6000 Salaries Expense,
--      6100 BPJS Employer Expense, 6120 Commission Expense, plus 6900 Unallocated-Labor-Clearing —
--      the explicit, visible labor suspense, DISTINCT from the expense general-suspense 9999), and
--      the LABOR mapping_rule rows keyed on (posting_type=EXPENSE, gl_hint=<the event gl_account
--      string employee-service emits>), versioned + effective-dated with the 9999-12-31 sentinel.
--
-- DESIGN — finance RE-RESOLVES the canonical GL account from the event's gl_account HINT via
-- mapping_rule (CQRS, resolve-on-write; the ledger owns its chart of accounts), exactly like the
-- ExpenseRecorded path. The producer's gl_account is never blindly trusted. An unrecognised labor
-- hint FAILS SAFE to the expense suspense 9999; the UNALLOCATED bucket is routed to the explicit
-- 6900 labor-clearing (money is never dropped — HR-3).
--
-- chart_of_account + mapping_rule stay GLOBAL reference data (not Auditable, not RLS) as in V2.
-- The tenant business data — ledger_posting, consolidated_pnl, and now payroll_run_ledger — stay
-- Auditable + under FORCE RLS (rule 4 + rule 5). GL mapping is DATA, never hardcoded in Java
-- (ENGINEERING-STANDARDS §7) — the resolver reads these rows.

-- ---------------------------------------------------------------------------
-- ledger_posting — labor dimensions (additive; defaults keep existing rows valid)
-- ---------------------------------------------------------------------------
-- payroll_run_id / run_seq are NULL for REVENUE and ExpenseRecorded postings (non-payroll). They
-- are set for every labor posting (PRIMARY or REVERSAL) so a run is found and reversed on
-- supersession. posting_role distinguishes an original from a contra; uses_illustrative_rules and
-- unallocated are self-describing flags on the posting. NOT NULL with a DEFAULT so the existing
-- REVENUE/EXPENSE rows are valid without a backfill.
ALTER TABLE ledger_posting ADD COLUMN payroll_run_id UUID;
ALTER TABLE ledger_posting ADD COLUMN run_seq INTEGER;
ALTER TABLE ledger_posting ADD COLUMN posting_role VARCHAR(16) NOT NULL DEFAULT 'PRIMARY'
    CHECK (posting_role IN ('PRIMARY', 'REVERSAL'));
ALTER TABLE ledger_posting ADD COLUMN uses_illustrative_rules BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE ledger_posting ADD COLUMN unallocated BOOLEAN NOT NULL DEFAULT false;

-- A run's postings are scanned by payroll_run_id to reverse them on supersession; index the path.
CREATE INDEX idx_ledger_posting_run ON ledger_posting (payroll_run_id);

-- ---------------------------------------------------------------------------
-- consolidated_pnl — the illustrative flag (sticky/monotonic)
-- ---------------------------------------------------------------------------
-- The flag is OR-ed in by PnlReadModelWriter's atomic upsert, so once any illustrative-derived
-- labor lands in a (company, period, currency) the row is flagged true and stays true — the
-- dashboard must keep badging the period provisional (HR integrity, #23). DEFAULT false keeps the
-- existing revenue/expense-from-sale rows correct.
ALTER TABLE consolidated_pnl ADD COLUMN uses_illustrative_rules BOOLEAN NOT NULL DEFAULT false;

-- ---------------------------------------------------------------------------
-- payroll_run_ledger (the control/state table — Auditable + RLS, company_id)
-- ---------------------------------------------------------------------------
-- One row per (company_id, period, run_seq). Drives:
--   * RECONCILIATION — allocated_sum_minor accumulates each LaborCostAllocated bucket's amount as
--     buckets arrive (finance does NOT block on PayrollPosted); when PayrollPosted arrives its
--     control_total_minor is compared against allocated_sum_minor: match -> RECONCILED, mismatch ->
--     RECONCILE_FAILED (loud, postings stay on the books, period not presented as final).
--   * SUPERSESSION — a higher run_seq for the same (company, period) supersedes lower ACTIVE runs;
--     finance reverses the prior run's PRIMARY postings (append-only contra) then reposts, flips
--     the prior row to SUPERSEDED. A per-(company, period) deterministic pg_advisory_xact_lock
--     (keyed on company_id + period, taken before any run-row access) serializes interleaved
--     deliveries of two runs — including two genuinely NEW runs for which no run row exists yet.
-- state machine: PENDING -> RECONCILED | RECONCILE_FAILED ; any -> SUPERSEDED | CURRENCY_MISMATCH.
-- SUPERSEDED and CURRENCY_MISMATCH are terminal (sticky) — no transition clears them.
CREATE TABLE payroll_run_ledger (
    id                      UUID         NOT NULL PRIMARY KEY,

    payroll_run_id          UUID         NOT NULL,
    period                  VARCHAR(7)   NOT NULL,
    run_seq                 INTEGER      NOT NULL,

    -- PENDING | RECONCILED | RECONCILE_FAILED | SUPERSEDED | CURRENCY_MISMATCH
    -- SUPERSEDED and CURRENCY_MISMATCH are TERMINAL/STICKY (a later event cannot clear them).
    state                   VARCHAR(24)  NOT NULL,

    -- Running sum of this run's LaborCostAllocated bucket amounts (minor units). NEVER a float.
    allocated_sum_minor     BIGINT       NOT NULL DEFAULT 0,
    -- The PayrollPosted labor control total (minor units); NULL until PayrollPosted arrives.
    control_total_minor     BIGINT,
    currency                CHAR(3)      NOT NULL,

    uses_illustrative_rules BOOLEAN      NOT NULL DEFAULT false,

    -- Auditable (libs/tenant) — present on every Native table.
    created_at              TIMESTAMPTZ  NOT NULL,
    created_by              VARCHAR(255) NOT NULL,
    updated_at              TIMESTAMPTZ  NOT NULL,
    updated_by              VARCHAR(255) NOT NULL,
    version                 BIGINT       NOT NULL,
    company_id              VARCHAR(64)  NOT NULL,

    CONSTRAINT ck_payroll_run_ledger_state
        CHECK (state IN ('PENDING', 'RECONCILED', 'RECONCILE_FAILED', 'SUPERSEDED',
                         'CURRENCY_MISMATCH')),
    -- One control row per run: (company_id, period, run_seq) is unique. The consumer upserts on it.
    CONSTRAINT uq_payroll_run_ledger UNIQUE (company_id, period, run_seq)
);

ALTER TABLE payroll_run_ledger ENABLE ROW LEVEL SECURITY;
ALTER TABLE payroll_run_ledger FORCE ROW LEVEL SECURITY;

CREATE POLICY payroll_run_ledger_tenant_isolation ON payroll_run_ledger
    USING (company_id = current_setting('app.current_tenant', true))
    WITH CHECK (company_id = current_setting('app.current_tenant', true));

-- The supersession lookup scans prior ACTIVE runs for a (company, period); index that access path.
CREATE INDEX idx_payroll_run_ledger_period ON payroll_run_ledger (company_id, period);

-- ---------------------------------------------------------------------------
-- chart_of_account — seed finance's canonical LABOR expense accounts
-- ---------------------------------------------------------------------------
-- The accounts finance's labor postings resolve TO. 6900 is the explicit, visible LABOR-CLEARING /
-- suspense account the UNALLOCATED bucket lands on — DISTINCT from the expense general-suspense 9999
-- so an unallocated labor cost is its own queryable concern an operator clears once the assignment
-- is known. All account_type EXPENSE (labor is genuinely an expense).
INSERT INTO chart_of_account (account_code, name, account_type) VALUES
    ('6000', 'Salaries Expense',                'EXPENSE'),
    ('6100', 'BPJS Employer Expense',           'EXPENSE'),
    ('6120', 'Commission Expense',              'EXPENSE'),
    ('6900', 'Unallocated-Labor-Clearing',      'EXPENSE');

-- ---------------------------------------------------------------------------
-- mapping_rule — seed the LABOR rules (resolve the event gl_account HINT -> finance account)
-- ---------------------------------------------------------------------------
-- Labor buckets resolve under posting_type=EXPENSE keyed on the event's gl_account string as the
-- gl_hint (the pay_component.gl_account set employee-service emits): 5100-SALARY, 5200-BPJS-ER,
-- 5120-COMMISSION. Versioned + effective-dated with the 9999-12-31 open-ended sentinel, so any run
-- resolves. An emitted labor gl_account with NO rule fails safe to the expense suspense 9999.
INSERT INTO mapping_rule
    (id, posting_type, gl_hint, version, gl_account_code, effective_from, effective_to) VALUES
    (gen_random_uuid(), 'EXPENSE', '5100-SALARY',    1, '6000', DATE '2000-01-01', DATE '9999-12-31'),
    (gen_random_uuid(), 'EXPENSE', '5200-BPJS-ER',   1, '6100', DATE '2000-01-01', DATE '9999-12-31'),
    (gen_random_uuid(), 'EXPENSE', '5120-COMMISSION', 1, '6120', DATE '2000-01-01', DATE '9999-12-31');
