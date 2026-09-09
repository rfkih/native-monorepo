-- finance-service V66 — card MDR fee expense: chart-of-account + role map (ADR 0076), the leg that
-- was missing before a card balance could be settled at all.
--
-- ============================================================================================================================
-- ILLUSTRATIVE PLACEHOLDER — SME-GATED — REPLACE VIA HIGHER-VERSION DATA (the V52 pattern, and every
-- new-account addition before it). V51 declared the PRE-EXISTING chart official while keeping the
-- provenance machinery "so a future unverified change can flag itself again" — this is exactly that:
-- a BRAND NEW account the business owner has not reviewed, seeded version 1 / uses_illustrative TRUE.
-- ============================================================================================================================
-- WHY THIS EXISTS AT ALL
--
-- CARD_CLEARING (1902, seeded V15) has been a ONE-WAY account since the day it was created: every
-- card sale DEBITS it and nothing in the fleet has ever credited it. Bank reconciliation offers only
-- CLEARING / BANK_FEE / INTEREST / QRIS_CLEARING — QRIS got its settlement path in ADR 0045, card
-- never did. So card money accrued and stayed: the first tenant to look had Rp 144.000 sitting in
-- 1902 across a single leg, with no flow in the product able to move it.
--
-- ADR 0076 built the payout flow that can. It already carries the balance (V65 seeded the sub-ledger
-- from the ledger) and already splits a payout's deduction per source onto that source's own fee
-- account. The only thing card lacked was the account to point at — routing an acquirer's fee to
-- PLATFORM_FEE_EXPENSE or QRIS_FEE_EXPENSE would balance perfectly and quietly misstate the P&L.
--
-- DELIBERATELY NOT ALSO A RECONCILIATION CATEGORY. QRIS ended up with TWO writers that credit 1901 —
-- the payout and the QRIS_CLEARING reconciliation category — and doing both for one transfer doubles
-- the fee (recorded as a known risk in ADR 0076). Card has no established habit to preserve, so it
-- gets exactly ONE path: the payout form. Adding the symmetric category would import the same defect
-- into a place that does not have it yet.
--
-- 5730 verified collision-free: the seeded 57xx space holds 5700 (Cash Short, V43), 5710 (Platform
-- Fee, V44), 5720 (QRIS MDR, V52) and then 5800 (Inventory Shrinkage, V50). 5730 is free.
--
-- Money (rule 8): the fee is an integer in minor units, carried with the entry's ISO-4217 code.
-- ============================================================================================================================

-- ---------------------------------------------------------------------------
-- New illustrative COA account.
-- ---------------------------------------------------------------------------
-- 5730 — Card MDR Fee Expense (EXPENSE). Debited for the card acquirer's merchant-discount-rate
--         share of a payout's deduction. Like every other source's fee it is DERIVED, never typed:
--         the merchant enters the net that reached the bank and the split follows each line's gross.
INSERT INTO chart_of_account (account_code, name, account_type)
VALUES ('5730', 'Card MDR Fee Expense (ILLUSTRATIVE — SME-gated)', 'EXPENSE')
ON CONFLICT (account_code) DO NOTHING;

-- ---------------------------------------------------------------------------
-- role_account_map: CARD_FEE_EXPENSE → 5730 (version 1, illustrative). CARD_CLEARING → 1902 (V15)
-- and CASH_CLEARING → 1900 (V13) are reused unchanged as the payout's other two legs.
-- ---------------------------------------------------------------------------
INSERT INTO role_account_map
    (id, account_role, gl_account_code, version, uses_illustrative, effective_from, effective_to)
VALUES
    (gen_random_uuid(), 'CARD_FEE_EXPENSE', '5730', 1, TRUE, DATE '2000-01-01', DATE '9999-12-31');

-- ---------------------------------------------------------------------------
-- SME CONFIRMATION REQUIRED (do not remove until confirmed):
-- ---------------------------------------------------------------------------
-- A. 5730 is an illustrative placeholder that must map to the client's real card-acquirer fee code
--    via a higher-version role_account_map row, no code change.
-- B. A card acquirer's fee is often billed MONTHLY in arrears rather than netted off each payout.
--    A merchant on that arrangement receives the gross and should record the payout with net ==
--    gross (no deduction, no 5730 leg); the monthly invoice is an ordinary expense. Deriving the
--    fee from gross − net keeps both arrangements correct without asking which one applies.
-- ============================================================================================================================
