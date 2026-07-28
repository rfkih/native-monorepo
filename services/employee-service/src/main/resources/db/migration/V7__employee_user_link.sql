-- V7 — link an HR employee to their console login (the Keycloak subject id).
--
-- Nullable, no backfill (existing employees have no login), so the FORCE-RLS
-- migration-UPDATE trap does not apply. VARCHAR(64) matches the org-service
-- user_outlet_assignment.user_id convention: the Keycloak `sub` is a stable,
-- non-PII opaque id, safe to store unencrypted. The partial unique index makes
-- a login linkable to at most ONE employee per company (the service pre-checks
-- for a clean 409; the index is the concurrency backstop).

ALTER TABLE employee ADD COLUMN user_id VARCHAR(64) NULL;

CREATE UNIQUE INDEX uq_employee_user
    ON employee (company_id, user_id)
    WHERE user_id IS NOT NULL;
