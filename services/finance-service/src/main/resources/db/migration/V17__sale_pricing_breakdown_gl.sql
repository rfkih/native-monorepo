-- finance-service V17 — Phase 2 pricing breakdown GL: discount, service charge, tax.
--
-- ============================================================================================================================
-- ILLUSTRATIVE PLACEHOLDER — SME-GATED — REPLACE VIA HIGHER-VERSION DATA
-- ============================================================================================================================
-- The chart_of_account rows, posting_template v2, and role_account_map rows seeded here are ALL
-- ILLUSTRATIVE PLACEHOLDERS. They are intentional development seeds that make the 5-line GL entry
-- end-to-end balanced TODAY, while the real Indonesian COA mappings are SME-gated:
--
--   1. TAX REGIME: PB1 (regional, varies by municipality) vs PPN 11% (national VAT) is unconfirmed.
--      Account 2100 (Tax Payable Illustrative) is a placeholder; a real liability account must be
--      provided by an accounting + tax SME.
--   2. SERVICE CHARGE TREATMENT: Revenue (credit INCOME) vs liability (tip pool, credit LIABILITY)
--      is SME-gated. Account 4020 (Service Charge Revenue ILLUSTRATIVE) may become account 2110
--      (Service Charge Payable) if the liability treatment applies.
--   3. SALES DISCOUNT: Whether contra-revenue (debit 4010) or a separate marketing expense is
--      SME-gated.
--   4. CHART OF ACCOUNTS: The illustrative accounts (2100, 4010, 4020) must be mapped to the
--      company's actual SAK-EMKM or tax-compliant COA by an accountant.
--
-- To replace with real rules (NO code change required):
--   INSERT INTO posting_template (id, event_kind, version, uses_illustrative, effective_from, effective_to)
--       VALUES (gen_random_uuid(), 'SALE', 3, FALSE, <date>, DATE '9999-12-31');
--   ... plus the template lines and role_account_map v2 rows.
-- Higher-version rows are preferred by the resolver (ORDER BY version DESC LIMIT 1).
-- ============================================================================================================================
--
-- This migration is ADDITIVE (never edit an applied migration). It:
--   1. Widens posting_template_line.amount_basis CHECK to accept Phase 2 basis values.
--   2. Adds new illustrative COA accounts for discount, service-charge, tax.
--   3. Seeds a SALE posting template v2 with 5 lines.
--   4. Seeds role_account_map v2 entries for new Phase 2 roles.
--   5. Adds SERVICE_CHARGE_PAYABLE to role_account_map (defined but unused, reserved for SME).

-- ---------------------------------------------------------------------------
-- 1. Widen amount_basis CHECK to accept Phase 2 basis values.
-- ---------------------------------------------------------------------------
-- V13 seeded: CHECK (amount_basis IN ('GROSS'))
-- Phase 2 adds: GROSS_REVENUE, DISCOUNT, SERVICE_CHARGE, TAX
-- Same drop-and-recreate pattern as V16 used for event_kind.
ALTER TABLE posting_template_line
    DROP CONSTRAINT ck_template_amount_basis;

ALTER TABLE posting_template_line
    ADD CONSTRAINT ck_template_amount_basis
        CHECK (amount_basis IN (
            'GROSS',           -- original: grand total (clearing debit)
            'GROSS_REVENUE',   -- Phase 2: subtotal (before discount) → revenue credit
            'DISCOUNT',        -- Phase 2: discount amount → contra-revenue debit
            'SERVICE_CHARGE',  -- Phase 2: service charge → service-charge revenue credit
            'TAX'              -- Phase 2: tax amount → tax payable credit
        ));

-- ---------------------------------------------------------------------------
-- 2. New illustrative COA accounts.
-- ---------------------------------------------------------------------------
-- 2100 — Tax Payable (Illustrative). A current LIABILITY for collected restaurant tax.
--         The tax regime (PB1 ~10% vs PPN 11%) and the actual account code are SME-gated.
INSERT INTO chart_of_account (account_code, name, account_type)
VALUES ('2100', 'Tax Payable — Restaurant (ILLUSTRATIVE — regime + rate + account SME-gated)',
        'LIABILITY')
