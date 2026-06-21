-- restaurant-service V8 — persist the order-level fixed discount so payParked can
-- recompute the identical PriceBreakdown at pay time (H1 fix).
--
-- discount_minor is nullable: NULL means no fixed discount was applied. Existing rows
-- (pre-Phase 4) and direct-checkout orders without a discount correctly remain NULL.

ALTER TABLE restaurant_order
    ADD COLUMN IF NOT EXISTS discount_minor BIGINT NULL;
