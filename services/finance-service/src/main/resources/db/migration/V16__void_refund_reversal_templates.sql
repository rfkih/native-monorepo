-- finance-service V16 — void/refund reversal posting templates (ADR 0006, slice 4).
--
-- ============================================================================================================================
-- ILLUSTRATIVE PLACEHOLDER — SME-GATED — REPLACE VIA HIGHER-VERSION DATA
-- ============================================================================================================================
-- These templates are the CONTRA of the illustrative SALE template (V13). A real accountant
-- should confirm the debit/credit treatment for void vs. refund under SAK-EMKM before going live.
-- Replace via higher-version rows (version 2+) without modifying this migration — same pattern as V13/V15.
-- ============================================================================================================================
--
-- This migration is ADDITIVE. It seeds:
--   1. SALE_VOID posting template: Dr REVENUE / Cr CLEARING (exact inverse of SALE — full reversal).
--   2. SALE_REFUND posting template: Dr REVENUE / Cr CLEARING (proportional — uses refund amount as GROSS).
--
-- Both templates reuse the existing REVENUE and CASH_CLEARING account roles so the clearing-role
-- override mechanism (ADR 0006 slice 2 — QRIS/CARD routing) works identically for void/refund as
-- it does for the original SALE: the writer passes clearingRoleOverride=QRIS_CLEARING|CARD_CLEARING
-- when the original tender was digital, and the template's CASH_CLEARING leg is substituted.

-- Widen the event_kind check constraint to accept the two new reversal kinds.
-- The constraint was originally seeded in V13 with only SALE/EXPENSE/LABOR.
-- We drop-and-recreate it here (additive schema evolution — no data changes).
ALTER TABLE posting_template
    DROP CONSTRAINT ck_posting_template_event_kind;

ALTER TABLE posting_template
    ADD CONSTRAINT ck_posting_template_event_kind
        CHECK (event_kind IN ('SALE', 'EXPENSE', 'LABOR', 'SALE_VOID', 'SALE_REFUND'));

-- posting_template seeds
INSERT INTO posting_template (id, event_kind, version, uses_illustrative, effective_from, effective_to) VALUES
    (gen_random_uuid(), 'SALE_VOID',   1, TRUE, DATE '2000-01-01', DATE '9999-12-31'),
    (gen_random_uuid(), 'SALE_REFUND', 1, TRUE, DATE '2000-01-01', DATE '9999-12-31');

-- SALE_VOID template: Dr REVENUE (4000) / Cr CASH_CLEARING (1900)
-- This is the exact contra of the SALE template, so the net effect when both entries are present
-- is Dr CLEARING / Cr REVENUE + Dr REVENUE / Cr CLEARING = net zero (revenue fully reversed).
INSERT INTO posting_template_line (id, template_id, line_no, account_role, side, amount_basis)
SELECT gen_random_uuid(), pt.id, 1, 'REVENUE',      'DEBIT',  'GROSS'
  FROM posting_template pt WHERE pt.event_kind = 'SALE_VOID' AND pt.version = 1;
INSERT INTO posting_template_line (id, template_id, line_no, account_role, side, amount_basis)
SELECT gen_random_uuid(), pt.id, 2, 'CASH_CLEARING', 'CREDIT', 'GROSS'
  FROM posting_template pt WHERE pt.event_kind = 'SALE_VOID' AND pt.version = 1;

-- SALE_REFUND template: Dr REVENUE (4000) / Cr CASH_CLEARING (1900) using the REFUND AMOUNT as GROSS
-- The caller passes refund_amount (not the full original amount) as the monetary gross, so the
-- proportional reversal happens automatically without a new amount_basis variant.
INSERT INTO posting_template_line (id, template_id, line_no, account_role, side, amount_basis)
SELECT gen_random_uuid(), pt.id, 1, 'REVENUE',       'DEBIT',  'GROSS'
  FROM posting_template pt WHERE pt.event_kind = 'SALE_REFUND' AND pt.version = 1;
INSERT INTO posting_template_line (id, template_id, line_no, account_role, side, amount_basis)
SELECT gen_random_uuid(), pt.id, 2, 'CASH_CLEARING', 'CREDIT', 'GROSS'
  FROM posting_template pt WHERE pt.event_kind = 'SALE_REFUND' AND pt.version = 1;