ON CONFLICT (account_code) DO NOTHING;

-- 2110 — Service Charge Payable (Illustrative, RESERVED). Used when service charge is a
--         tip-pool liability rather than revenue. Phase 2 templates do NOT use this account;
--         it is seeded for completeness and future SME-confirmed use.
INSERT INTO chart_of_account (account_code, name, account_type)
VALUES ('2110', 'Service Charge Payable — Tip Pool (ILLUSTRATIVE — treatment SME-gated)',
        'LIABILITY')
ON CONFLICT (account_code) DO NOTHING;

-- 4010 — Sales Discount (Illustrative). A contra-revenue account for order-level discounts.
--         Whether this is contra-revenue or a marketing expense is SME-gated.
INSERT INTO chart_of_account (account_code, name, account_type)
VALUES ('4010', 'Sales Discount (ILLUSTRATIVE — contra-revenue treatment SME-gated)',
        'REVENUE')
ON CONFLICT (account_code) DO NOTHING;

-- 4020 — Service Charge Revenue (Illustrative). Used when service charge is treated as income.
--         If SME determines it is a tip-pool liability, map SERVICE_CHARGE_REVENUE to 2110 instead.
INSERT INTO chart_of_account (account_code, name, account_type)
VALUES ('4020', 'Service Charge Revenue (ILLUSTRATIVE — vs liability treatment SME-gated)',
        'REVENUE')
ON CONFLICT (account_code) DO NOTHING;

-- ---------------------------------------------------------------------------
-- 3. SALE posting template v2 (5-line breakdown).
-- ---------------------------------------------------------------------------
-- Illustrative SALE GL entry (Phase 2):
--
--   Dr  CLEARING              GROSS (= grandTotal, customer-pays)   [basis: GROSS]
--   Dr  SALES_DISCOUNT        DISCOUNT (if > 0)                     [basis: DISCOUNT]
--   Cr  GROSS_REVENUE         SUBTOTAL (before discount)            [basis: GROSS_REVENUE]
--   Cr  SERVICE_CHARGE_REVENUE SERVICE_CHARGE (if > 0)              [basis: SERVICE_CHARGE]
--   Cr  TAX_PAYABLE           TAX (if > 0)                          [basis: TAX]
--
-- Zero-amount lines are omitted by JournalPostingService.buildEntryForSale, so a sale with no
-- discount/tax/service charge (legacy/carwash) collapses to the 2-line Dr CLEARING / Cr REVENUE
-- entry, identical to the v1 behaviour (via GROSS_REVENUE mapping to the same 4000 account).
--
-- Balance check: Dr side = GROSS + DISCOUNT = grandTotal + discount.
--                Cr side = GROSS_REVENUE + SERVICE_CHARGE + TAX
--                        = subtotal + serviceCharge + tax
--                        = (taxableBase + discount) + serviceCharge + tax
--                        = (grandTotal - serviceCharge - tax + discount) + serviceCharge + tax
--                        = grandTotal + discount.  ✓
INSERT INTO posting_template (id, event_kind, version, uses_illustrative, effective_from, effective_to)
VALUES (gen_random_uuid(), 'SALE', 2, TRUE, DATE '2000-01-01', DATE '9999-12-31');

-- Line 1: Dr CLEARING (GROSS = grandTotal) — clearing debit, routing is tender-dependent.
INSERT INTO posting_template_line (id, template_id, line_no, account_role, side, amount_basis)
SELECT gen_random_uuid(), pt.id, 1, 'CASH_CLEARING', 'DEBIT', 'GROSS'
  FROM posting_template pt WHERE pt.event_kind = 'SALE' AND pt.version = 2;

-- Line 2: Dr SALES_DISCOUNT (DISCOUNT amount — zero-omitted when no discount).
INSERT INTO posting_template_line (id, template_id, line_no, account_role, side, amount_basis)
SELECT gen_random_uuid(), pt.id, 2, 'SALES_DISCOUNT', 'DEBIT', 'DISCOUNT'
  FROM posting_template pt WHERE pt.event_kind = 'SALE' AND pt.version = 2;

