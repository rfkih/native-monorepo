-- employee-service baseline (#19) — the HR module's canonical employee, RECORDS ONLY
-- (no payroll/compensation yet).
--
-- Tables:
--   * employee             — the canonical person: full_name, ptkp_status, status
--                            (ACTIVE|INACTIVE), and the COLUMN-ENCRYPTED PII nik +
--                            bank_account (rule 6 — AES-256-GCM ciphertext at rest, so
--                            these are TEXT holding base64(IV||ciphertext+tag), NEVER the
--                            plaintext). Org/team/manager are deliberately NOT here — they
--                            live on the assignment (ARCHITECTURE.md §2).
--   * employment_contract  — employment_type (PERMANENT|CONTRACT|...), legal_employer_id,
--                            and effective_from/effective_to (open-ended uses the
--                            far-future 9999-12-31 sentinel, never NULL — §2.5). The
--                            employee's legal employer(s) come from their contract(s).
--   * assignment           — org_unit_id, reporting_to (nullable employee_id manager),
--                            role, effective-dated. An employee holds multiple CONCURRENT
--                            assignments; all concurrent assignments MUST resolve to the
--                            SAME legal_employer (enforced in the service against the local
--                            org read model below).
--   * org_unit_projection  — the LOCAL org read model: org_unit_id -> {company_id,
--                            legal_employer_id, type, active}. An app-maintained projection
--                            updated by the OrgUnitCreated/OrgUnitChanged consumer; the
--                            same-legal-employer invariant is checked against it. Like
--                            finance's consolidated_revenue read model it is Auditable + RLS
--                            (an app-maintained projection, NOT a Debezium-CDC-derived one).
--   * outbox               — the transactional-outbox table the libs/events OutboxWriter
--                            writes to; the EmployeeChanged / AssignmentChanged rows commit
--                            atomically with the aggregate change (rule 3).
--   * processed_event      — the idempotent-consumer dedupe store (libs/events
--                            ProcessedEventStore): one row per handled org-event UUID.
--
-- The app connects as a NON-superuser role (without BYPASSRLS), so RLS is genuinely
-- enforced; FORCE ROW LEVEL SECURITY makes the policy apply even to the table owner /
-- migration role, so no connection silently bypasses tenant isolation (rule 5).
-- current_setting('app.current_tenant', true) returns NULL when the GUC is unset, so with
-- no tenant bound every policy predicate is false -> fail closed.

-- ---------------------------------------------------------------------------
-- employee (the canonical person — PII column-encrypted)
-- ---------------------------------------------------------------------------
CREATE TABLE employee (
    id            UUID         NOT NULL PRIMARY KEY,
    full_name     VARCHAR(255) NOT NULL,

    -- Indonesian tax marital/dependant status (PTKP). Stored as text; the PtkpStatus
    -- enum is the source of truth (mapped EnumType.STRING). Not PII.
    ptkp_status   VARCHAR(16)  NOT NULL,

    -- PII (rule 6) — stored as AES-256-GCM CIPHERTEXT, never plaintext. The JPA
    -- PiiAttributeConverter encrypts on write / decrypts on read; the column holds
    -- base64(IV(12) || ciphertext+GCMtag(16)), which is wider than the plaintext, hence
    -- TEXT. A random IV per value means the same NIK encrypts to different ciphertext, so
    -- the column never leaks value equality. These are NEVER logged and never in an event.
    nik           TEXT         NOT NULL,
    bank_account  TEXT         NOT NULL,

    -- Employment status: ACTIVE | INACTIVE. EmployeeStatus enum (EnumType.STRING).
    status        VARCHAR(16)  NOT NULL,

    -- Auditable (libs/tenant) — present on every Native table.
    created_at    TIMESTAMPTZ  NOT NULL,
    created_by    VARCHAR(255) NOT NULL,
    updated_at    TIMESTAMPTZ  NOT NULL,
    updated_by    VARCHAR(255) NOT NULL,
    version       BIGINT       NOT NULL,
    company_id    VARCHAR(64)  NOT NULL
);

ALTER TABLE employee ENABLE ROW LEVEL SECURITY;
ALTER TABLE employee FORCE ROW LEVEL SECURITY;

CREATE POLICY employee_tenant_isolation ON employee
    USING (company_id = current_setting('app.current_tenant', true))
    WITH CHECK (company_id = current_setting('app.current_tenant', true));

-- ---------------------------------------------------------------------------
-- employment_contract (employment_type + legal_employer + effective dates)
-- ---------------------------------------------------------------------------
CREATE TABLE employment_contract (
    id                UUID         NOT NULL PRIMARY KEY,
    employee_id       UUID         NOT NULL,

    -- PERMANENT | CONTRACT | ... — EmploymentType enum (EnumType.STRING).
    employment_type   VARCHAR(32)  NOT NULL,

    -- The legal employer this contract is under. The employee's legal employer(s) are the
    -- distinct legal_employer_id(s) across their contracts; concurrent assignments must all
    -- resolve to one of them (and to a single one — the same-legal-employer invariant).
    legal_employer_id UUID         NOT NULL,

    -- Effective-dated: open-ended effective_to uses the far-future 9999-12-31 sentinel,
    -- never NULL (keeps range predicates index-friendly — ENGINEERING-STANDARDS §2.5).
    effective_from    DATE         NOT NULL,
    effective_to      DATE         NOT NULL DEFAULT DATE '9999-12-31',

    -- Auditable (libs/tenant) — present on every Native table.
    created_at        TIMESTAMPTZ  NOT NULL,
    created_by        VARCHAR(255) NOT NULL,
    updated_at        TIMESTAMPTZ  NOT NULL,
    updated_by        VARCHAR(255) NOT NULL,
    version           BIGINT       NOT NULL,
    company_id        VARCHAR(64)  NOT NULL
);

ALTER TABLE employment_contract ENABLE ROW LEVEL SECURITY;
ALTER TABLE employment_contract FORCE ROW LEVEL SECURITY;

CREATE POLICY employment_contract_tenant_isolation ON employment_contract
    USING (company_id = current_setting('app.current_tenant', true))
    WITH CHECK (company_id = current_setting('app.current_tenant', true));

-- Contracts are read by employee (to resolve the employee's legal employer(s)).
CREATE INDEX idx_employment_contract_employee ON employment_contract (employee_id);

-- ---------------------------------------------------------------------------
-- assignment (org_unit, reporting_to, role, effective dates)
-- ---------------------------------------------------------------------------
-- Org/team/manager live HERE, not on the employee (ARCHITECTURE.md §2). An employee holds
-- multiple CONCURRENT assignments; all concurrent assignments must resolve to the SAME
-- legal_employer (resolved via the org_unit_projection read model and enforced in the service).
CREATE TABLE assignment (
    id             UUID         NOT NULL PRIMARY KEY,
    employee_id    UUID         NOT NULL,

    -- The org_unit this assignment is to. Its legal_employer is learned from the local
    -- org read model (org_unit_projection), populated by the OrgUnit consumer.
    org_unit_id    UUID         NOT NULL,

    -- The manager this assignment reports to: a nullable employee_id (reporting line lives
    -- on the assignment, so the same person can report to different managers per assignment).
    reporting_to   UUID         NULL,

    -- The role held in this assignment (e.g. "cashier", "shift_lead"). Free text in this slice.
    role           VARCHAR(128) NOT NULL,

    -- Effective-dated: open-ended effective_to uses the far-future 9999-12-31 sentinel,
    -- never NULL (ENGINEERING-STANDARDS §2.5). Two assignments are CONCURRENT when their
    -- [effective_from, effective_to] ranges overlap.
    effective_from DATE         NOT NULL,
    effective_to   DATE         NOT NULL DEFAULT DATE '9999-12-31',

    -- Auditable (libs/tenant) — present on every Native table.
    created_at     TIMESTAMPTZ  NOT NULL,
    created_by     VARCHAR(255) NOT NULL,
    updated_at     TIMESTAMPTZ  NOT NULL,
    updated_by     VARCHAR(255) NOT NULL,
    version        BIGINT       NOT NULL,
    company_id     VARCHAR(64)  NOT NULL
);

ALTER TABLE assignment ENABLE ROW LEVEL SECURITY;
ALTER TABLE assignment FORCE ROW LEVEL SECURITY;

CREATE POLICY assignment_tenant_isolation ON assignment
    USING (company_id = current_setting('app.current_tenant', true))
    WITH CHECK (company_id = current_setting('app.current_tenant', true));

-- The concurrency check scans an employee's assignments; index that access path.
CREATE INDEX idx_assignment_employee ON assignment (employee_id);

-- ---------------------------------------------------------------------------
-- org_unit_projection (the LOCAL org read model)
-- ---------------------------------------------------------------------------
-- org_unit_id -> {company_id, legal_employer_id, type, active}. Maintained by the
-- OrgUnitCreated / OrgUnitChanged consumer (idempotent via processed_event). It is the
-- authoritative source the same-legal-employer invariant is checked against on assignment
-- creation (one legal_employer per company today, so the projected value is authoritative).
--
-- Like finance's consolidated_revenue, it is an app-maintained read model (NOT a
-- Debezium-CDC-derived projection), so the rule-4 Auditable + rule-5 RLS guarantees apply
-- uniformly: it carries the Auditable columns and is under RLS. The consumer writes it
-- inside a TenantContext scope bound to the EVENT's company_id, so the WITH CHECK passes.
CREATE TABLE org_unit_projection (
    org_unit_id       UUID         NOT NULL PRIMARY KEY,

    -- The org-unit's legal employer (the value the invariant resolves to).
    legal_employer_id UUID         NOT NULL,

    -- The org-unit kind (business_unit | outlet | team, ADR 0012) and whether it is active.
    type              VARCHAR(32)  NOT NULL,
    active            BOOLEAN      NOT NULL,

    -- Auditable (libs/tenant) — present on every Native table.
    created_at        TIMESTAMPTZ  NOT NULL,
    created_by        VARCHAR(255) NOT NULL,
    updated_at        TIMESTAMPTZ  NOT NULL,
    updated_by        VARCHAR(255) NOT NULL,
    version           BIGINT       NOT NULL,
    company_id        VARCHAR(64)  NOT NULL
);

ALTER TABLE org_unit_projection ENABLE ROW LEVEL SECURITY;
ALTER TABLE org_unit_projection FORCE ROW LEVEL SECURITY;

CREATE POLICY org_unit_projection_tenant_isolation ON org_unit_projection
    USING (company_id = current_setting('app.current_tenant', true))
    WITH CHECK (company_id = current_setting('app.current_tenant', true));

-- ---------------------------------------------------------------------------
-- transactional outbox (consumed by Debezium / StubRelay)
-- ---------------------------------------------------------------------------
-- Columns match libs/events OutboxRecord / OutboxWriter exactly. published_at is set by
-- the relay (ignored by Debezium). NOT Auditable and NOT under RLS: it is infrastructure
-- the relay tails, and its rows already carry company_id for downstream tenant scoping; the
-- writer always runs inside the company's transaction, which is itself RLS-scoped. PII is
-- NEVER written to a payload or header (rule 6) — EmployeeChanged carries only employee_id /
-- company_id / status, AssignmentChanged only the assignment dimensions.
CREATE TABLE outbox (
    id             UUID                     NOT NULL PRIMARY KEY,
    aggregate_type VARCHAR(255)             NOT NULL,
    aggregate_id   VARCHAR(255)             NOT NULL,
    event_type     VARCHAR(255)             NOT NULL,
    payload        BYTEA                    NOT NULL,
    headers        TEXT                     NULL,
    company_id     UUID                     NOT NULL,
    occurred_at    TIMESTAMPTZ              NOT NULL,
    published_at   TIMESTAMPTZ              NULL
);

CREATE INDEX idx_outbox_unpublished ON outbox (occurred_at, id) WHERE published_at IS NULL;

-- ---------------------------------------------------------------------------
-- processed_event (the idempotent-consumer dedupe store)
-- ---------------------------------------------------------------------------
-- libs/events ProcessedEventStore records each handled event UUID here and skips duplicates
-- (INSERT ... ON CONFLICT DO NOTHING), so a re-delivered OrgUnitCreated/OrgUnitChanged is
-- applied at most once (rule 3). The claim runs INSIDE the same transaction as the
-- projection upsert, so dedupe and side effects commit together. Not Auditable and not under
-- RLS: keyed solely by the globally-unique event id, not tenant-scoped business data.
CREATE TABLE processed_event (
    event_id     UUID                     NOT NULL PRIMARY KEY,
    processed_at TIMESTAMP WITH TIME ZONE NOT NULL
);
