package id.co.nativeapp.finance.bank.domain;

/**
 * The reconciliation state of a {@link BankStatementLine} (Phase 3 bank reconciliation, ADR 0016).
 *
 * <ul>
 *   <li>{@link #UNRECONCILED} — imported from the bank statement, not yet matched to the GL.
 *   <li>{@link #RECONCILED} — matched: a balanced transfer {@code JournalEntry} has been posted and
 *       linked via {@link BankStatementLine#getJournalEntryId()}.
 * </ul>
 */
public enum StatementLineStatus {
  UNRECONCILED,
  RECONCILED
}
