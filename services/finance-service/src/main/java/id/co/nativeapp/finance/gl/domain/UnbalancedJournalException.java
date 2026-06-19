package id.co.nativeapp.finance.gl.domain;

/**
 * Thrown by {@link JournalEntry#balanced} when the supplied lines fail the double-entry invariant:
 * fewer than 2 lines, a single-sided violation (a line is both debit and credit), a mixed-currency
 * line set, or {@code Σdebit ≠ Σcredit} or the sum is ≤ 0. An unbalanced entry cannot exist in
 * memory; this exception is always a programming error in the posting-rule framework.
 */
public class UnbalancedJournalException extends RuntimeException {

  public UnbalancedJournalException(String message) {
    super(message);
  }
}
