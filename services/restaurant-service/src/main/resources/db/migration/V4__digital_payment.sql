-- restaurant-service: digital payment capture support (ADR 0006, slice 3).
--
-- Extends restaurant_order.status to accommodate the new AWAITING_PAYMENT
-- status (16 chars exactly: AWAITING_PAYMENT = 16). Widens the column to
-- VARCHAR(24) to leave head-room for future statuses and to make the invariant
-- explicit. No data migration needed (existing rows carry PENDING or COMPLETED).
--
-- Adds a CHECK constraint documenting the valid status values; PostgreSQL
-- validates existing rows but all current rows are PENDING/COMPLETED so no
-- conflict arises.

ALTER TABLE restaurant_order
    ALTER COLUMN status TYPE VARCHAR(24);

ALTER TABLE restaurant_order
    ADD CONSTRAINT ck_order_status CHECK (
        status IN ('PENDING', 'AWAITING_PAYMENT', 'COMPLETED'));
