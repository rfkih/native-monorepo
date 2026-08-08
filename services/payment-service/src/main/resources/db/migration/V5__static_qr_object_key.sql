-- V5 (ADR 0048): the static QRIS image moves from bytea-in-Postgres to the object store.
--
-- static_qr_object_key holds the content-addressed key
-- (payment/{company_id}/qr/{sha256}.{ext}); the metadata columns and RLS are unchanged.
-- Legacy rows keep their bytea until the read-through migration converts them on serve
-- (never a Flyway backfill — a migration cannot reach the object store, and under FORCE
-- RLS its UPDATE would silently match 0 rows: the org-service V6/V7 lesson).
--
-- The qr_complete CHECK is restated for the dual-home era: an image is "present" when the
-- payload lives inline OR in the store; present-ness must still travel with ALL metadata
-- columns together (the V2 invariant), and a row may never carry BOTH payload homes.
ALTER TABLE payment_settings ADD COLUMN IF NOT EXISTS static_qr_object_key TEXT NULL;

ALTER TABLE payment_settings DROP CONSTRAINT IF EXISTS ck_payment_settings_qr_complete;
ALTER TABLE payment_settings
  ADD CONSTRAINT ck_payment_settings_qr_complete
  CHECK (
        ((static_qr_data IS NULL AND static_qr_object_key IS NULL)
             = (static_qr_content_type IS NULL))
    AND ((static_qr_data IS NULL AND static_qr_object_key IS NULL)
             = (static_qr_byte_size IS NULL))
    AND ((static_qr_data IS NULL AND static_qr_object_key IS NULL)
             = (static_qr_sha256 IS NULL))
    AND NOT (static_qr_data IS NOT NULL AND static_qr_object_key IS NOT NULL)
  );
