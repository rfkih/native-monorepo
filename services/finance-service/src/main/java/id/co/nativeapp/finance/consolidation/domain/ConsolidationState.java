package id.co.nativeapp.finance.consolidation.domain;

/**
 * The lifecycle state of a {@link ConsolidationSummary} (P3d SEAM 3a).
 *
 * <p>The close pipeline writes exactly ONE summary per close run, in one of these states. Only
 * {@link #CLOSED} is a finalised consolidation; the others are GATED outcomes that hold the close
 * back (loud, money never silently summed or eliminated against nothing).
 *
 * <ul>
 *   <li>{@link #CLOSED} — every active member's trial balance is ingested, every member's base
 *       currency equals the group's reporting currency, every intercompany leg reconciled, and the
 *       eliminations + roll-up are posted.
 *   <li>{@link #PENDING_MEMBERS} — at least one active member has no ingested {@code
 *       group_trial_balance} for the period; the close is not finalised (do not consolidate a
 *       partial group).
 *   <li>{@link #PENDING_MEMBERS_WARMING_UP} — the {@code group_member} projection is not yet caught
 *       up for the group (a known-pending membership change), so the member set itself is not
 *       trustworthy; HOLD rather than consolidate a stale member set.
 *   <li>{@link #MEMBER_TRIAL_BALANCE_UNBALANCED} — at least one active member's trial balance does
 *       NOT reconcile in its own FUNCTIONAL currency (its signed double-entry residual is
 *       non-zero), or the member published it {@code reconciled = false}. The simplified
 *       translation policy ASSUMES each member book already balances natively (so the only
 *       post-translation residual is the legitimate closing-vs-average FX effect). A native
 *       imbalance is NOT a translation effect and must NOT be swept into the CTA reserve; HOLD
 *       rather than consolidate (and mis-label) raw imbalance as a translation adjustment.
 *   <li>{@link #MULTI_CURRENCY_UNSUPPORTED} — <strong>RETIRED in SEAM 3b.</strong> In 3a a member's
 *       base currency differing from the reporting currency was rejected cleanly here. SEAM 3b now
 *       SUPPORTS multi-currency via the SIMPLIFIED, FLAGGED translation path ({@link
 *       SimplifiedTranslationPolicy}), so the close no longer produces this state. The enum
 *       constant (and its V7 {@code CHECK}) are retained for backward compatibility with any
 *       historical row; no new close writes it.
 *   <li>{@link #INTERCOMPANY_UNRECONCILED} — an UNMATCHED or AMOUNT_MISMATCH intercompany leg
 *       blocks the close (money is never eliminated against nothing).
 *   <li>{@link #SUPERSEDED} — a higher {@code close_run_seq} reversed and replaced this close.
 *       TERMINAL / sticky (mirrors {@code PayrollRunState.SUPERSEDED}): a later event must never
 *       transition the run away from it.
 * </ul>
 */
public enum ConsolidationState {
  CLOSED,
  PENDING_MEMBERS,
  PENDING_MEMBERS_WARMING_UP,
  MULTI_CURRENCY_UNSUPPORTED,
  MEMBER_TRIAL_BALANCE_UNBALANCED,
  INTERCOMPANY_UNRECONCILED,
  SUPERSEDED
}
