-- V10 — the employee's Indonesian tax id (NPWP), PII, column-encrypted at rest.
--
-- The P1 statutory engine's no-NPWP surcharge (UU PPh Art 21(5a): x120% on both the monthly TER
-- result and the annual Art-17 liability) needs a per-employee "is an NPWP on file" signal. The
-- column holds AES-256-GCM ciphertext exactly like nik/bank_account/login_temp_password_enc
-- (id.co.nativeapp.employee.config.PiiAttributeConverter, rule 6) — the application works in
-- plaintext, the row never does.
--
-- Nullable with NO backfill: an employee created before this migration (or one still missing the
-- number) simply has no NPWP on file yet (Employee.hasNpwp() = false, and the engine's surcharge
-- path applies until HR records it via the employee update endpoint). Because there is no backfill
-- UPDATE against this FORCE-RLS table, the RLS migration-UPDATE trap (see
-- docs — "Flyway UPDATE on a FORCE-RLS table silently matches 0 rows") does not apply here (V8's
-- login_temp_password_enc precedent).

ALTER TABLE employee ADD COLUMN npwp_enc TEXT NULL;
