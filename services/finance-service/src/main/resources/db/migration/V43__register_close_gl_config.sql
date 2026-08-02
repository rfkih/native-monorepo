-- finance-service V43 — Register-close GL wiring: chart-of-account + role maps (ADR 0036, closing
-- kasir / cash-drawer variance).
--
-- ============================================================================================================================
-- ILLUSTRATIVE PLACEHOLDER — SME-GATED — REPLACE VIA HIGHER-VERSION DATA (same pattern as V13/V25/V28/V30/V32).
-- ============================================================================================================================
-- Adds the two illustrative "selisih kas" (cash variance) accounts for a closed register session and
-- maps the CASH_SHORT_EXPENSE / CASH_OVER_INCOME roles RegisterSessionClosedEvent already documents
-- (finance.register.messaging.RegisterSessionClosedEvent). No posting_template and no event_kind
-- widening: closing a session posts an AD-HOC 2-line JournalEntry built directly by the register-close
-- writer and resolved via RoleAccountResolver — the Bank/Tax/Asset-disposal approach (V30/V32/V36).
-- Finance posts ONLY the signed drawer variance (`over_short_minor`), never the sale/refund totals
-- themselves (revenue is recognized at the sale, not re-touched here):
--   short (overShortMinor < 0) -> Dr CASH_SHORT_EXPENSE (5700) / Cr CASH_CLEARING (1900, V13)
--   over  (overShortMinor > 0) -> Dr CASH_CLEARING (1900, V13) / Cr CASH_OVER_INCOME (4300)
--   zero  (overShortMinor = 0) -> no journal entry (the session is still marked processed)
-- Two roles, not one netted role, mirroring the GAIN_ON_DISPOSAL / LOSS_ON_DISPOSAL precedent (V36):
-- an SME can later remap both CASH_SHORT_EXPENSE and CASH_OVER_INCOME to a single netted account via
-- a higher-version role_account_map row, without a code change.
--
-- 5700 / 4300 verified collision-free: grepping every `INSERT INTO chart_of_account` across
-- V2-V42 (the same check V36 and V39 performed) shows the seeded 57xx code space is empty (56xx's
-- only entry is 5600, V36 Loss on Asset Disposal) and the seeded 43xx code space is empty (42xx's
-- only entry is 4200, V36 Gain on Asset Disposal); neither 5700 nor 4300 collides with any existing
-- account_code.
-- ============================================================================================================================

-- ---------------------------------------------------------------------------
-- New illustrative COA accounts.
-- ---------------------------------------------------------------------------
-- 5700 — Cash Short Expense (Selisih Kas Kurang) (EXPENSE). Debited when a closed register session's
--         physical count comes up SHORT of the expected drawer total.
INSERT INTO chart_of_account (account_code, name, account_type)
VALUES ('5700', 'Cash Short Expense (Selisih Kas Kurang) (ILLUSTRATIVE — SME-gated)', 'EXPENSE')
ON CONFLICT (account_code) DO NOTHING;

-- 4300 — Cash Over Income (Selisih Kas Lebih) (REVENUE — "other income", the same 4100/4200
--         other-income placement as INTEREST_INCOME (V30) and GAIN_ON_DISPOSAL (V36)). Credited when
--         a closed register session's physical count comes up OVER the expected drawer total.
INSERT INTO chart_of_account (account_code, name, account_type)
VALUES ('4300', 'Cash Over Income (Selisih Kas Lebih) (ILLUSTRATIVE — SME-gated)', 'REVENUE')
ON CONFLICT (account_code) DO NOTHING;

-- ---------------------------------------------------------------------------
-- role_account_map: CASH_SHORT_EXPENSE → 5700, CASH_OVER_INCOME → 4300 (version 1, illustrative).
--   CASH_CLEARING → 1900 (V13) is reused as the drawer contra on both legs.
-- ---------------------------------------------------------------------------
INSERT INTO role_account_map
    (id, account_role, gl_account_code, version, uses_illustrative, effective_from, effective_to)
VALUES
    (gen_random_uuid(), 'CASH_SHORT_EXPENSE', '5700', 1, TRUE, DATE '2000-01-01', DATE '9999-12-31'),
    (gen_random_uuid(), 'CASH_OVER_INCOME',   '4300', 1, TRUE, DATE '2000-01-01', DATE '9999-12-31');

-- ---------------------------------------------------------------------------
-- SME CONFIRMATION REQUIRED (do not remove until confirmed):
-- ---------------------------------------------------------------------------
-- A. 5700 and 4300 are illustrative placeholders that must map to the client's real "other
--    expense/income" codes; a real COA may prefer a single netted "cash over/short" account instead
--    of two directional ones — remap both roles to it via a higher-version row, no code change.
-- B. The variance is trusted, TRUSTED service data re-derived by
--    RegisterSessionClosedEvent.assertReconciliationIdentity() (counted - expected, plus the
--    float/sales/refunds identity and sign guards) before it is posted; finance never recomputes the
--    per-session cash sum itself (CASH_CLEARING 1900 is company-wide, not per-session).
-- C. No materiality threshold — every non-zero variance posts, however small. A tolerance band before
--    posting a variance entry is a policy choice, deferred to the SME.
-- ============================================================================================================================
