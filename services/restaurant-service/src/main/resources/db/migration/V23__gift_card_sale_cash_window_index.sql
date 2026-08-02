-- restaurant-service V23 — gift-card-sale cash-window index (closing kasir, ADR 0036).
--
-- A gift card sold for CASH is a liability, not revenue, so it lives in `gift_card_sale`, not
-- `sale` — but its cash is physically in the drawer. The register close therefore sums a THIRD
-- drawer inflow: CASH-tender `gift_card_sale.amount_minor` with `occurred_at` in
-- [opened_at, closed_at). Found live in the review-fix drill: without this term, a 30k cash
-- gift-card sale surfaced as a phantom 30k OVER at close.
--
-- This migration only adds the window index for that sum — same shape and partial predicate as
-- V21's idx_sale_cash_window / V22's idx_payment_refund_window: (company_id, business_id,
-- occurred_at) partial on tender_type = 'CASH', INCLUDE the summed column to avoid a heap fetch.
-- Additive only; no table or column changes.
CREATE INDEX idx_gift_card_sale_cash_window
    ON gift_card_sale (company_id, business_id, occurred_at)
    INCLUDE (amount_minor)
    WHERE tender_type = 'CASH';
