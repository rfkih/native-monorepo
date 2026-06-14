-- Phase-3 PAYROLL ENGINE tables (#23) inside employee-service.
--
-- Every table extends Auditable (the six audit/tenancy columns) and is under RLS:
-- ENABLE + FORCE ROW LEVEL SECURITY with a tenant-isolation policy keyed to
-- current_setting('app.current_tenant', true) — identical to V1 (rule 4 + rule 5). The app
-- connects as a non-superuser role (no BYPASSRLS), so the policy genuinely engages; FORCE makes it
-- bind even the owning role; an unset GUC -> NULL -> false predicate -> fail closed.
--
-- PII (rule 6 — salary/compensation): base pay and individual payslip-line amounts are stored as
-- AES-256-GCM CIPHERTEXT in TEXT columns (base_pay_enc, amount_enc, calc_basis_enc,
-- fixed_amount_enc) via MoneyPiiConverter — NEVER plaintext amount/currency columns, NEVER
-- queryable/sortable by value (intentional). labor_cost_allocation amounts are NON-PII outlet
-- cost-center aggregates, so they use the plain two-column Money shape.
--
-- Money is integer minor units + an ISO-4217 currency code (rule 8) — never a float. Statutory
-- FIGURES are DATA in statutory_rule.params_json (jsonb); Java holds ZERO statutory numbers (HR-9).

-- ---------------------------------------------------------------------------
-- comp_package (compensation package — base pay PII-encrypted, effective-dated)
-- ---------------------------------------------------------------------------
CREATE TABLE comp_package (
    id                     UUID         NOT NULL PRIMARY KEY,
    employee_id            UUID         NOT NULL,
    employment_contract_id UUID         NOT NULL,

    -- PII (rule 6): base pay as AES-256-GCM ciphertext, NEVER plaintext minor/currency columns.
    base_pay_enc           TEXT         NOT NULL,

    pay_frequency          VARCHAR(16)  NOT NULL,
    effective_from         DATE         NOT NULL,
    effective_to           DATE         NOT NULL DEFAULT DATE '9999-12-31',

    created_at             TIMESTAMPTZ  NOT NULL,
    created_by             VARCHAR(255) NOT NULL,
    updated_at             TIMESTAMPTZ  NOT NULL,
    updated_by             VARCHAR(255) NOT NULL,
    version                BIGINT       NOT NULL,
    company_id             VARCHAR(64)  NOT NULL
);

ALTER TABLE comp_package ENABLE ROW LEVEL SECURITY;
ALTER TABLE comp_package FORCE ROW LEVEL SECURITY;
CREATE POLICY comp_package_tenant_isolation ON comp_package
    USING (company_id = current_setting('app.current_tenant', true))
    WITH CHECK (company_id = current_setting('app.current_tenant', true));
CREATE INDEX idx_comp_package_employee ON comp_package (employee_id);

-- ---------------------------------------------------------------------------
-- pay_component (catalog of an earning/deduction — pure config, NO amounts)
-- ---------------------------------------------------------------------------
CREATE TABLE pay_component (
    id                 UUID         NOT NULL PRIMARY KEY,
    component_key      VARCHAR(64)  NOT NULL,
    kind               VARCHAR(16)  NOT NULL,
    calc_type          VARCHAR(32)  NOT NULL,
    bearer             VARCHAR(16)  NOT NULL,
    gl_account         VARCHAR(64)  NOT NULL,
    taxable            BOOLEAN      NOT NULL,
    statutory_rule_key VARCHAR(64)  NULL,
    display_order      INTEGER      NOT NULL,
    active             BOOLEAN      NOT NULL,

    created_at         TIMESTAMPTZ  NOT NULL,
    created_by         VARCHAR(255) NOT NULL,
    updated_at         TIMESTAMPTZ  NOT NULL,
    updated_by         VARCHAR(255) NOT NULL,
    version            BIGINT       NOT NULL,
    company_id         VARCHAR(64)  NOT NULL
);

ALTER TABLE pay_component ENABLE ROW LEVEL SECURITY;
ALTER TABLE pay_component FORCE ROW LEVEL SECURITY;
CREATE POLICY pay_component_tenant_isolation ON pay_component
    USING (company_id = current_setting('app.current_tenant', true))
    WITH CHECK (company_id = current_setting('app.current_tenant', true));
CREATE INDEX idx_pay_component_key ON pay_component (component_key);

-- ---------------------------------------------------------------------------
-- earning_rule (binds a component to a comp package; FIXED amount PII-encrypted)
-- ---------------------------------------------------------------------------
CREATE TABLE earning_rule (
    id                      UUID         NOT NULL PRIMARY KEY,
    compensation_package_id UUID         NOT NULL,
    pay_component_id        UUID         NOT NULL,
    param_kind              VARCHAR(32)  NOT NULL,

    -- PII (rule 6): the fixed Money amount as ciphertext when param_kind = FIXED_AMOUNT.
    fixed_amount_enc        TEXT         NULL,
    -- non-PII config rates:
    percent_basis_points    INTEGER      NULL,
    metric_key              VARCHAR(64)  NULL,
    rate_minor              BIGINT       NULL,
    rate_currency           VARCHAR(3)   NULL,

    effective_from          DATE         NOT NULL,
    effective_to            DATE         NOT NULL DEFAULT DATE '9999-12-31',

    created_at              TIMESTAMPTZ  NOT NULL,
    created_by              VARCHAR(255) NOT NULL,
    updated_at              TIMESTAMPTZ  NOT NULL,
    updated_by              VARCHAR(255) NOT NULL,
    version                 BIGINT       NOT NULL,
    company_id              VARCHAR(64)  NOT NULL
);

ALTER TABLE earning_rule ENABLE ROW LEVEL SECURITY;
ALTER TABLE earning_rule FORCE ROW LEVEL SECURITY;
CREATE POLICY earning_rule_tenant_isolation ON earning_rule
    USING (company_id = current_setting('app.current_tenant', true))
    WITH CHECK (company_id = current_setting('app.current_tenant', true));
CREATE INDEX idx_earning_rule_comp_package ON earning_rule (compensation_package_id);

-- ---------------------------------------------------------------------------
-- statutory_rule (VERSIONED + EFFECTIVE-DATED rule DATA; provenance-flagged)
-- ---------------------------------------------------------------------------
-- params_json holds brackets/rates/ceilings/PTKP as DATA (HR-9). provenance is NOT NULL with a
-- CHECK constraint and no default, so a new row must DECLARE whether it is an illustrative
-- placeholder or an official figure — a placeholder can never be silently treated as official.
CREATE TABLE statutory_rule (
    id             UUID         NOT NULL PRIMARY KEY,
    rule_key       VARCHAR(64)  NOT NULL,
    rule_version   VARCHAR(64)  NOT NULL,
    calc_type      VARCHAR(32)  NOT NULL,
    params_json    JSONB        NOT NULL,
    currency       CHAR(3)      NOT NULL,
    provenance     VARCHAR(32)  NOT NULL,
    source_note    TEXT         NOT NULL,
    effective_from DATE         NOT NULL,
    effective_to   DATE         NOT NULL DEFAULT DATE '9999-12-31',
    active         BOOLEAN      NOT NULL,

    created_at     TIMESTAMPTZ  NOT NULL,
    created_by     VARCHAR(255) NOT NULL,
    updated_at     TIMESTAMPTZ  NOT NULL,
    updated_by     VARCHAR(255) NOT NULL,
    version        BIGINT       NOT NULL,
    company_id     VARCHAR(64)  NOT NULL,

    CONSTRAINT chk_statutory_rule_provenance
        CHECK (provenance IN ('ILLUSTRATIVE_PLACEHOLDER', 'OFFICIAL'))
);

ALTER TABLE statutory_rule ENABLE ROW LEVEL SECURITY;
ALTER TABLE statutory_rule FORCE ROW LEVEL SECURITY;
CREATE POLICY statutory_rule_tenant_isolation ON statutory_rule
    USING (company_id = current_setting('app.current_tenant', true))
    WITH CHECK (company_id = current_setting('app.current_tenant', true));
CREATE INDEX idx_statutory_rule_key_effective ON statutory_rule (rule_key, effective_from, effective_to);

-- ---------------------------------------------------------------------------
-- payroll_run (one per (company_id, period, run_seq); company-level totals only)
-- ---------------------------------------------------------------------------
CREATE TABLE payroll_run (
    id                                 UUID         NOT NULL PRIMARY KEY,
    period                             VARCHAR(7)   NOT NULL,
    run_seq                            INTEGER      NOT NULL,
    status                             VARCHAR(32)  NOT NULL,
    rule_version_set_json              JSONB        NOT NULL,
    uses_illustrative_rules            BOOLEAN      NOT NULL,

    -- Company-level TOTALS (NOT PII) as Money two-column pairs (rule 8).
    gross_total_minor                  BIGINT       NOT NULL,
    gross_total_currency               CHAR(3)      NOT NULL,
    employee_deduction_total_minor     BIGINT       NOT NULL,
    employee_deduction_total_currency  CHAR(3)      NOT NULL,
    employer_contribution_total_minor  BIGINT       NOT NULL,
    employer_contribution_total_currency CHAR(3)    NOT NULL,
    net_total_minor                    BIGINT       NOT NULL,
    net_total_currency                 CHAR(3)      NOT NULL,

    base_currency                      CHAR(3)      NOT NULL,
    sealed_sources_json                JSONB        NOT NULL,
    posted_at                          TIMESTAMPTZ  NULL,

    created_at                         TIMESTAMPTZ  NOT NULL,
    created_by                         VARCHAR(255) NOT NULL,
    updated_at                         TIMESTAMPTZ  NOT NULL,
    updated_by                         VARCHAR(255) NOT NULL,
    version                            BIGINT       NOT NULL,
    company_id                         VARCHAR(64)  NOT NULL,

    -- A re-run of a period is a NEW run_seq; this UNIQUE prevents a duplicate run / double post.
    CONSTRAINT uq_payroll_run_company_period_seq UNIQUE (company_id, period, run_seq)
);

ALTER TABLE payroll_run ENABLE ROW LEVEL SECURITY;
ALTER TABLE payroll_run FORCE ROW LEVEL SECURITY;
CREATE POLICY payroll_run_tenant_isolation ON payroll_run
    USING (company_id = current_setting('app.current_tenant', true))
    WITH CHECK (company_id = current_setting('app.current_tenant', true));
CREATE INDEX idx_payroll_run_period ON payroll_run (period, run_seq);

-- ---------------------------------------------------------------------------
-- payslip_line (per run/employee/component — amounts PII-encrypted; rule_version stamped)
-- ---------------------------------------------------------------------------
CREATE TABLE payslip_line (
    id              UUID         NOT NULL PRIMARY KEY,
    payroll_run_id  UUID         NOT NULL,
    employee_id     UUID         NOT NULL,
    pay_component_id UUID        NOT NULL,
    component_key   VARCHAR(64)  NOT NULL,
    kind            VARCHAR(16)  NOT NULL,
    bearer          VARCHAR(16)  NOT NULL,
    gl_account      VARCHAR(64)  NOT NULL,

    -- PII (rule 6): individual salary line amounts as ciphertext, NEVER plaintext.
    amount_enc      TEXT         NOT NULL,
    calc_basis_enc  TEXT         NULL,

    rule_version    VARCHAR(64)  NULL,
    is_illustrative BOOLEAN      NOT NULL,

    created_at      TIMESTAMPTZ  NOT NULL,
    created_by      VARCHAR(255) NOT NULL,
    updated_at      TIMESTAMPTZ  NOT NULL,
    updated_by      VARCHAR(255) NOT NULL,
    version         BIGINT       NOT NULL,
    company_id      VARCHAR(64)  NOT NULL
);

ALTER TABLE payslip_line ENABLE ROW LEVEL SECURITY;
ALTER TABLE payslip_line FORCE ROW LEVEL SECURITY;
CREATE POLICY payslip_line_tenant_isolation ON payslip_line
    USING (company_id = current_setting('app.current_tenant', true))
    WITH CHECK (company_id = current_setting('app.current_tenant', true));
CREATE INDEX idx_payslip_line_run ON payslip_line (payroll_run_id);
CREATE INDEX idx_payslip_line_run_employee ON payslip_line (payroll_run_id, employee_id);

-- ---------------------------------------------------------------------------
-- labor_cost_allocation (per run/employee/outlet/gl — NON-PII cost-center aggregate)
-- ---------------------------------------------------------------------------
CREATE TABLE labor_cost_allocation (
    id                 UUID         NOT NULL PRIMARY KEY,
    payroll_run_id     UUID         NOT NULL,
    employee_id        UUID         NOT NULL,
    outlet_org_unit_id UUID         NOT NULL,
    legal_employer_id  UUID         NOT NULL,
    gl_account         VARCHAR(64)  NOT NULL,

    -- NON-PII outlet cost bucket -> plain two-column Money (queryable), feeds LaborCostAllocated.
    amount_minor       BIGINT       NOT NULL,
    amount_currency    CHAR(3)      NOT NULL,
    earnings_share_bp  INTEGER      NOT NULL,

    created_at         TIMESTAMPTZ  NOT NULL,
    created_by         VARCHAR(255) NOT NULL,
    updated_at         TIMESTAMPTZ  NOT NULL,
    updated_by         VARCHAR(255) NOT NULL,
    version            BIGINT       NOT NULL,
    company_id         VARCHAR(64)  NOT NULL
);

ALTER TABLE labor_cost_allocation ENABLE ROW LEVEL SECURITY;
ALTER TABLE labor_cost_allocation FORCE ROW LEVEL SECURITY;
CREATE POLICY labor_cost_allocation_tenant_isolation ON labor_cost_allocation
    USING (company_id = current_setting('app.current_tenant', true))
    WITH CHECK (company_id = current_setting('app.current_tenant', true));
CREATE INDEX idx_labor_cost_allocation_run ON labor_cost_allocation (payroll_run_id);

-- ---------------------------------------------------------------------------
-- metric_input (projection of consumed MetricPublished — variable pay inputs)
-- ---------------------------------------------------------------------------
CREATE TABLE metric_input (
    id           UUID         NOT NULL PRIMARY KEY,
    metric_key   VARCHAR(64)  NOT NULL,
    period       VARCHAR(16)  NOT NULL,
    grain        VARCHAR(16)  NOT NULL,
    subject_id   UUID         NOT NULL,
    metric_value BIGINT       NOT NULL,

    created_at   TIMESTAMPTZ  NOT NULL,
    created_by   VARCHAR(255) NOT NULL,
    updated_at   TIMESTAMPTZ  NOT NULL,
    updated_by   VARCHAR(255) NOT NULL,
    version      BIGINT       NOT NULL,
    company_id   VARCHAR(64)  NOT NULL,

    CONSTRAINT uq_metric_input_natural_key UNIQUE (company_id, metric_key, period, grain, subject_id)
);

ALTER TABLE metric_input ENABLE ROW LEVEL SECURITY;
ALTER TABLE metric_input FORCE ROW LEVEL SECURITY;
CREATE POLICY metric_input_tenant_isolation ON metric_input
    USING (company_id = current_setting('app.current_tenant', true))
    WITH CHECK (company_id = current_setting('app.current_tenant', true));
CREATE INDEX idx_metric_input_lookup ON metric_input (metric_key, period, subject_id);

-- ---------------------------------------------------------------------------
-- period_seal (projection of consumed PeriodSealed — the completeness gate reads this)
-- ---------------------------------------------------------------------------
CREATE TABLE period_seal (
    id          UUID         NOT NULL PRIMARY KEY,
    business_id UUID         NOT NULL,
    period      VARCHAR(16)  NOT NULL,
    sealed_at   TIMESTAMPTZ  NOT NULL,

    created_at  TIMESTAMPTZ  NOT NULL,
    created_by  VARCHAR(255) NOT NULL,
    updated_at  TIMESTAMPTZ  NOT NULL,
    updated_by  VARCHAR(255) NOT NULL,
    version     BIGINT       NOT NULL,
    company_id  VARCHAR(64)  NOT NULL,

    CONSTRAINT uq_period_seal_natural_key UNIQUE (company_id, business_id, period)
);

ALTER TABLE period_seal ENABLE ROW LEVEL SECURITY;
ALTER TABLE period_seal FORCE ROW LEVEL SECURITY;
CREATE POLICY period_seal_tenant_isolation ON period_seal
    USING (company_id = current_setting('app.current_tenant', true))
    WITH CHECK (company_id = current_setting('app.current_tenant', true));
CREATE INDEX idx_period_seal_period ON period_seal (period);
