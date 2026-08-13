-- payment-service V6 — per-environment GATEWAY credentials (ADR 0045 amendment).
--
-- The merchant's Midtrans credentials were a SINGLE slot: provider_environment (SANDBOX/PRODUCTION)
-- plus server_key_encrypted / client_key_encrypted / server_key_last4. Because the key is write-only
-- (blank on save keeps the stored value), flipping the environment WITHOUT re-entering the key left
-- the OTHER environment's key in place (e.g. environment=SANDBOX still bound to a PRODUCTION key) →
-- Midtrans rejected auth at the till, surfaced only as the confusing "Demo" MANUAL fallback.
--
-- This EXPANDS the schema to TWO slots — one per environment — so each key lives with its own
-- environment and can never be mismatched. provider_environment becomes purely the ACTIVE selector
-- (which slot the till + webhook use); activating an environment now requires that slot to hold a
-- key (enforced in the domain).
--
-- Expand/contract: the legacy single-slot columns (server_key_encrypted / client_key_encrypted /
-- server_key_last4) are LEFT IN PLACE, dead — the app stops reading them from this release. A later
-- migration CONTRACTS (drops) them once no deployed image references them, so an auto-rollback to
-- the previous image still finds its columns (forward-only safety, ADR 0057).

ALTER TABLE payment_settings
    ADD COLUMN sandbox_server_key_encrypted    BYTEA       NULL,
    ADD COLUMN sandbox_client_key_encrypted    BYTEA       NULL,
    ADD COLUMN sandbox_server_key_last4        VARCHAR(4)  NULL,
    ADD COLUMN production_server_key_encrypted BYTEA       NULL,
    ADD COLUMN production_client_key_encrypted BYTEA       NULL,
    ADD COLUMN production_server_key_last4     VARCHAR(4)  NULL;

-- Backfill the per-environment slots from the legacy single slot: copy each row's current key into
-- the slot that matches its current provider_environment. payment_settings is FORCE ROW LEVEL
-- SECURITY and Flyway runs as the table owner with no app.current_tenant GUC set — a FORCE-RLS
-- UPDATE would then match ZERO rows (the fleet's known migration-backfill gotcha). Drop FORCE for
-- the backfill, then restore it immediately.
ALTER TABLE payment_settings NO FORCE ROW LEVEL SECURITY;

UPDATE payment_settings
   SET sandbox_server_key_encrypted = server_key_encrypted,
       sandbox_client_key_encrypted = client_key_encrypted,
       sandbox_server_key_last4     = server_key_last4
 WHERE provider_environment = 'SANDBOX'
   AND server_key_encrypted IS NOT NULL;

UPDATE payment_settings
   SET production_server_key_encrypted = server_key_encrypted,
       production_client_key_encrypted = client_key_encrypted,
       production_server_key_last4     = server_key_last4
 WHERE provider_environment = 'PRODUCTION'
   AND server_key_encrypted IS NOT NULL;

ALTER TABLE payment_settings FORCE ROW LEVEL SECURITY;
