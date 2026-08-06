package id.co.nativeapp.restaurant.stocktake.domain;

/**
 * A submitted stocktake's lines reference menu items priced in different currencies. All items on
 * one outlet are expected to share the company base currency (CLAUDE.md), so a mismatch is a data
 * integrity signal, not a normal validation failure — mapped to {@code 422 Unprocessable Entity}
 * ({@code stocktake-currency-mismatch}) by {@code StocktakeAdvice}, mirroring {@code
 * InsufficientStockException}'s {@code 422} treatment.
 */
public class StocktakeCurrencyMismatchException extends RuntimeException {

  private final String expectedCurrency;
  private final String actualCurrency;

  public StocktakeCurrencyMismatchException(String expectedCurrency, String actualCurrency) {
    super(
        "stocktake lines carry mismatched currencies: expected "
            + expectedCurrency
            + ", found "
            + actualCurrency);
    this.expectedCurrency = expectedCurrency;
    this.actualCurrency = actualCurrency;
  }

  public String getExpectedCurrency() {
    return expectedCurrency;
  }

  public String getActualCurrency() {
    return actualCurrency;
  }
}
