package id.co.nativeapp.finance.gl.projection;

/**
 * Read projection for the per-leg void/refund reversal lookup: the columns from {@code
 * journal_line} that the reversal writer needs to negate each original SALE component leg (Phase
 * 2).
 *
 * <p>Backs the native read query on {@link
 * id.co.nativeapp.finance.gl.repository.JournalLineRepository#findLinesByEntryId}. Snake_case
 * native aliases map to these accessors via Spring Data's projection-interface convention.
 * Extracted into the feature's dedicated {@code gl.projection} package so the ArchUnit {@code
 * Projection} layer rule enforces it is accessed only from service and repository layers.
 *
 * <p>Finance NEVER recomputes amounts for reversals — it reads each original line's debit/credit
 * columns and negates them (swap debit ↔ credit). The reversal writer builds a balanced contra
 * entry from these projections inside the same transaction as the void/refund event claim.
 */
public interface JournalLineReversalView {

  /** The line's unique id (for audit / deduplication purposes). */
  java.util.UUID getId();

  /** The account code (FK → {@code chart_of_account.account_code}) for this line. */
  String getAccountCode();

  /**
   * Debit amount in minor units. Exactly one of {@code debitMinor}/{@code creditMinor} is zero; the
   * other is strictly positive. The reversal writer will swap: original debit → contra credit,
   * original credit → contra debit.
   */
  long getDebitMinor();

  /**
   * Credit amount in minor units. Exactly one of {@code debitMinor}/{@code creditMinor} is zero.
   */
  long getCreditMinor();

  /** ISO-4217 currency code for this line (must match the entry currency). */
  String getCurrency();

  /** Line ordering within the entry (1-based). Preserved on the contra entry for audit tracing. */
  int getLineNo();
}
