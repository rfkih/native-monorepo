-- Phase E1 EXPENSE CLAIMS (ADR 0030) — the employee-service core: category catalog, the claim
-- aggregate, and an append-only per-transition audit/idempotency log.
--
-- Every table extends Auditable (the six audit/tenancy columns) and is under RLS:
-- ENABLE + FORCE ROW LEVEL SECURITY with a tenant-isolation policy keyed to
-- current_setting('app.current_tenant', true) — identical to V1/V2 (rule 4 + rule 5). The app
-- connects as a non-superuser role (no BYPASSRLS), so the policy genuinely engages; FORCE makes it
-- bind even the owning role; an unset GUC -> NULL -> false predicate -> fail closed.
--
-- Money (rule 8): expense_claim.amount_minor/amount_currency are PLAIN columns (BIGINT + CHAR(3)),
-- deliberately NOT PII-encrypted (ADR 0030 §rationale) — a claim amount is manager-visible
-- operational data, unlike an individual payslip line. Never a float.
--
-- expense_claim_event is the replay-detection + audit log: every guarded state transition
-- (submit/cancel/approve/refuse, and later void/pay) appends exactly one row keyed by
-- (company_id, claim_id, idempotency_key); the UNIQUE index is BOTH the audit trail and the
-- concurrency backstop a retried/racing request recovers against (ADR 0030 §5, the AP-Bill idiom).

-- ---------------------------------------------------------------------------
-- expense_category (admin catalog — gl_hint drives finance's mapping_rule resolution)
-- ---------------------------------------------------------------------------
CREATE TABLE expense_category (
    id          UUID         NOT NULL PRIMARY KEY,
    name        VARCHAR(128) NOT NULL,
    gl_hint     VARCHAR(64)  NOT NULL DEFAULT '',
    taxable     BOOLEAN      NOT NULL DEFAULT FALSE,
    active      BOOLEAN      NOT NULL DEFAULT TRUE,

    created_at  TIMESTAMPTZ  NOT NULL,
    created_by  VARCHAR(255) NOT NULL,
    updated_at  TIMESTAMPTZ  NOT NULL,
    updated_by  VARCHAR(255) NOT NULL,
    version     BIGINT       NOT NULL,
    company_id  VARCHAR(64)  NOT NULL
);

ALTER TABLE expense_category ENABLE ROW LEVEL SECURITY;
ALTER TABLE expense_category FORCE ROW LEVEL SECURITY;
CREATE POLICY expense_category_tenant_isolation ON expense_category
    USING (company_id = current_setting('app.current_tenant', true))
    WITH CHECK (company_id = current_setting('app.current_tenant', true));
CREATE UNIQUE INDEX uq_expense_category_name ON expense_category (company_id, lower(name));

-- ---------------------------------------------------------------------------
-- expense_claim (the aggregate — DRAFT -> SUBMITTED -> APPROVED|REFUSED; cancel from DRAFT/SUBMITTED)
-- ---------------------------------------------------------------------------
CREATE TABLE expense_claim (
    id                    UUID         NOT NULL PRIMARY KEY,
    employee_id           UUID         NOT NULL,
    category_id           UUID         NOT NULL,
    org_unit_id           UUID         NOT NULL,
    status                VARCHAR(16)  NOT NULL,

    -- Money (rule 8): plain columns, NOT PII-encrypted (ADR 0030 — manager-visible operational data).
    amount_minor          BIGINT       NOT NULL CHECK (amount_minor > 0),
    amount_currency       CHAR(3)      NOT NULL,

    expense_date          DATE         NOT NULL,
    merchant              VARCHAR(160) NULL,
    note                  TEXT         NULL,

    reimbursement_method  VARCHAR(16)  NOT NULL DEFAULT 'PAYROLL'
        CHECK (reimbursement_method IN ('PAYROLL', 'DIRECT')),
    reimbursement_run_id  UUID         NULL,
    settled_at            TIMESTAMPTZ  NULL,

    approved_at           TIMESTAMPTZ  NULL,
    decided_by            VARCHAR(255) NULL,
    decided_at            TIMESTAMPTZ  NULL,
    decision_comment      TEXT         NULL,

    created_at            TIMESTAMPTZ  NOT NULL,
    created_by            VARCHAR(255) NOT NULL,
    updated_at            TIMESTAMPTZ  NOT NULL,
    updated_by            VARCHAR(255) NOT NULL,
    version               BIGINT       NOT NULL,
    company_id            VARCHAR(64)  NOT NULL,

    CONSTRAINT chk_expense_claim_status
        CHECK (status IN ('DRAFT', 'SUBMITTED', 'APPROVED', 'REFUSED', 'CANCELLED', 'VOIDED', 'REIMBURSED'))
);

ALTER TABLE expense_claim ENABLE ROW LEVEL SECURITY;
ALTER TABLE expense_claim FORCE ROW LEVEL SECURITY;
CREATE POLICY expense_claim_tenant_isolation ON expense_claim
    USING (company_id = current_setting('app.current_tenant', true))
    WITH CHECK (company_id = current_setting('app.current_tenant', true));
CREATE INDEX idx_expense_claim_company_status ON expense_claim (company_id, status);
CREATE INDEX idx_expense_claim_employee_status ON expense_claim (employee_id, status);
CREATE INDEX idx_expense_claim_org_unit ON expense_claim (org_unit_id);
CREATE INDEX idx_expense_claim_reimbursement_run ON expense_claim (reimbursement_run_id)
    WHERE reimbursement_run_id IS NOT NULL;

-- ---------------------------------------------------------------------------
-- expense_claim_event (append-only per-transition audit row + idempotency-key backstop)
-- ---------------------------------------------------------------------------
CREATE TABLE expense_claim_event (
    id               UUID         NOT NULL PRIMARY KEY,
    claim_id         UUID         NOT NULL,
    action           VARCHAR(32)  NOT NULL,
    from_status      VARCHAR(16)  NOT NULL,
    to_status        VARCHAR(16)  NOT NULL,
    actor            VARCHAR(255) NOT NULL,
    comment          TEXT         NULL,
    idempotency_key  VARCHAR(80)  NOT NULL,

    created_at       TIMESTAMPTZ  NOT NULL,
    created_by       VARCHAR(255) NOT NULL,
    updated_at       TIMESTAMPTZ  NOT NULL,
    updated_by       VARCHAR(255) NOT NULL,
    version          BIGINT       NOT NULL,
    company_id       VARCHAR(64)  NOT NULL
);

ALTER TABLE expense_claim_event ENABLE ROW LEVEL SECURITY;
ALTER TABLE expense_claim_event FORCE ROW LEVEL SECURITY;
CREATE POLICY expense_claim_event_tenant_isolation ON expense_claim_event
    USING (company_id = current_setting('app.current_tenant', true))
    WITH CHECK (company_id = current_setting('app.current_tenant', true));
CREATE UNIQUE INDEX uq_expense_claim_event_idem ON expense_claim_event (company_id, claim_id, idempotency_key);
CREATE INDEX idx_expense_claim_event_claim ON expense_claim_event (claim_id);