-- Line 3: Cr GROSS_REVENUE (SUBTOTAL = sum of line totals before discount).
INSERT INTO posting_template_line (id, template_id, line_no, account_role, side, amount_basis)
SELECT gen_random_uuid(), pt.id, 3, 'GROSS_REVENUE', 'CREDIT', 'GROSS_REVENUE'
  FROM posting_template pt WHERE pt.event_kind = 'SALE' AND pt.version = 2;

-- Line 4: Cr SERVICE_CHARGE_REVENUE (SERVICE_CHARGE amount — zero-omitted when no service charge).
INSERT INTO posting_template_line (id, template_id, line_no, account_role, side, amount_basis)
SELECT gen_random_uuid(), pt.id, 4, 'SERVICE_CHARGE_REVENUE', 'CREDIT', 'SERVICE_CHARGE'
  FROM posting_template pt WHERE pt.event_kind = 'SALE' AND pt.version = 2;

-- Line 5: Cr TAX_PAYABLE (TAX amount — zero-omitted when no tax).
INSERT INTO posting_template_line (id, template_id, line_no, account_role, side, amount_basis)
SELECT gen_random_uuid(), pt.id, 5, 'TAX_PAYABLE', 'CREDIT', 'TAX'
  FROM posting_template pt WHERE pt.event_kind = 'SALE' AND pt.version = 2;

-- ---------------------------------------------------------------------------
-- 4. role_account_map v2 — Phase 2 new roles (version 2, illustrative).
-- ---------------------------------------------------------------------------
-- GROSS_REVENUE maps to the same 4000 (Sales Revenue) as the existing REVENUE role.
-- This allows a zero-discount/tax event (legacy/carwash) to collapse to Dr CLEARING / Cr 4000
-- when the GROSS_REVENUE line resolves — identical revenue recognition.
INSERT INTO role_account_map
    (id, account_role, gl_account_code, version, uses_illustrative, effective_from, effective_to)
VALUES
    (gen_random_uuid(), 'GROSS_REVENUE',         '4000', 2, TRUE, DATE '2000-01-01', DATE '9999-12-31'),
    (gen_random_uuid(), 'SALES_DISCOUNT',         '4010', 1, TRUE, DATE '2000-01-01', DATE '9999-12-31'),
    (gen_random_uuid(), 'SERVICE_CHARGE_REVENUE', '4020', 1, TRUE, DATE '2000-01-01', DATE '9999-12-31'),
    (gen_random_uuid(), 'TAX_PAYABLE',            '2100', 1, TRUE, DATE '2000-01-01', DATE '9999-12-31'),
    -- SERVICE_CHARGE_PAYABLE: defined, illustrative, reserved (Phase 2 templates do NOT use it).
    (gen_random_uuid(), 'SERVICE_CHARGE_PAYABLE', '2110', 1, TRUE, DATE '2000-01-01', DATE '9999-12-31');

-- ---------------------------------------------------------------------------
-- SME CONFIRMATION REQUIRED (do not remove this comment until confirmed):
-- ---------------------------------------------------------------------------
-- A. Tax regime: PB1 (regional entertainment/restaurant tax, ~10%) vs PPN 11% (national VAT).
--    The current seed uses PB1_RESTAURANT 1000 bp (10%) — ILLUSTRATIVE only.
-- B. Service charge treatment: Revenue (4020) vs tip-pool liability (2110). ILLUSTRATIVE uses
--    SERVICE_CHARGE_REVENUE → 4020. SME must confirm; if liability, re-seed with 2110.
-- C. Sales discount: contra-revenue (4010) vs marketing expense (50xx). ILLUSTRATIVE uses 4010.
-- D. All account codes (2100, 2110, 4010, 4020) must be mapped to the client's real COA.
-- E. The SALE_VOID and SALE_REFUND templates (V16) must be updated in a V18+ migration to also
--    reverse DISCOUNT, SERVICE_CHARGE, and TAX legs when breakdown fields are present.
--    Phase 2 void/refund reversal is implemented at the service layer (see ReversalPostingWriter)
--    using the GROSS amount from SaleVoided/SaleRefunded — those events do not yet carry the
--    breakdown. A future event evolution (SaleVoided v2) may carry the breakdown for per-leg
--    reversal; until then the reversal uses the existing 2-line GROSS template.
-- ============================================================================================================================
