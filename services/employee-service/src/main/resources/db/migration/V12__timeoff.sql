-- Phase P6 ATTENDANCE & TIME-OFF FOUNDATION (ADR 0033) — the standalone-usable employee-service
-- core for leave requests, overtime entries, derived leave balances, and the per-tenant work
-- calendar. Track P Phase P7 (not this migration) teaches the payroll run to CONSUME these rows;
-- P6 only records and approves them.
--
-- Every table extends Auditable (the six audit/tenancy columns) and is under RLS: ENABLE + FORCE
-- ROW LEVEL SECURITY with a tenant-isolation policy keyed to current_setting('app.current_tenant',
-- true) — identical to V1/V2/V9 (rule 4 + rule 5). The app connects as a non-superuser role (no
-- BYPASSRLS), so the policy genuinely engages; FORCE makes it bind even the owning role; an unset
-- GUC -> NULL -> false predicate -> fail closed.
--
-- No Money on any of these tables (rule 8 is n/a here — days and minutes are plain integers, not
-- monetary amounts; P7 is what turns a day/minute count into a Money-typed payslip line).

-- ---------------------------------------------------------------------------
-- leave_request (ANNUAL | UNPAID | SICK — SUBMITTED -> APPROVED | REJECTED; cancel from SUBMITTED)
-- ---------------------------------------------------------------------------
CREATE TABLE leave_request (
    id                UUID         NOT NULL PRIMARY KEY,
    employee_id       UUID         NOT NULL,
    leave_type        VARCHAR(16)  NOT NULL,
    start_date        DATE         NOT NULL,
    end_date          DATE         NOT NULL,
    days              INT          NOT NULL CHECK (days > 0),
    status            VARCHAR(16)  NOT NULL,

    decided_by        VARCHAR(255) NULL,
    decided_at        TIMESTAMPTZ  NULL,
    decision_note     TEXT         NULL,

    -- The client's Idempotency-Key on CREATE (there is no DRAFT stage — create IS the SUBMIT
    -- transition, ADR 0033 §3): a retried POST replays the ORIGINAL request rather than creating a
    -- second one. Later transitions (approve/reject/cancel) instead replay via the shared
    -- timeoff_request_event table (they act on an EXISTING, already-known request id) — the
    -- fixed_asset "acquire" idiom (V34) applied to a create-with-no-prior-id resource.
    idempotency_key   VARCHAR(80)  NOT NULL,

    created_at        TIMESTAMPTZ  NOT NULL,
    created_by        VARCHAR(255) NOT NULL,
    updated_at        TIMESTAMPTZ  NOT NULL,
    updated_by        VARCHAR(255) NOT NULL,
    version           BIGINT       NOT NULL,
    company_id        VARCHAR(64)  NOT NULL,

    CONSTRAINT chk_leave_request_leave_type CHECK (leave_type IN ('ANNUAL', 'UNPAID', 'SICK')),
    CONSTRAINT chk_leave_request_status
        CHECK (status IN ('SUBMITTED', 'APPROVED', 'REJECTED', 'CANCELLED')),
    CONSTRAINT chk_leave_request_date_range CHECK (end_date >= start_date)
);

ALTER TABLE leave_request ENABLE ROW LEVEL SECURITY;
ALTER TABLE leave_request FORCE ROW LEVEL SECURITY;
CREATE POLICY leave_request_tenant_isolation ON leave_request
    USING (company_id = current_setting('app.current_tenant', true))
    WITH CHECK (company_id = current_setting('app.current_tenant', true));
CREATE INDEX idx_leave_request_employee_start ON leave_request (employee_id, start_date);
CREATE INDEX idx_leave_request_company_status ON leave_request (company_id, status);
CREATE UNIQUE INDEX uq_leave_request_idem ON leave_request (company_id, idempotency_key);

-- ---------------------------------------------------------------------------
-- overtime_entry (WEEKDAY | REST_DAY — same SUBMITTED -> APPROVED | REJECTED machine)
-- ---------------------------------------------------------------------------
CREATE TABLE overtime_entry (
    id                UUID         NOT NULL PRIMARY KEY,
    employee_id       UUID         NOT NULL,
    work_date         DATE         NOT NULL,
    minutes           INT          NOT NULL CHECK (minutes > 0 AND minutes <= 600),
    day_kind          VARCHAR(16)  NOT NULL,
    status            VARCHAR(16)  NOT NULL,

    decided_by        VARCHAR(255) NULL,
    decided_at        TIMESTAMPTZ  NULL,
    decision_note     TEXT         NULL,

    -- Same create-is-submit idempotency idiom as leave_request.idempotency_key above.
    idempotency_key   VARCHAR(80)  NOT NULL,

    created_at        TIMESTAMPTZ  NOT NULL,
    created_by        VARCHAR(255) NOT NULL,
    updated_at        TIMESTAMPTZ  NOT NULL,
    updated_by        VARCHAR(255) NOT NULL,
    version           BIGINT       NOT NULL,
    company_id        VARCHAR(64)  NOT NULL,

    CONSTRAINT chk_overtime_entry_day_kind CHECK (day_kind IN ('WEEKDAY', 'REST_DAY')),
    CONSTRAINT chk_overtime_entry_status
        CHECK (status IN ('SUBMITTED', 'APPROVED', 'REJECTED', 'CANCELLED'))
);

