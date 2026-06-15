-- finance-service V9 (P3d SEAM 3b — accounting Critical fix) — the NATIVE balance gate.
--
-- ============================================================================================
-- THE CRITICAL THIS MIGRATION SUPPORTS: the SEAM-3b close swept a member's RAW functional-currency
-- imbalance into the flagged CTA reserve (mis-labelled a translation adjustment) and wrote CLOSED.
-- The simplified translation policy ASSUMES each member trial balance already sums to zero in its
-- OWN functional currency, so the only post-translation residual is the legitimate closing-vs-average
-- FX effect. Nothing validated that assumption, and TrialBalancePublished.reconciled was decoded but
-- never persisted, so the close could not consult it.
--
-- This migration persists the reconciled flag onto group_trial_balance (so the close can HOLD an
-- unreconciled member) and adds the MEMBER_TRIAL_BALANCE_UNBALANCED hold state to consolidation_
-- summary. The native-residual computation itself is in GroupCloseWriter (it runs BEFORE translation,
-- per member, in the member's functional currency).
-- ============================================================================================

-- ---------------------------------------------------------------------------
-- group_trial_balance — persist the member's published reconciled flag
-- ---------------------------------------------------------------------------
-- reconciled mirrors TrialBalancePublished.reconciled: whether the member's books balanced (debits ==
-- credits in its functional currency) before publish. The close HOLDs a member that reported
-- reconciled = false (MEMBER_TRIAL_BALANCE_UNBALANCED), exactly like the member-completeness gate —
-- never consolidate (and silently CTA-absorb) a member that says its own books do not balance.
--
-- DEFAULT true backfills any pre-existing SEAM-2 row to the historical assumption (the 3a/3b path ran
-- as if every ingested member reconciled). NOT NULL after the backfill so every future row carries an
-- explicit value mirrored from the event.
ALTER TABLE group_trial_balance
    ADD COLUMN reconciled BOOLEAN NOT NULL DEFAULT true;

-- ---------------------------------------------------------------------------
-- consolidation_summary — add the MEMBER_TRIAL_BALANCE_UNBALANCED hold state
-- ---------------------------------------------------------------------------
-- MEMBER_TRIAL_BALANCE_UNBALANCED — at least one active member's trial balance does NOT reconcile in
-- its functional currency (non-zero signed double-entry residual), or it was published reconciled =
-- false. A native imbalance is NOT a translation (closing-vs-average) effect, so it must NOT be swept
-- into the CTA reserve; the close HOLDs in this state (no consolidation, no CTA sweep, no CLOSED).
ALTER TABLE consolidation_summary
    DROP CONSTRAINT ck_consolidation_summary_state;
ALTER TABLE consolidation_summary
    ADD CONSTRAINT ck_consolidation_summary_state
        CHECK (state IN ('CLOSED', 'PENDING_MEMBERS', 'MULTI_CURRENCY_UNSUPPORTED',
                         'MEMBER_TRIAL_BALANCE_UNBALANCED', 'INTERCOMPANY_UNRECONCILED',
                         'PENDING_MEMBERS_WARMING_UP', 'SUPERSEDED'));
