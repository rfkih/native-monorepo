package id.co.nativeapp.finance.consolidation.domain;

/**
 * The reconciliation state of an {@link IntercompanyMatch} (P3d SEAM 3a).
 *
 * <ul>
 *   <li>{@link #MATCHED} — both members' related-party P&amp;L legs for an {@code
 *       (intercompany_ref, period)} are present and reconcile as a STRICTLY WELL-FORMED pair:
 *       exactly two legs, between TWO DIFFERENT members, exactly ONE {@code REVENUE}-typed leg +
 *       ONE {@code EXPENSE}-typed leg, BOTH amounts STRICTLY POSITIVE, of EQUAL magnitude (same
 *       currency). The close eliminates the internal trade to zero (the revenue leg from gross
 *       revenue and the expense leg from gross expense, each by its own validated-positive amount).
 *   <li>{@link #UNMATCHED} — only one member reported a leg for the reference; there is nothing to
 *       eliminate it against, so the close is held back.
 *   <li>{@link #AMOUNT_MISMATCH} — both (strictly-positive, one-revenue+one-expense) legs are
 *       present but their magnitudes differ; eliminating would leave a residual, so the close is
 *       held back.
 *   <li>{@link #MALFORMED} — the reference is not a well-formed P&amp;L intercompany pair: it has
 *       MORE THAN TWO legs, both legs belong to the SAME member (a self-deal), the two legs are not
 *       exactly ONE {@code REVENUE}-typed leg + ONE {@code EXPENSE}-typed leg (e.g. two revenue
 *       legs), a leg amount is ZERO or NEGATIVE ({@code amountMinor <= 0} — a sign-blind match
 *       would fabricate net), or the pair MIXES a P&amp;L leg with a balance-sheet leg. Eliminating
 *       such a candidate would mis-state the group (leave internal trade un-removed and/or
 *       fabricate a phantom contra), so the close is held back.
 *   <li>{@link #OUT_OF_SCOPE_BALANCE_SHEET} — BOTH legs are balance-sheet account types
 *       (ASSET/LIABILITY/EQUITY), e.g. a legitimate IC receivable + IC payable pair. 3a
 *       consolidates only the P&amp;L (revenue/expense -&gt; net); a pure balance-sheet IC ref does
 *       NOT affect the P&amp;L net. It is a NON-BLOCKING, out-of-3a-scope SKIP: it is recorded, NOT
 *       eliminated, and does NOT block the close (balance-sheet IC elimination is a future / SEAM
 *       3b concern).
 *   <li>{@link #MATCHED_CROSS_CURRENCY} — (SEAM 3b, SIMPLIFIED) a well-formed P&amp;L pair (exactly
 *       one strictly-positive REVENUE leg + one strictly-positive EXPENSE leg, two different
 *       members) whose two legs are in DIFFERENT currencies. The native magnitudes cannot be
 *       compared for equality, so the close does NOT AMOUNT_MISMATCH-block it; instead it
 *       TRANSLATES each leg into the reporting currency (AVERAGE rate), eliminates the translated
 *       amounts, and posts the residual between the two translated legs as a flagged {@link
 *       ConsolidationEntryType#FX_TRANSLATION_DIFF} entry (never silently absorbed). See {@link
 *       SimplifiedTranslationPolicy}.
 * </ul>
 *
 * <p>{@link #UNMATCHED}, {@link #AMOUNT_MISMATCH} and {@link #MALFORMED} block the close (default
 * block — money is never eliminated against nothing, nor eliminated wrongly). {@link
 * #OUT_OF_SCOPE_BALANCE_SHEET} and {@link #MATCHED_CROSS_CURRENCY} are the NON-blocking non-MATCHED
 * states.
 */
public enum IntercompanyMatchState {
  MATCHED,
  UNMATCHED,
  AMOUNT_MISMATCH,
  MALFORMED,
  OUT_OF_SCOPE_BALANCE_SHEET,
  MATCHED_CROSS_CURRENCY
}