ALTER TABLE overtime_entry ENABLE ROW LEVEL SECURITY;
ALTER TABLE overtime_entry FORCE ROW LEVEL SECURITY;
CREATE POLICY overtime_entry_tenant_isolation ON overtime_entry
    USING (company_id = current_setting('app.current_tenant', true))
    WITH CHECK (company_id = current_setting('app.current_tenant', true));
CREATE INDEX idx_overtime_entry_employee_work_date ON overtime_entry (employee_id, work_date);
CREATE INDEX idx_overtime_entry_company_status ON overtime_entry (company_id, status);
CREATE UNIQUE INDEX uq_overtime_entry_idem ON overtime_entry (company_id, idempotency_key);

-- ---------------------------------------------------------------------------
-- leave_balance (per employee x year; the derived-usage READ subtracts APPROVED ANNUAL request
-- days from granted_days + adjustment_days — this table never stores "days used", only the grant
-- and any manager correction, ADR 0033)
-- ---------------------------------------------------------------------------
CREATE TABLE leave_balance (
    id                UUID         NOT NULL PRIMARY KEY,
    employee_id       UUID         NOT NULL,
    year              INT          NOT NULL,
    granted_days      INT          NOT NULL DEFAULT 12,
    adjustment_days   INT          NOT NULL DEFAULT 0,

    created_at        TIMESTAMPTZ  NOT NULL,
    created_by        VARCHAR(255) NOT NULL,
    updated_at        TIMESTAMPTZ  NOT NULL,
    updated_by        VARCHAR(255) NOT NULL,
    version           BIGINT       NOT NULL,
    company_id        VARCHAR(64)  NOT NULL
);

ALTER TABLE leave_balance ENABLE ROW LEVEL SECURITY;
ALTER TABLE leave_balance FORCE ROW LEVEL SECURITY;
CREATE POLICY leave_balance_tenant_isolation ON leave_balance
    USING (company_id = current_setting('app.current_tenant', true))
    WITH CHECK (company_id = current_setting('app.current_tenant', true));
CREATE UNIQUE INDEX uq_leave_balance_employee_year ON leave_balance (company_id, employee_id, year);

-- ---------------------------------------------------------------------------
-- work_calendar (one row per tenant — days_per_week 5|6, monthly_divisor 21|25 per the PP 36/2021
-- daily-wage convention; seeded on first read with the default (5, 21) rather than by Flyway —
-- RLS forbids a Flyway seed, the expense_category default-set precedent)
-- ---------------------------------------------------------------------------
CREATE TABLE work_calendar (
    id                UUID         NOT NULL PRIMARY KEY,
    days_per_week     INT          NOT NULL,
    monthly_divisor   INT          NOT NULL,

    created_at        TIMESTAMPTZ  NOT NULL,
    created_by        VARCHAR(255) NOT NULL,
    updated_at        TIMESTAMPTZ  NOT NULL,
    updated_by        VARCHAR(255) NOT NULL,
    version           BIGINT       NOT NULL,
    company_id        VARCHAR(64)  NOT NULL,

    CONSTRAINT chk_work_calendar_days_per_week CHECK (days_per_week IN (5, 6)),
    CONSTRAINT chk_work_calendar_monthly_divisor CHECK (monthly_divisor IN (21, 25))
);

ALTER TABLE work_calendar ENABLE ROW LEVEL SECURITY;
ALTER TABLE work_calendar FORCE ROW LEVEL SECURITY;
CREATE POLICY work_calendar_tenant_isolation ON work_calendar
    USING (company_id = current_setting('app.current_tenant', true))
    WITH CHECK (company_id = current_setting('app.current_tenant', true));
CREATE UNIQUE INDEX uq_work_calendar_company ON work_calendar (company_id);

-- ---------------------------------------------------------------------------
-- timeoff_request_event — ONE shared append-only transition-audit + idempotency-replay log for
-- BOTH leave_request and overtime_entry (request_kind discriminates), mirroring the
-- expense_claim_event pattern (V9). Shared rather than split in two: both aggregates guard the
-- IDENTICAL transition shape (a create-as-SUBMITTED, APPROVE, REJECT, CANCEL — same actor/comment/
-- idempotency-key columns), so one table halves the schema/repository/service boilerplate with no
-- loss of audit fidelity; request_kind keeps a LEAVE row and an OVERTIME row from ever being
-- confused even though request_id values are drawn from separate UUID spaces (ADR 0033 §5).
-- ---------------------------------------------------------------------------
CREATE TABLE timeoff_request_event (
    id               UUID         NOT NULL PRIMARY KEY,
    request_kind     VARCHAR(16)  NOT NULL,
    request_id       UUID         NOT NULL,
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
    company_id       VARCHAR(64)  NOT NULL,

    CONSTRAINT chk_timeoff_request_event_kind CHECK (request_kind IN ('LEAVE', 'OVERTIME'))
);

ALTER TABLE timeoff_request_event ENABLE ROW LEVEL SECURITY;
ALTER TABLE timeoff_request_event FORCE ROW LEVEL SECURITY;
CREATE POLICY timeoff_request_event_tenant_isolation ON timeoff_request_event
    USING (company_id = current_setting('app.current_tenant', true))
    WITH CHECK (company_id = current_setting('app.current_tenant', true));
CREATE UNIQUE INDEX uq_timeoff_request_event_idem
    ON timeoff_request_event (company_id, request_kind, request_id, idempotency_key);
CREATE INDEX idx_timeoff_request_event_request ON timeoff_request_event (request_kind, request_id);
