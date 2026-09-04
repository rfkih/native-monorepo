-- restaurant-service V38 — dynamic QRIS/CARD gateway support for BILLS (full-bill only).
--
-- Mirrors the existing ORDER two-step gateway flow (V3 payment + PaymentCaptureWriter): a bill's
-- gateway payment is a restaurant `payment` row keyed by paymentId, so payment-service and the
-- existing PaymentChargeSucceeded consumer plumbing (topic, schema, dedupe) need ZERO changes —
-- only this service's own dispatch (order payment vs bill payment) changes.
--
-- Expand-only (no column dropped, no data rewritten):
--
-- 1. payment.order_id becomes NULLABLE — a bill-originated payment carries bill_id instead of
--    order_id. ck_payment_order_xor_bill enforces exactly one of the two is ever set. Every
--    existing row already has order_id set (bill_id defaults NULL), so the CHECK validates
--    trivially against current data with no table rewrite concern (same technique V24 used to
--    re-add ck_payment_tender_type after widening it).
-- 2. payment gains discount_minor — needed so BillPaymentCaptureWriter can deterministically
--    RECOMPUTE the check breakdown at capture time (bills have no `order`-style aggregate to
--    snapshot a fixed discount on; for an order-originated payment this column stays NULL,
--    unused). There is deliberately NO stored "check idempotency key" column: the sale idempotency
--    key BillPaymentCaptureWriter records under is derived ON-THE-FLY from the payment's OWN id at
--    capture time (payment_id + ":bill-capture-sale"), mirroring exactly how the order path derives
--    payment_id + ":capture-sale" — code-review HIGH fix: an earlier cut stamped a BILL-WIDE shared
--    key at mint time, which collided when a bill recorded a second check in its lifetime.
-- 3. bill_line gains pending_payment_id — a per-line RESERVATION set by
--    BillWriter.initiatePendingPayment so a concurrent cash payBill() cannot also claim the same
--    unpaid lines while a gateway PENDING payment is in flight; cleared on abandon, or at capture
--    via a GUARDED mark-paid UPDATE keyed to the capturing payment_id (code-review C1 fix — the
--    cash path's own mark-paid UPDATE is separately guarded "paid = false AND pending_payment_id
--    IS NULL" so it can never clobber a live reservation either).
--
-- Money throughout stays minor units (BIGINT) + ISO-4217 currency — never a float (rule 8). No
-- backfill needed: both ALTER TABLE statements below are pure DDL (no data UPDATE), so the V36/V37
-- "FORCE RLS silently matches zero rows" trap does not apply here — that trap is specific to a
-- Flyway-session UPDATE against a FORCE-RLS table, not to adding a column or a CHECK constraint.

-- ---------------------------------------------------------------------------
-- 1. payment — order_id becomes optional; bill_id + capture-recompute column
-- ---------------------------------------------------------------------------
ALTER TABLE payment
    ALTER COLUMN order_id DROP NOT NULL;

ALTER TABLE payment
    ADD COLUMN bill_id UUID NULL;

-- The manual (staff-entered) discount requested at pay-pending mint time — re-fed into the SAME
-- promotions-engine + tax-charge computation at capture time (BillPaymentCaptureWriter) so the
-- recomputed grand total reproduces the amount authorized at mint, deterministically, without
-- needing a bill-side `applied_promotion` audit trail. NULL for an order-originated payment.
ALTER TABLE payment
    ADD COLUMN discount_minor BIGINT NULL;

ALTER TABLE payment
    ADD CONSTRAINT ck_payment_order_xor_bill CHECK ((order_id IS NULL) <> (bill_id IS NULL));

-- Access path: BillWriter.initiatePendingPayment's self-heal lookup (a bill's own live PENDING
-- gateway payment, if any) and BillPaymentCaptureWriter's bill lookup at capture.
CREATE INDEX idx_payment_bill_id ON payment (bill_id);

-- ---------------------------------------------------------------------------
-- 2. bill_line — pending_payment_id (line reservation for an in-flight gateway payment)
-- ---------------------------------------------------------------------------
ALTER TABLE bill_line
    ADD COLUMN pending_payment_id UUID NULL;

-- Access path: BillPaymentCaptureWriter resolves "the lines reserved for THIS payment" and
-- BillPaymentWriter.abandon releases them, both keyed by pending_payment_id. Also the predicate
-- BillWriter.payBill's/recordCheck's guarded mark-paid UPDATEs filter/verify against (C1 fix).
CREATE INDEX idx_bill_line_pending_payment_id ON bill_line (pending_payment_id);
