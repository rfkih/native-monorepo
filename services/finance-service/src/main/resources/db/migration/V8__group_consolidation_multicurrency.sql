-- finance-service V8 (P3d SEAM 3b) — MULTI-CURRENCY group consolidation, FLAGGED-SIMPLIFIED.
--
-- ============================================================================================
-- ⚠ SIMPLIFIED, FLAGGED CONSOLIDATION TRANSLATION POLICY — NOT AUDITED / NOT FINAL.
--
-- SEAM 3a closed a group only when EVERY member's base currency equalled the group's reporting
-- currency (a divergence was rejected MULTI_CURRENCY_UNSUPPORTED). SEAM 3b SUPPORTS multi-currency
-- groups via a TRANSLATION step — but as a DELIBERATELY SIMPLIFIED, LOUDLY-FLAGGED policy, NOT full
-- IAS-21:
--
--   * Each member trial-balance line is translated into the group reporting currency by account_type
--     — ASSET/LIABILITY/EQUITY at the CLOSING (period-end) rate, REVENUE/EXPENSE at the AVERAGE
--     (period) rate — via the P3c TranslationService.
--   * Because balance-sheet items translate at CLOSING and P&L items at AVERAGE, the translated trial
--     balance does NOT sum to zero. The residual is the (SIMPLIFIED) translation adjustment: it is
--     posted to an EXPLICITLY-FLAGGED CTA / translation-reserve account (3950-CTA-TRANSLATION-RESERVE)
--     so the consolidated set BALANCES. The close is GATED on the balance-check: after the CTA posts,
--     the translated set must reconcile to zero — the residual is NEVER silently dropped.
--   * A cross-currency intercompany pair eliminates its TRANSLATED legs and posts the cross-currency
--     residual as a flagged FX_TRANSLATION_DIFF entry (never silently absorbed).
--   * Any translated or stub-rate close is loudly flagged: uses_simplified_translation_policy = true
--     (sticky/monotonic) and uses_stub_fx carries the rate provenance (sticky-OR).
--
-- DEFERRED to an accounting SME (a HARD GATE — the same class as DJP/BPJS statutory figures, do NOT
-- invent as production values): full IAS-21 CTA/OCI (CTA = closing net assets minus opening net
-- assets at the opening rate minus average-rate P&L), HISTORICAL-rate equity (not closing-rate), and
-- OPENING-BALANCE roll-forward (cumulative consolidation). The simplified residual-to-reserve
-- mechanism here is a BALANCING device, not the IAS-21 CTA. NEVER present a multi-currency group
-- consolidation produced by this seam as audited / final. See the p3d-consolidation-scope decision.
-- ============================================================================================

-- ---------------------------------------------------------------------------
-- consolidation_summary — the simplified-translation-policy flag (sticky/monotonic)
-- ---------------------------------------------------------------------------
-- uses_simplified_translation_policy is TRUE for any multi-currency / translated close (sticky-OR,
-- like uses_stub_fx / uses_illustrative_rules). A same-currency (3a) close leaves it FALSE — no
-- translation happened, so there is nothing simplified to flag. A future dashboard badges any
-- uses_simplified_translation_policy summary PROVISIONAL ("needs accounting sign-off").
ALTER TABLE consolidation_summary
    ADD COLUMN uses_simplified_translation_policy BOOLEAN NOT NULL DEFAULT false;

-- ---------------------------------------------------------------------------
-- consolidation_ledger — widen entry_type for the two SIMPLIFIED-translation entry kinds
-- ---------------------------------------------------------------------------
-- CTA                 — the SIMPLIFIED translation-reserve balancing entry. Carries the residual of
--                       the translated trial balance (BS at CLOSING, P&L at AVERAGE) so the set
--                       reconciles to zero. Posted to 3950-CTA-TRANSLATION-RESERVE. NOT the IAS-21
--                       CTA (which needs opening balances + historical-rate equity — the #37 gate);
--                       this is a flagged balancing device under the simplified policy.
-- FX_TRANSLATION_DIFF — the cross-currency intercompany residual: when a cross-currency IC pair's two
--                       legs are each translated to the reporting currency (AVERAGE) and eliminated,
--                       the difference between the two translated amounts is posted here (flagged),
--                       never silently absorbed.
--
-- 'FX_TRANSLATION_DIFF' is 19 chars — wider than the V7 VARCHAR(16). Widen the column first, then
-- swap the CHECK to include the two new tokens.
ALTER TABLE consolidation_ledger
    ALTER COLUMN entry_type TYPE VARCHAR(20);
ALTER TABLE consolidation_ledger
    DROP CONSTRAINT ck_consolidation_ledger_entry_type;
ALTER TABLE consolidation_ledger
    ADD CONSTRAINT ck_consolidation_ledger_entry_type
        CHECK (entry_type IN ('ELIMINATION', 'ADJUSTMENT', 'REVERSAL', 'CTA', 'FX_TRANSLATION_DIFF'));

-- A CTA / FX_TRANSLATION_DIFF entry is a PRIMARY posting of the close (it is reversed append-only by a
-- superseding higher close_run_seq, exactly like an ELIMINATION), so posting_role stays
-- PRIMARY/REVERSAL — no widening needed there.

-- ---------------------------------------------------------------------------
-- intercompany_match — widen state for the cross-currency match outcome
-- ---------------------------------------------------------------------------
-- MATCHED_CROSS_CURRENCY — a well-formed P&L pair (one strictly-positive REVENUE + one
--                          strictly-positive EXPENSE, two different members) whose legs are in
--                          DIFFERENT currencies. The native magnitudes are not comparable, so the
--                          close does NOT AMOUNT_MISMATCH-block it; it translates each leg (AVERAGE),
--                          eliminates the translated amounts, and posts the residual between the two
--                          translated legs as a flagged FX_TRANSLATION_DIFF. Non-blocking. ('MATCHED_
--                          CROSS_CURRENCY' is 22 chars — fits VARCHAR(32).)
ALTER TABLE intercompany_match
    DROP CONSTRAINT ck_intercompany_match_state;
ALTER TABLE intercompany_match
    ADD CONSTRAINT ck_intercompany_match_state
        CHECK (state IN ('MATCHED', 'UNMATCHED', 'AMOUNT_MISMATCH', 'MALFORMED',
                         'OUT_OF_SCOPE_BALANCE_SHEET', 'MATCHED_CROSS_CURRENCY'));
