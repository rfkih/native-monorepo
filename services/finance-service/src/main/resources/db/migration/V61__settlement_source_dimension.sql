-- ============================================================================================================================
-- V61 — the settlement SOURCE dimension on the receivable sub-ledger (ADR 0076)
-- ============================================================================================================================
-- ADR 0036 gave marketplace money a per-channel sub-ledger (V44 `platform_receivable`, a sub-ledger
-- of GL 1250). QRIS got none: every QRIS sale lands in one company-wide clearing account, 1901
-- (ADR 0045 / V15), with no record of WHO will pay it out.
--
-- That breaks on a real merchant. A merchant using Shopee's QRIS is paid by Shopee in a SINGLE bank
-- transfer covering both ShopeeFood orders (1250) and QRIS sales at the counter (1901). ADR 0076
-- makes the settlement dimension the PAYER rather than the tender, so one payout can clear both.
--
-- Two columns, because they answer two different questions:
--   * source_kind — WHICH GL ACCOUNT this row's balance lives in. MARKETPLACE rows credit
--     PLATFORM_RECEIVABLE (1250), QRIS rows credit QRIS_CLEARING (1901), CARD rows credit
--     CARD_CLEARING (1902). The AccountRole is DERIVED from this value in one place in the writer,
--     never stored per row — a stored role could drift out of agreement with its own kind.
--   * source_code — WHO PAYS. The grouping key for a payout: 'SHOPEE' owns both the ShopeeFood
--     channel and the Shopee QRIS row, so one settlement clears both.
--
-- ---------------------------------------------------------------------------------------------
-- ROLLING-DEPLOY SAFETY — why `uq_platform_receivable_channel` is left exactly as it is
-- ---------------------------------------------------------------------------------------------
-- The obvious shape would be to widen the unique key to include source_kind so 'SHOPEE' could exist
-- once as MARKETPLACE and once as QRIS. That is NOT done here: the previous IMAGE upserts with
-- `ON CONFLICT (company_id, channel_code, currency)`, so dropping that constraint would break the
-- old image the moment it is rolled back onto this schema — the fleet already learned that a
-- migration whose safety depends on the NEW image is not safe (ADR 0074 rollback lesson).
--
-- Instead the key is untouched and a QRIS row simply carries its own channel_code (the writer
-- namespaces it, e.g. 'QRIS:SHOPEE'), with source_code carrying the payer they share. Additive,
-- and a rollback keeps working.
-- ============================================================================================================================

-- ---------------------------------------------------------------------------
-- 1. source_kind — which GL account the row's balance belongs to.
-- ---------------------------------------------------------------------------
-- DEFAULT 'MARKETPLACE' is correct for every existing row AND for anything the previous image
-- inserts during a rollback window: that image only ever wrote marketplace receivables.
ALTER TABLE platform_receivable
    ADD COLUMN source_kind VARCHAR(16) NOT NULL DEFAULT 'MARKETPLACE';

ALTER TABLE platform_receivable
    ADD CONSTRAINT ck_platform_receivable_source_kind
        CHECK (source_kind IN ('MARKETPLACE', 'QRIS', 'CARD'));

-- ---------------------------------------------------------------------------
-- 2. source_code — who pays this balance out.
-- ---------------------------------------------------------------------------
-- 'UNKNOWN' mirrors the channel_code convention already in this table (V44: 'UNKNOWN' is a VALID
-- routing default, not an error state). It is also the value the previous image would leave behind
-- if it inserted a brand-new channel after this migration ran; the writer heals a MARKETPLACE row
-- whose source_code is still 'UNKNOWN' by adopting its channel_code, so a rollback window cannot
-- strand a balance outside every payout group.
ALTER TABLE platform_receivable
    ADD COLUMN source_code VARCHAR(32) NOT NULL DEFAULT 'UNKNOWN';

-- Backfill: for every row that exists today the payer IS the channel (ShopeeFood is paid by Shopee,
-- GoFood by Gojek), so source_code starts as channel_code.
--
-- platform_receivable is FORCE ROW LEVEL SECURITY and Flyway runs as the table owner with no
-- `app.current_tenant` GUC set — a FORCE-RLS UPDATE would match ZERO rows and pass silently (the
-- fleet's known migration-backfill gotcha, cf. payment-service V6). Drop FORCE for the backfill and
-- restore it immediately.
ALTER TABLE platform_receivable NO FORCE ROW LEVEL SECURITY;

UPDATE platform_receivable
   SET source_code = channel_code
 WHERE source_code = 'UNKNOWN'
   AND channel_code <> 'UNKNOWN';

ALTER TABLE platform_receivable FORCE ROW LEVEL SECURITY;

-- ---------------------------------------------------------------------------
-- 3. The payout-grouping read.
-- ---------------------------------------------------------------------------
-- "What does Shopee still owe me, across every kind?" — the settlement form's opening question and
-- the overdue nudge's input. The existing unique index leads with company_id and cannot serve it.
CREATE INDEX idx_platform_receivable_source
    ON platform_receivable (company_id, source_code, currency);

-- ============================================================================================================================
-- SME CONFIRMATION REQUIRED (carried forward from V44, unchanged by this migration):
-- ============================================================================================================================
-- A. 1250 / 1901 / 1902 and 5710 / 5720 remain ILLUSTRATIVE placeholders remapped via
--    role_account_map, no code change. This migration adds no new AccountRole: QRIS_CLEARING and
--    CARD_CLEARING were seeded by V15, QRIS_FEE_EXPENSE by V52.
-- B. Splitting a payout's deduction into commission / transaction fee / merchant-funded promo stays
--    DEFERRED (ADR 0076): `fee = Σ gross − net` is verifiable against the bank statement, while a
--    breakdown needs the platform's own report and its own expense account — merchant-funded promo
--    is marketing spend, not a platform fee, and booking it to 5710 would misstate the P&L.
-- C. v1 attributes QRIS to the company's CONFIGURED source at posting time. A merchant running two
--    QRIS acquirers at once is mis-attributed — detectable as a sub-ledger balance that never
--    settles, and fixable additively by carrying the acquirer on the sale.
-- ============================================================================================================================
