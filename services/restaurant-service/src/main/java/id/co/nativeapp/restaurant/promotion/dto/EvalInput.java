package id.co.nativeapp.restaurant.promotion.dto;

import id.co.nativeapp.money.Money;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * The input to {@link id.co.nativeapp.restaurant.promotion.service.PromotionEngineService#evaluate
 * PromotionEngineService.evaluate} — everything the engine needs to compute the collapsed discount for one
 * checkout/pay/quote, and nothing it can mutate (pure evaluation, no writes).
 *
 * @param lines the cart lines (line-scope rule matching)
 * @param currency the ISO-4217 currency of {@code subtotal} and every line (single-currency per
 *     order/bill/quote — already enforced upstream)
 * @param subtotal the pre-computed order/check subtotal (Σ line totals)
 * @param occurredAt the instant promotions are evaluated AT — checkout/pay time, never park time (a
 *     parked order re-evaluates at pay time); determines the effective rule window and the happy-hour
 *     day/time gate in each rule's own timezone
 * @param couponCode the caller-supplied coupon code, or {@code null}/blank for none. Expected
 *     pre-normalized (trimmed + uppercased) by the caller, but the engine re-normalizes defensively
 *     before lookup
 * @param manualDiscountMinor the pre-existing staff-entered order/check-level discount, applied LAST
 *     in the composition order; must be &ge; 0
 */
public record EvalInput(
    List<EvalLine> lines,
    String currency,
    Money subtotal,
    Instant occurredAt,
    String couponCode,
    long manualDiscountMinor) {

  public EvalInput {
    Objects.requireNonNull(lines, "lines");
    Objects.requireNonNull(currency, "currency");
    Objects.requireNonNull(subtotal, "subtotal");
    Objects.requireNonNull(occurredAt, "occurredAt");
    lines = List.copyOf(lines);
    if (manualDiscountMinor < 0) {
      throw new IllegalArgumentException(
          "manualDiscountMinor must be >= 0, got: " + manualDiscountMinor);
    }
  }
}
