-- V33 (ADR 0049, P0): inert wire for per-sale seller attribution.
--
-- sold_by_user_id captures the OPERATOR's Keycloak sub (the person who rang the sale) --
-- distinct from the Auditable created_by, which will record the DEVICE once outlet
-- credentials exist (ADR 0049 P3). NULL until ADR 0049 P2 (the PIN / operator-session
-- flow) starts populating it -- today every row stays NULL and nothing reads it, so this
-- is a purely additive, behavior-preserving column add.
--
-- No RLS work needed: the existing sale_tenant_isolation policy (V1) covers new columns
-- automatically. No backfill: there is nothing to backfill (the column starts unset).
ALTER TABLE sale ADD COLUMN IF NOT EXISTS sold_by_user_id VARCHAR(64) NULL;

COMMENT ON COLUMN sale.sold_by_user_id IS
    'The seller (operator''s Keycloak sub) captured at ring time; null until ADR 0049 P2.';
