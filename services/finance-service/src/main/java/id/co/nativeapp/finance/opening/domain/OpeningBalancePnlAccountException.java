package id.co.nativeapp.finance.opening.domain;

/**
 * An opening-balance line targeted a REVENUE or EXPENSE account (ADR 0037). The opening entry
 * touches balance-sheet accounts only — prior accumulated profit is entered as a credit to Retained
 * Earnings, never by re-posting a P&amp;L. Surfaced as {@code 422}.
 */
public class OpeningBalancePnlAccountException extends RuntimeException {

  public OpeningBalancePnlAccountException(String accountCode, String accountType) {
    super(
        "opening-balance line targets "
            + accountType
            + " account "
            + accountCode
            + " — the opening entry accepts balance-sheet accounts only (ASSET/LIABILITY/EQUITY);"
            + " enter prior profit as a Retained Earnings credit");
  }
}
