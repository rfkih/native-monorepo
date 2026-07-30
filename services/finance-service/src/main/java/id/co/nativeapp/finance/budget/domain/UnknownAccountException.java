package id.co.nativeapp.finance.budget.domain;

/**
 * Thrown when a budget line references an {@code account_code} that is not in the chart of
 * accounts. Mapped to {@code 400} — bad client input (a typo'd or non-existent account). Caught in
 * the writer before the DB FK would reject it, so the client gets a clear message naming the
 * account.
 */
public class UnknownAccountException extends RuntimeException {

  public UnknownAccountException(String accountCode) {
    super("unknown account code: " + accountCode);
  }
}
