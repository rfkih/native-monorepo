-- finance-service V52 — QRIS MDR fee expense: chart-of-account + role map (ADR 0045, "Settling 1901
-- to bank" — the bank-reconciliation extension ADR 0038 already promised: the MDR fee books at
-- reconciliation, not at close).
--
-- ============================================================================================================================
-- ILLUSTRATIVE PLACEHOLDER — SME-GATED — REPLACE VIA HIGHER-VERSION DATA (same pattern as
-- V13/V25/V28/V30/V32/V43/V44/V50). V51 (ADR 0042) declared the PRE-EXISTING chart official, but its
-- own text is explicit that the provenance machinery "remains so a future unverified change can flag
-- itself again" — this is exactly that: a BRAND NEW account the business owner has not yet reviewed,
-- so it seeds the same way every prior new role/account addition has (version 1, uses_illustrative =
-- TRUE), not as an already-official row.
-- ============================================================================================================================
-- Bank reconciliation (ADR 0016) gains a QRIS_CLEARING category (ReconciliationWriter): reconciling a
-- QRIS settlement deposit posts
--   Dr BANK (net) + Dr QRIS_FEE_EXPENSE (MDR fee) / Cr QRIS_CLEARING (gross = net + fee)
-- — the fee leg is OMITTED when the fee is zero (no zero-amount journal line; the
-- PlatformSettlementWriter precedent, V44/V45). QRIS_CLEARING itself (role, account 1901) was already
-- seeded by V15 and reused unchanged here; only the new MDR fee leg needs a role + account.
--
-- 5720 verified collision-free: grepping every `INSERT INTO chart_of_account` across V1-V51 (the same
-- check V36/V39/V43/V44/V50 performed) shows the seeded 57xx code space holds only 5700 (Cash Short
-- Expense, V43) and 5710 (Platform Fee Expense, V44) — 5720 is free between it and 5800 (Inventory
-- Shrinkage Expense, V50). 5720 does not collide with any existing account_code.
--
-- Money (rule 8): the fee is an integer in minor units; currency is the entry's ISO-4217 code carried
-- alongside it. Never a float.
-- ============================================================================================================================

-- ---------------------------------------------------------------------------
-- New illustrative COA account.
-- ---------------------------------------------------------------------------
-- 5720 — QRIS MDR Fee Expense (EXPENSE). Debited for the acquirer's merchant-discount-rate fee when
--         a QRIS_CLEARING bank line is reconciled (the fee never touches the sale/refund posting —
--         it books at reconciliation, exactly as ADR 0038 promised).
INSERT INTO chart_of_account (account_code, name, account_type)
VALUES ('5720', 'QRIS MDR Fee Expense (ILLUSTRATIVE — SME-gated)', 'EXPENSE')
ON CONFLICT (account_code) DO NOTHING;

-- ---------------------------------------------------------------------------
-- role_account_map: QRIS_FEE_EXPENSE → 5720 (version 1, illustrative). QRIS_CLEARING → 1901 (V15,
-- superseded official at V51) and BANK → 1000 (V30) are reused unchanged as the other two legs.
-- ---------------------------------------------------------------------------
INSERT INTO role_account_map
    (id, account_role, gl_account_code, version, uses_illustrative, effective_from, effective_to)
VALUES
    (gen_random_uuid(), 'QRIS_FEE_EXPENSE', '5720', 1, TRUE, DATE '2000-01-01', DATE '9999-12-31');

-- ---------------------------------------------------------------------------
-- SME CONFIRMATION REQUIRED (do not remove until confirmed):
-- ---------------------------------------------------------------------------
-- A. 5720 is an illustrative placeholder that must map to the client's real MDR/merchant-fee expense
--    code via a higher-version role_account_map row, no code change.
-- B. feeMinor is CLIENT-SUPPLIED at reconcile time (the operator reads the MDR off the QRIS
--    settlement/acquirer report) — ReconciliationWriter only validates it is non-negative and only
--    accepted for QRIS_CLEARING; it does not recompute or cap it against any rate table.
-- ============================================================================================================================
