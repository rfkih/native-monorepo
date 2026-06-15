package id.co.nativeapp.finance.consolidation;

/**
 * The kind of a {@link ConsolidationLedgerEntry} (P3d SEAM 3a + 3b).
 *
 * <ul>
 *   <li>{@link #ELIMINATION} — an intercompany contra that nets a matched related-party trade to
 *       zero in the consolidated books (a member's intercompany revenue is cancelled against the
 *       counterparty member's intercompany expense, so internal trade does not inflate the group).
 *   <li>{@link #ADJUSTMENT} — a manual consolidation adjustment. Modelled for completeness; SEAM
 *       3a's automatic pass posts only ELIMINATIONs (and their REVERSALs), so no ADJUSTMENT is
 *       produced here, but the entry_type and the reversal machinery generalise to it.
 *   <li>{@link #REVERSAL} — a supersession contra negating a prior PRIMARY entry append-only when a
 *       higher {@code close_run_seq} re-closes the period (#23 analog).
 *   <li>{@link #CTA} — (SEAM 3b, SIMPLIFIED) the translation-reserve balancing entry. Translating
 *       balance-sheet items at the CLOSING rate and P&amp;L items at the AVERAGE rate leaves the
 *       translated trial balance not summing to zero; this entry posts that residual to the flagged
 *       {@code 3950-CTA-TRANSLATION-RESERVE} account so the consolidated set BALANCES. It is NOT
 *       the full IAS-21 CTA (which needs opening balances + historical-rate equity — the deferred
 *       SME gate); it is a flagged balancing device under the simplified translation policy. See
 *       {@link SimplifiedTranslationPolicy}.
 *   <li>{@link #FX_TRANSLATION_DIFF} — (SEAM 3b, SIMPLIFIED) the cross-currency intercompany
 *       residual. When a cross-currency IC pair's two legs are each translated into the reporting
 *       currency (AVERAGE) and eliminated, the difference between the two translated amounts is
 *       posted here (flagged) rather than silently absorbed.
 * </ul>
 */
public enum ConsolidationEntryType {
  ELIMINATION,
  ADJUSTMENT,
  REVERSAL,
  CTA,
  FX_TRANSLATION_DIFF
}
