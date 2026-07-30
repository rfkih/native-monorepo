-- barbershop-service V5 — Phase 4 of the POS-parity program (ADR 0027: loyalty-service and
-- eventual-consistency redemption). Extends barbershop_ticket's pricing-reconciliation CHECK to
-- account for a points-loyalty redemption, which V4 added the (nullable) column for but did NOT wire
-- into the CHECK — this migration closes that gap. Identical DDL to carwash-service V10 — only this
-- header and the target table (barbershop_ticket, not carwash_ticket) differ.
--
-- ---------------------------------------------------------------------------
-- Why the identity extends (ADR 0027 decision 4)
-- ---------------------------------------------------------------------------
-- V1 seeded:
--     CONSTRAINT ck_barbershop_ticket_total_balances
--         CHECK (subtotal_minor - discount_minor + service_charge_minor + tax_minor = total_minor)
--
-- Points-loyalty redemption is CONTRA-REVENUE (account role LOYALTY_DISCOUNT in finance) — a SECOND,
-- INDEPENDENT deduction from subtotal, distinct from the promo discount. It reduces `total_minor`
-- exactly like `discount_minor` does, so the reconciliation identity gains one more term:
--
--     subtotal_minor - discount_minor - loyalty_redeemed_minor + service_charge_minor + tax_minor
--         = total_minor
--
-- `loyalty_redeemed_minor` is NULLable (V4): most tickets redeem nothing. COALESCE(...,0) makes the
-- extended CHECK accept both a NULL (untouched, pre-Phase-4-shaped) ticket and a 0 redemption
-- identically to the original identity — every ticket written before this migration, and every new
-- ticket that never touches loyalty, satisfies the SAME arithmetic as before. This is a pure
-- constraint WIDENING (a strict superset of previously-valid rows remains valid; no existing row can
-- newly violate it), so it is backward-safe and reversible: DROP the extended constraint and
-- re-ADD the V1 form to roll back — no data migration either direction.
--
-- ---------------------------------------------------------------------------
-- What is deliberately NOT in this identity
-- ---------------------------------------------------------------------------
--   * `discount_minor` stays PROMO-ONLY. It is the existing order-level/promotions discount (V3);
--     loyalty-points redemption is its own leg (`loyalty_redeemed_minor`), never folded into
--     `discount_minor` — the two are accounted, reported, and (in finance) posted to DIFFERENT GL
--     roles (SALES_DISCOUNT vs LOYALTY_DISCOUNT) and must stay separately auditable at the source.
--   * `gift_card_redeemed_minor` (also added by V4) is DELIBERATELY EXCLUDED from this identity. A
--     gift-card redemption is a TENDER settlement, not a price reduction (ADR 0027 decision 4): the
--     ticket's `total_minor` is unchanged by it — only HOW that total is paid changes (part of the
--     clearing debit is redirected to `Dr GIFT_CARD_LIABILITY` instead of the tender-clearing
--     account). It belongs in the payment/tender reconciliation, not the pricing-breakdown identity.
--
-- ---------------------------------------------------------------------------
-- Mechanics
-- ---------------------------------------------------------------------------
-- Drop-and-recreate CHECK is the established house pattern for widening a constraint without a data
-- migration (finance-service V16/V17/V25/V28 do the same for posting_template CHECKs; carwash-service
-- V10 does the identical widening for its own ticket table). No RLS/audit change: this migration
-- touches only a CHECK constraint on an existing table.

ALTER TABLE barbershop_ticket
    DROP CONSTRAINT ck_barbershop_ticket_total_balances;

ALTER TABLE barbershop_ticket
    ADD CONSTRAINT ck_barbershop_ticket_total_balances
        CHECK (subtotal_minor - discount_minor - COALESCE(loyalty_redeemed_minor, 0)
                   + service_charge_minor + tax_minor = total_minor);
