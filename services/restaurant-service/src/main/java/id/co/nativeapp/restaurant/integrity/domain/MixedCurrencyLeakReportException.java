package id.co.nativeapp.restaurant.integrity.domain;

/**
 * Raised when the figures feeding one outlet's leak report do not all share a currency.
 *
 * <p>A company's base currency is immutable from creation (ADR 0025), and every menu price and
 * ingredient cost is denominated in it, so this should be unreachable. It fails CLOSED rather than
 * summing across currencies or silently picking a winner: an estimate of money lost is worthless if
 * two different kinds of money were added together to reach it, and a wrong number here would be
 * acted on. Mirrors finance's {@code MismatchedPostingCurrencyException} posture.
 */
public class MixedCurrencyLeakReportException extends RuntimeException {

  public MixedCurrencyLeakReportException(String expected, String found) {
    super(
        "sales-integrity report mixes currencies: expected "
            + expected
            + " but found "
            + found
            + "; a company's base currency is immutable, so this indicates inconsistent data");
  }
}
