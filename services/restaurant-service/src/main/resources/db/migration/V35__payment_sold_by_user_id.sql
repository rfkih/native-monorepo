-- V35 (ADR 0049, P4): async-tender operator attribution wire.
--
-- sold_by_user_id captures the OPERATOR's Keycloak sub AT RING TIME (checkout), for a PENDING
-- digital (QRIS/CARD) tender only -- payment.service.PaymentWriter#recordPendingDigitalInCurrentTx
-- stamps it when a verified operator session is present. There is no live operator session on the
-- async PaymentChargeSucceeded capture thread (a Kafka consumer, no HTTP request), so this column
-- is what lets PaymentCaptureWriter#capture thread the ring-time operator through to the sale's
-- sold_by_user_id / commission metric subject instead of falling back to the bound (device) actor.
--
-- Mirrors V33 (sale.sold_by_user_id)'s RLS-safe additive-column style: no RLS work needed (the
-- existing payment_tenant_isolation policy, V3, covers new columns automatically); no backfill
-- (every existing row stays NULL -- purely additive, behavior-preserving).
ALTER TABLE payment ADD COLUMN IF NOT EXISTS sold_by_user_id VARCHAR(64) NULL;

COMMENT ON COLUMN payment.sold_by_user_id IS
    'The operator (Keycloak sub) captured at ring time for a PENDING digital tender; read back at async capture (ADR 0049 P4).';
