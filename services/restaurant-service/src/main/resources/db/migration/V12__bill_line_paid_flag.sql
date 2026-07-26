-- restaurant-service V12 — Increment 3: split-by-item support on bill_line.
--
-- Adds a 'paid' flag so individual lines can be paid on separate checks without
-- transitioning the whole bill to PAID. When all lines are paid the bill writer
-- transitions the bill itself to PAID.
--
-- Additive migration only; RLS policy on bill_line already exists (V11).

ALTER TABLE bill_line
    ADD COLUMN IF NOT EXISTS paid         BOOLEAN  NOT NULL DEFAULT false,
    ADD COLUMN IF NOT EXISTS paid_sale_id UUID     NULL;

CREATE INDEX IF NOT EXISTS idx_bill_line_bill_unpaid
    ON bill_line (company_id, bill_id)
    WHERE paid = false;
