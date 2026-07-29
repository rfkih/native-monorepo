package id.co.nativeapp.finance.bank.domain;

/**
 * Thrown when a reconciliation is attempted against a {@link BankStatementLine} that is not {@link
 * StatementLineStatus#UNRECONCILED} (no double-reconcile). Mapped to HTTP 409 (Conflict) by {@code
 * BankAdvice}.
 */
public class ReconciliationStateException extends RuntimeException {

  public ReconciliationStateException(String message) {
    super(message);
  }
}
