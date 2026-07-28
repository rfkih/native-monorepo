-- V8 — store the one-time login password ENCRYPTED, until the employee activates.
--
-- When an owner creates a login for an employee, Keycloak issues a one-time temporary
-- password (change-on-first-login). The owner needs to hand that password to the employee,
-- so it is held here — encrypted at rest with the SAME AES-256-GCM column cipher as the
-- salary/NIK/bank PII (id.co.nativeapp.employee.config.PiiAttributeConverter) — and shown only
-- to owner/manager on the employee detail surface. It is PURGED the moment the employee first
-- authenticates (their first /me call — reachable only AFTER Keycloak forced the change), and a
-- TTL backstop (login_temp_password_set_at) hides a stale value if they never open the app.
-- This is the deliberate, bounded exception to "the temporary password is never stored"
-- (ADR 0014): encrypted, owner-only, auto-purged.
--
-- Both columns are nullable with no backfill (existing employees have no stored temp password),
-- so the FORCE-RLS migration-UPDATE trap does not apply.

ALTER TABLE employee ADD COLUMN login_temp_password_enc VARCHAR(255) NULL;
ALTER TABLE employee ADD COLUMN login_temp_password_set_at TIMESTAMPTZ NULL;
