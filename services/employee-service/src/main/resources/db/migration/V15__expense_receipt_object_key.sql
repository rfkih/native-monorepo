-- V15 (ADR 0048): expense receipts move from bytea-in-Postgres to the object store.
--
-- object_key holds the content-addressed key (employee/{company_id}/receipt/{sha256}.{ext});
-- the metadata columns (content_type, byte_size, sha256) and the RLS policy are unchanged —
-- only the payload's home moves. data becomes nullable for object-backed rows; legacy rows
-- keep their bytea and are converted opportunistically on read (read-through migration,
-- flag `native.media.read-through-migrate`) inside normal tenant-bound transactions — never
-- a Flyway backfill (a migration cannot reach the object store, and under FORCE RLS its
-- UPDATE would silently match 0 rows — the org-service V6/V7 lesson).
--
-- The CHECK guarantees a row is never payload-less: every receipt has its bytes inline OR
-- in the object store (receipts are expense evidence — a dangling metadata row would be an
-- audit problem, so the DB refuses to represent one).
ALTER TABLE expense_receipt ADD COLUMN IF NOT EXISTS object_key TEXT NULL;
ALTER TABLE expense_receipt ALTER COLUMN data DROP NOT NULL;
ALTER TABLE expense_receipt
  ADD CONSTRAINT ck_expense_receipt_payload_present
  CHECK (data IS NOT NULL OR object_key IS NOT NULL);
