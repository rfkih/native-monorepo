package id.co.nativeapp.barbershop.promotion.dto;

import id.co.nativeapp.money.Money;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * The input to {@link id.co.nativeapp.barbershop.promotion.service.PromotionEngineService#evaluate
 * PromotionEngineService.evaluate} — everything the engine needs to compute the collapsed discount for one
 * checkout/quote, and nothing it can mutate (pure evaluation, no writes). Ported verbatim from
 * restaurant-service via carwash-service.
 *
 * @param lines the cart lines (line-scope rule matching)
 * @param currency the ISO-4217 currency of {@code subtotal} and every line (single-currency per
 *     ticket/quote — already enforced upstream by {@code TicketItemReader})
 * @param subtotal the pre-computed ticket subtotal (Σ line totals)
 * @param occurredAt the instant promotions are evaluated AT — checkout time; determines the
 *     effective rule window and the happy-hour day/time gate in each rule's own timezone
 * @param couponCode the caller-supplied coupon code, or {@code null}/blank for none. Expected
 *     pre-normalized (trimmed + uppercased) by the caller, but the engine re-normalizes defensively
 *     before lookup
 * @param manualDiscountMinor the pre-existing staff-entered ticket-level discount, applied LAST in
 *     the composition order; must be &ge; 0
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
