-- ADR 0049 P1: the per-employee operator PIN a cashier uses to identify themselves at a shared
-- outlet terminal. POST /api/v1/operators/session verifies (employeeId, PIN) and mints a
-- self-contained, OFFLINE-verified operator token (libs/security OperatorTokenCodec) — the seller
-- attribution for a till sale, never a client-supplied id.
--
-- pin_hash is a PLAIN column (VARCHAR, an Argon2id PHC string, e.g.
-- "$argon2id$v=19$m=...,t=...,p=...$salt$hash") — deliberately NOT wrapped in the
-- PiiAttributeConverter (AES-256-GCM at rest): Argon2id is a one-way KDF, never decrypted back to
-- a plaintext PIN, so the column-level PII cipher (built for a REVERSIBLE value) does not apply.
-- failed_attempts / locked_until back the lockout policy (OperatorPin domain, employee-service).
--
-- Every Auditable column + FORCE ROW LEVEL SECURITY, identical shape to every other employee-
-- service table (rule 4 + rule 5). One PIN per employee per company (a company-global PIN,
-- scoped to whichever outlet the employee is actively assigned to at sign-in time).
CREATE TABLE operator_pin (
    id               UUID         NOT NULL PRIMARY KEY,
    employee_id      UUID         NOT NULL,
    -- Argon2id PHC string (one-way KDF) — never decrypted, never logged, never returned (ADR 0049 P1).
    pin_hash         VARCHAR(255) NOT NULL,
    failed_attempts  INT          NOT NULL DEFAULT 0,
    locked_until     TIMESTAMPTZ  NULL,

    created_at       TIMESTAMPTZ  NOT NULL,
    created_by       VARCHAR(255) NOT NULL,
    updated_at       TIMESTAMPTZ  NOT NULL,
    updated_by       VARCHAR(255) NOT NULL,
    version          BIGINT       NOT NULL,
    company_id       VARCHAR(64)  NOT NULL
);

COMMENT ON COLUMN operator_pin.pin_hash IS
    'Argon2id PHC string (one-way KDF); never decrypted, never logged, ADR 0049 P1.';

ALTER TABLE operator_pin ENABLE ROW LEVEL SECURITY;
ALTER TABLE operator_pin FORCE ROW LEVEL SECURITY;
CREATE POLICY operator_pin_tenant_isolation ON operator_pin
    USING (company_id = current_setting('app.current_tenant', true))
    WITH CHECK (company_id = current_setting('app.current_tenant', true));

CREATE UNIQUE INDEX uq_operator_pin_employee ON operator_pin (company_id, employee_id);
