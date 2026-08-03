package id.co.nativeapp.finance.opening.domain;

/**
 * The opening entry's period is sealed — a {@code tax_filing} row exists for it (ADR 0037) — so
 * opening balances cannot post into it. Practically unreachable (opening balances are normally a
 * company's first posting), but guarded for correctness and surfaced as {@code 422}.
 */
public class OpeningBalanceSealedPeriodException extends RuntimeException {

  public OpeningBalanceSealedPeriodException(String message) {
    super(message);
  }
}
