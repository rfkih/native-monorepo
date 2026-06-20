-- finance-service V15 — digital-tender clearing accounts (ADR 0006, slice 2).
--
-- ============================================================================================================================
-- ILLUSTRATIVE PLACEHOLDER — SME-GATED — REPLACE VIA HIGHER-VERSION DATA
-- ============================================================================================================================
-- The chart_of_account rows and role_account_map entries seeded here carry the same
-- ILLUSTRATIVE pattern as V13. The real QRIS-settlement AR / card-settlement AR accounts
-- (per SAK-EMKM / Bank-Indonesia reporting) must be provided by an accounting + tax SME.
--
-- To replace with real rules (NO code change required):
--   INSERT INTO role_account_map (id, account_role, gl_account_code, version, uses_illustrative,
--       effective_from, effective_to)
--       VALUES (gen_random_uuid(), 'QRIS_CLEARING', '<real-account>', 2, FALSE, <date>,
--               DATE '9999-12-31');
-- Higher-version rows are preferred by the resolver (ORDER BY version DESC LIMIT 1).
-- ============================================================================================================================
--
-- This migration is ADDITIVE. It:
--   1. Adds two illustrative ASSET clearing accounts to chart_of_account.
--   2. Seeds role_account_map rows so JournalPostingService can resolve QRIS_CLEARING and
--      CARD_CLEARING for sale events whose tender_type is 'QRIS' / 'CARD'.

-- ---------------------------------------------------------------------------
-- 1. New illustrative clearing accounts
-- ---------------------------------------------------------------------------
-- 1901 — QRIS (QR-code) settlement clearing. A pending-collection ASSET:
--         the amount is owed by the QRIS aggregator until daily settlement.
INSERT INTO chart_of_account (account_code, name, account_type)
VALUES ('1901', 'QRIS Settlement Clearing (PLACEHOLDER — replace via SME)', 'ASSET')
ON CONFLICT (account_code) DO NOTHING;

-- 1902 — Card (debit/credit) settlement clearing. Same rationale as QRIS.
INSERT INTO chart_of_account (account_code, name, account_type)
VALUES ('1902', 'Card Settlement Clearing (PLACEHOLDER — replace via SME)', 'ASSET')
ON CONFLICT (account_code) DO NOTHING;

-- ---------------------------------------------------------------------------
-- 2. Role→account mappings (version 1, illustrative)
-- ---------------------------------------------------------------------------
INSERT INTO role_account_map
    (id, account_role, gl_account_code, version, uses_illustrative, effective_from, effective_to)
VALUES
    (gen_random_uuid(), 'QRIS_CLEARING', '1901', 1, TRUE, DATE '2000-01-01', DATE '9999-12-31'),
    (gen_random_uuid(), 'CARD_CLEARING', '1902', 1, TRUE, DATE '2000-01-01', DATE '9999-12-31');
