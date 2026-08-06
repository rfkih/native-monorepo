package id.co.nativeapp.restaurant.stocktake.domain;

/**
 * A concurrent POS sale/refund adjusted a counted item's stock (bumping its optimistic-lock
 * {@code @Version}) between this stocktake's read and its flush. The whole stocktake transaction
 * rolled back — nothing was persisted, no stock was double-adjusted, no event emitted — so the
 * operator may safely retry (the console re-sends the same stable {@code Idempotency-Key}). Mapped
 * to {@code 409 Conflict} ({@code stocktake-stock-raced}, {@code retryable=true}) by {@code
 * StocktakeAdvice} rather than surfacing as an opaque 500, because a stocktake is expected to run
 * while the outlet is online and taking sales (ADR 0038 phase 3).
 */
public class StocktakeStockRacedException extends RuntimeException {

  public StocktakeStockRacedException(Throwable cause) {
    super("a concurrent stock change raced this stocktake; retry the count", cause);
  }
}
