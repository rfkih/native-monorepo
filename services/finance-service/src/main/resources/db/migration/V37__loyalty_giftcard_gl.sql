-- finance-service V37 — ILLUSTRATIVE GL for loyalty + gift cards (ADR 0027, Phase 4 POS-parity).
--
-- ============================================================================================================================
-- *** DEPLOYMENT-ORDER HAZARD — READ BEFORE MERGING / APPLYING TO ANY ENVIRONMENT ***
-- ============================================================================================================================
-- This migration is REFERENCE DATA ONLY (no Java in this change — Data Engineer scope). It seeds a
-- posting_template SALE **version 3** row. `PostingTemplateResolver.resolve(EventKind, Instant)`
-- picks the HIGHEST version whose effective window contains the event's occurred-at date —
-- unconditionally, for EVERY SaleRecorded event, not just ones that touch loyalty or gift cards.
-- Line-loading calls `AccountRole.valueOf(account_role)` on EVERY line of the resolved template
-- BEFORE any zero-amount omission happens (that omission is in JournalPostingService, one layer up).
--
-- The three new posting_template_line account_role values this migration seeds for SALE v3 —
-- `GIFT_CARD_LIABILITY` and `LOYALTY_DISCOUNT` — DO NOT YET EXIST in `AccountRole.java`, and the
-- three new amount_basis tokens — `NET_TENDER`, `GIFT_CARD_TENDER`, `LOYALTY_REDEEMED` — are not yet
-- handled by `JournalPostingService.resolveAmount(String, Money, SaleRecordedEvent)`, and
-- `SaleRecordedEvent` does not yet carry the gift-card/loyalty breakdown fields (ADR 0027 decision 5).
--
-- CONSEQUENCE IF THIS MIGRATION LANDS WITHOUT ITS COMPANION JAVA CHANGE (AccountRole +
-- EventKind#GIFT_CARD_SALE + JournalPostingService#resolveAmount + SaleRecordedEvent breakdown
-- fields) IN THE SAME DEPLOY/BUILD: the moment SALE v3 becomes the effective template, EVERY
-- SaleRecorded consumption — across every company, every vertical, loyalty or not — throws
-- `IllegalArgumentException: No enum constant ... GIFT_CARD_LIABILITY` out of
-- `PostingTemplateResolver.resolve()`, uncaught by `RevenuePostingWriter.postRevenue()`, aborting the
-- @Transactional unit of work for every sale. This is NOT a Phase-4-only blast radius — it stops ALL
-- revenue GL posting fleet-wide, and will fail every finance-service test that posts a SALE event.
--
-- REQUIRED: merge/deploy this migration in the SAME build as the companion Java change. Do not run
-- `flywayMigrate` (or boot a finance-service instance) against this migration on its own. This is the
-- explicit, loud sign-off flag CLAUDE.md calls for on a money-critical, cross-cutting reference-data
-- change — surfaced here and in the task report, not silently mitigated (the ILLUSTRATIVE GL below is
-- the deliverable ADR 0027 Phase 4 asked for; deferring its effective_from would only mask the
-- coordination requirement, not remove it).
-- ============================================================================================================================
--
-- ============================================================================================================================
-- ILLUSTRATIVE PLACEHOLDER — SME-GATED — REPLACE VIA HIGHER-VERSION DATA (same pattern as V13/V17/V25/V28)
-- ============================================================================================================================
-- ADR 0027 decision 4 marks this treatment ILLUSTRATIVE, SME-gated end to end:
--   1. GIFT-CARD SALE is a LIABILITY event, not revenue: `Dr <tender clearing> / Cr GIFT_CARD_LIABILITY`.
--   2. GIFT-CARD REDEMPTION is a TENDER settlement, not a discount: the SALE clearing debit splits
--      into the residual tender leg (NET_TENDER) and the liability draw-down (GIFT_CARD_TENDER).
--      Revenue legs (GROSS_REVENUE / SERVICE_CHARGE_REVENUE / TAX_PAYABLE) are UNCHANGED.
--   3. POINTS REDEMPTION is contra-revenue (`LOYALTY_DISCOUNT`), extending the SALE Dr side exactly
--      as `SALES_DISCOUNT` already does for the promo discount.
--   4. EARN is memo-only — no GL at earn. `LOYALTY_LIABILITY` (accrued points liability) and
--      `GIFT_CARD_BREAKAGE_INCOME` (unredeemed-card income) are seeded DEFINED-BUT-UNUSED — the
--      `SERVICE_CHARGE_PAYABLE` precedent (V17) — for the SME-confirmed model (points valuation +
--      breakage policy is squarely accounting SME territory, not a finance-service default).
--
-- To replace with real rules (NO code change required): insert higher-version posting_template /
-- role_account_map rows exactly as V13's own header describes. Higher-version rows are preferred by
-- the resolver (ORDER BY version DESC), so these version-1/3 illustrative rows remain as an audit
-- trail and do NOT need to be deleted.
-- ============================================================================================================================
--
-- This migration is ADDITIVE (never edit an applied migration; V2 of the SALE template is untouched).
-- Reference-data seeding ONLY — no new tables, no Java. It:
--   1. Widens posting_template.event_kind CHECK to accept GIFT_CARD_SALE.
--   2. Widens posting_template_line.amount_basis CHECK to accept NET_TENDER / GIFT_CARD_TENDER /
--      LOYALTY_REDEEMED.
--   3. Adds four illustrative COA accounts: 2500 (Gift Card Liability), 4030 (Loyalty Redemption
--      Contra-Revenue), 2510 (Loyalty Points Liability, DEFINED-BUT-UNUSED), 4900 (Gift Card
--      Breakage Income, DEFINED-BUT-UNUSED).
--   4. Seeds role_account_map: GIFT_CARD_LIABILITY→2500, LOYALTY_DISCOUNT→4030,
--      LOYALTY_LIABILITY→2510, GIFT_CARD_BREAKAGE_INCOME→4900 (all version 1).
--   5. Seeds a NEW posting_template GIFT_CARD_SALE v1: Dr <tender clearing> (GROSS) / Cr
--      GIFT_CARD_LIABILITY (GROSS).
--   6. Seeds a NEW posting_template SALE v3 (never edits v2): the 7-line breakdown with the
--      gift-card/loyalty legs folded into the clearing debit + a new contra-revenue debit.
--
-- ---------------------------------------------------------------------------
-- RLS-seed mechanics — mirrors V13/V15/V17/V25/V28, NOT the tenant-scoped seed pattern
-- ---------------------------------------------------------------------------
-- chart_of_account, posting_template, posting_template_line, and role_account_map are ALL global
-- reference data (V13's own header: "NOT Auditable, NOT RLS" — the same pattern as mapping_rule from
-- V2). They carry no company_id and no RLS policy, so — unlike a tenant-scoped seed into a
-- FORCE-RLS table (e.g. barbershop-service V1's tax_charge_rule INSERT, which needs `SET LOCAL
-- app.current_tenant = ...` for its WITH CHECK to pass) — this migration needs NO `SET LOCAL`
-- anywhere: every INSERT below targets a table with no RLS policy to satisfy. Every prior finance GL
-- reference-data migration (V13, V15, V17, V25, V28) follows the identical no-SET-LOCAL pattern.

-- ---------------------------------------------------------------------------
-- 1. Widen event_kind CHECK to accept GIFT_CARD_SALE (drop/recreate, per V16/V25/V28).
-- ---------------------------------------------------------------------------
ALTER TABLE posting_template DROP CONSTRAINT ck_posting_template_event_kind;

ALTER TABLE posting_template
    ADD CONSTRAINT ck_posting_template_event_kind
        CHECK (event_kind IN (
            'SALE', 'EXPENSE', 'LABOR', 'SALE_VOID', 'SALE_REFUND',
            'INVOICE_ISSUED', 'PAYMENT_RECEIVED', 'INVOICE_VOID',
            'BILL_POSTED', 'BILL_PAYMENT_MADE', 'BILL_VOID',
            'GIFT_CARD_SALE'));

-- ---------------------------------------------------------------------------
-- 2. Widen amount_basis CHECK to accept the Phase 4 basis tokens (drop/recreate, per V17/V28).
-- ---------------------------------------------------------------------------
-- NET_TENDER        — the residual clearing leg: amount - gift_card_redeemed_minor.
-- GIFT_CARD_TENDER  — the gift-card-settled leg: gift_card_redeemed_minor.
-- LOYALTY_REDEEMED  — the points-redemption contra-revenue leg: loyalty_redeemed_minor.
ALTER TABLE posting_template_line DROP CONSTRAINT ck_template_amount_basis;

ALTER TABLE posting_template_line
    ADD CONSTRAINT ck_template_amount_basis
        CHECK (amount_basis IN (
            'GROSS', 'GROSS_REVENUE', 'DISCOUNT', 'SERVICE_CHARGE', 'TAX', 'NET',
            'NET_TENDER', 'GIFT_CARD_TENDER', 'LOYALTY_REDEEMED'));

-- ---------------------------------------------------------------------------
-- 3. New illustrative COA accounts.
-- ---------------------------------------------------------------------------
-- 2500 — Gift Card Liability (LIABILITY). Credited when a card is sold (GIFT_CARD_SALE), debited as
--         it is redeemed (part of the SALE v3 clearing debit). Outstanding balance = cards owed.
INSERT INTO chart_of_account (account_code, name, account_type)
VALUES ('2500', 'Gift Card Liability (ILLUSTRATIVE — SME-gated)', 'LIABILITY')
ON CONFLICT (account_code) DO NOTHING;

-- 4030 — Loyalty Redemption Contra-Revenue (REVENUE — a contra-revenue debit account, same
--         classification style as 4010 Sales Discount: SME must confirm contra-revenue vs a separate
--         marketing/loyalty expense treatment).
INSERT INTO chart_of_account (account_code, name, account_type)
VALUES ('4030', 'Loyalty Redemption Contra-Revenue (ILLUSTRATIVE — contra-revenue treatment SME-gated)',
        'REVENUE')
ON CONFLICT (account_code) DO NOTHING;

-- 2510 — Loyalty Points Liability (LIABILITY, DEFINED-BUT-UNUSED). ADR 0027 decision 4: earn is
--         memo-only in Phase 4 — no GL entry accrues a points liability at earn time. This account is
--         seeded for completeness, reserved for a future SME-confirmed points-valuation + breakage
--         model (the SERVICE_CHARGE_PAYABLE precedent from V17: defined ahead of use, not wired into
--         any posting template today).
INSERT INTO chart_of_account (account_code, name, account_type)
VALUES ('2510', 'Loyalty Points Liability (ILLUSTRATIVE, DEFINED-BUT-UNUSED — SME-gated)', 'LIABILITY')
ON CONFLICT (account_code) DO NOTHING;

-- 4900 — Gift Card Breakage Income (REVENUE, DEFINED-BUT-UNUSED). Recognising unredeemed / expired
--         gift-card value as income requires an SME-confirmed breakage policy (rate + timing) not yet
--         decided; seeded for completeness only, same DEFINED-BUT-UNUSED posture as 2510 above.
INSERT INTO chart_of_account (account_code, name, account_type)
VALUES ('4900', 'Gift Card Breakage Income (ILLUSTRATIVE, DEFINED-BUT-UNUSED — SME-gated)', 'REVENUE')
ON CONFLICT (account_code) DO NOTHING;

-- ---------------------------------------------------------------------------
-- 4. role_account_map — Phase 4 new roles (version 1, illustrative).
-- ---------------------------------------------------------------------------
INSERT INTO role_account_map
    (id, account_role, gl_account_code, version, uses_illustrative, effective_from, effective_to)
VALUES
    (gen_random_uuid(), 'GIFT_CARD_LIABILITY',        '2500', 1, TRUE, DATE '2000-01-01', DATE '9999-12-31'),
    (gen_random_uuid(), 'LOYALTY_DISCOUNT',            '4030', 1, TRUE, DATE '2000-01-01', DATE '9999-12-31'),
    -- DEFINED-BUT-UNUSED (see the COA comments above) — mapped for completeness, not resolved by any
    -- posting_template line seeded in this migration.
    (gen_random_uuid(), 'LOYALTY_LIABILITY',           '2510', 1, TRUE, DATE '2000-01-01', DATE '9999-12-31'),
    (gen_random_uuid(), 'GIFT_CARD_BREAKAGE_INCOME',   '4900', 1, TRUE, DATE '2000-01-01', DATE '9999-12-31');

-- ---------------------------------------------------------------------------
-- 5. posting_template GIFT_CARD_SALE v1 — a NEW EventKind (ADR 0027 decision 4: a gift-card sale is
--    a liability event, never revenue — this is why it is NOT folded into the SALE template).
-- ---------------------------------------------------------------------------
-- Dr <tender clearing> (GROSS) / Cr GIFT_CARD_LIABILITY (GROSS).
-- Line 1 uses CASH_CLEARING as its role — the SAME tender-routed-override mechanism the SALE
-- template already relies on (ADR 0006 slice 2 / JournalPostingService.buildEntry(...,
-- clearingRoleOverride)): the writer substitutes QRIS_CLEARING / CARD_CLEARING when the gift card was
-- purchased with a digital tender, exactly as it does for a SALE's clearing leg. No new override
-- mechanism is introduced; this template line simply opts into the existing one.
INSERT INTO posting_template (id, event_kind, version, uses_illustrative, effective_from, effective_to)
VALUES (gen_random_uuid(), 'GIFT_CARD_SALE', 1, TRUE, DATE '2000-01-01', DATE '9999-12-31');

INSERT INTO posting_template_line (id, template_id, line_no, account_role, side, amount_basis)
SELECT gen_random_uuid(), pt.id, 1, 'CASH_CLEARING',       'DEBIT',  'GROSS'
  FROM posting_template pt WHERE pt.event_kind = 'GIFT_CARD_SALE' AND pt.version = 1;
INSERT INTO posting_template_line (id, template_id, line_no, account_role, side, amount_basis)
SELECT gen_random_uuid(), pt.id, 2, 'GIFT_CARD_LIABILITY', 'CREDIT', 'GROSS'
  FROM posting_template pt WHERE pt.event_kind = 'GIFT_CARD_SALE' AND pt.version = 1;

-- ---------------------------------------------------------------------------
-- 6. posting_template SALE v3 — a NEW VERSION row (v2 from V17 is untouched, remains the audit trail).
-- ---------------------------------------------------------------------------
-- Illustrative SALE GL entry (Phase 4 — adds gift-card-tender split + loyalty contra-revenue):
--
--   Dr  <tender clearing>       NET_TENDER  (= amount − gift_card_redeemed)      [CASH_CLEARING role,
--                                                                                  tender-routed]
--   Dr  GIFT_CARD_LIABILITY     GIFT_CARD_TENDER (= gift_card_redeemed)
--   Dr  SALES_DISCOUNT          DISCOUNT (if > 0)                     — PROMO-ONLY, unchanged from v2
--   Dr  LOYALTY_DISCOUNT        LOYALTY_REDEEMED (if > 0)             — NEW: points contra-revenue
--   Cr  GROSS_REVENUE           GROSS_REVENUE (= subtotal, before discount/loyalty)
--   Cr  SERVICE_CHARGE_REVENUE  SERVICE_CHARGE (if > 0)
--   Cr  TAX_PAYABLE             TAX (if > 0)
--
-- Zero-amount lines are omitted at posting time (existing JournalPostingService behaviour, unchanged
-- by this migration) — so a legacy sale (no gift card, no loyalty redemption: GIFT_CARD_TENDER=0,
-- LOYALTY_REDEEMED=0) drops those two lines and collapses to EXACTLY the v2 shape: Dr NET_TENDER
-- (= amount, since gift_card_redeemed=0) / Dr SALES_DISCOUNT (if any) / Cr GROSS_REVENUE / Cr
-- SERVICE_CHARGE_REVENUE (if any) / Cr TAX_PAYABLE (if any) — BYTE-IDENTICAL journal entries to v2
-- for every pre-Phase-4 sale, satisfying ADR 0027 decision 5's "every consumer keeps working".
--
-- Balance check, using the ticket-level identity this phase's ck_*_ticket_total_balances migrations
-- enforce at the source (subtotal − discount − loyalty_redeemed + service_charge + tax = amount):
--   Dr side = NET_TENDER + GIFT_CARD_TENDER + DISCOUNT + LOYALTY_REDEEMED
--           = (amount − gift_card_redeemed) + gift_card_redeemed + discount + loyalty_redeemed
--           = amount + discount + loyalty_redeemed
--   Cr side = GROSS_REVENUE + SERVICE_CHARGE + TAX
--           = subtotal + service_charge + tax
--           = (amount + discount + loyalty_redeemed − service_charge − tax) + service_charge + tax
--           = amount + discount + loyalty_redeemed.  ✓ (gift_card_redeemed cancels out of both
--   sides identically to how it never appears in the ticket-pricing identity — a tender-only leg.)
INSERT INTO posting_template (id, event_kind, version, uses_illustrative, effective_from, effective_to)
VALUES (gen_random_uuid(), 'SALE', 3, TRUE, DATE '2000-01-01', DATE '9999-12-31');

-- Line 1: Dr <tender clearing> (NET_TENDER = amount − gift_card_redeemed) — clearing debit, routing
-- is tender-dependent (unchanged override mechanism from v1/v2).
INSERT INTO posting_template_line (id, template_id, line_no, account_role, side, amount_basis)
SELECT gen_random_uuid(), pt.id, 1, 'CASH_CLEARING', 'DEBIT', 'NET_TENDER'
  FROM posting_template pt WHERE pt.event_kind = 'SALE' AND pt.version = 3;

-- Line 2: Dr GIFT_CARD_LIABILITY (GIFT_CARD_TENDER = gift_card_redeemed — zero-omitted when no
-- gift-card tender was used).
INSERT INTO posting_template_line (id, template_id, line_no, account_role, side, amount_basis)
SELECT gen_random_uuid(), pt.id, 2, 'GIFT_CARD_LIABILITY', 'DEBIT', 'GIFT_CARD_TENDER'
  FROM posting_template pt WHERE pt.event_kind = 'SALE' AND pt.version = 3;

-- Line 3: Dr SALES_DISCOUNT (DISCOUNT — promo-only, zero-omitted when no discount). Unchanged from v2.
INSERT INTO posting_template_line (id, template_id, line_no, account_role, side, amount_basis)
SELECT gen_random_uuid(), pt.id, 3, 'SALES_DISCOUNT', 'DEBIT', 'DISCOUNT'
  FROM posting_template pt WHERE pt.event_kind = 'SALE' AND pt.version = 3;

-- Line 4: Dr LOYALTY_DISCOUNT (LOYALTY_REDEEMED — points contra-revenue, zero-omitted when no
-- redemption). NEW in Phase 4.
INSERT INTO posting_template_line (id, template_id, line_no, account_role, side, amount_basis)
SELECT gen_random_uuid(), pt.id, 4, 'LOYALTY_DISCOUNT', 'DEBIT', 'LOYALTY_REDEEMED'
  FROM posting_template pt WHERE pt.event_kind = 'SALE' AND pt.version = 3;

-- Line 5: Cr GROSS_REVENUE (SUBTOTAL = sum of line totals before discount/loyalty). Unchanged from v2.
INSERT INTO posting_template_line (id, template_id, line_no, account_role, side, amount_basis)
SELECT gen_random_uuid(), pt.id, 5, 'GROSS_REVENUE', 'CREDIT', 'GROSS_REVENUE'
  FROM posting_template pt WHERE pt.event_kind = 'SALE' AND pt.version = 3;

-- Line 6: Cr SERVICE_CHARGE_REVENUE (SERVICE_CHARGE — zero-omitted when none). Unchanged from v2.
INSERT INTO posting_template_line (id, template_id, line_no, account_role, side, amount_basis)
SELECT gen_random_uuid(), pt.id, 6, 'SERVICE_CHARGE_REVENUE', 'CREDIT', 'SERVICE_CHARGE'
  FROM posting_template pt WHERE pt.event_kind = 'SALE' AND pt.version = 3;

-- Line 7: Cr TAX_PAYABLE (TAX — zero-omitted when none). Unchanged from v2.
INSERT INTO posting_template_line (id, template_id, line_no, account_role, side, amount_basis)
SELECT gen_random_uuid(), pt.id, 7, 'TAX_PAYABLE', 'CREDIT', 'TAX'
  FROM posting_template pt WHERE pt.event_kind = 'SALE' AND pt.version = 3;

-- ---------------------------------------------------------------------------
-- SME CONFIRMATION REQUIRED (do not remove this comment until confirmed):
-- ---------------------------------------------------------------------------
-- A. Gift-card liability account (2500) and loyalty contra-revenue account (4030) must be mapped to
--    the client's real SAK-EMKM COA.
-- B. Points valuation + breakage policy (rate, timing, "significant vs remote" recognition test)
--    determines whether/when LOYALTY_LIABILITY (2510) and GIFT_CARD_BREAKAGE_INCOME (4900) are ever
--    wired into a posting template — deliberately NOT done in this migration (ADR 0027 decision 4).
-- C. The SALE_VOID / SALE_REFUND templates (V16) are NOT updated by this migration to reverse the
--    gift-card/loyalty legs — same deferred posture V17's footer already recorded for
--    DISCOUNT/SERVICE_CHARGE/TAX reversal; a future SaleVoided/SaleRefunded schema evolution carrying
--    the full breakdown would be needed for exact per-leg reversal.
-- D. *** This migration's SALE v3 template line account roles (GIFT_CARD_LIABILITY, LOYALTY_DISCOUNT)
--    and amount_basis tokens (NET_TENDER, GIFT_CARD_TENDER, LOYALTY_REDEEMED) require a companion
--    Java change before this migration is safe to run anywhere SALE events are processed — see the
--    DEPLOYMENT-ORDER HAZARD banner at the top of this file. ***
-- ============================================================================================================================
