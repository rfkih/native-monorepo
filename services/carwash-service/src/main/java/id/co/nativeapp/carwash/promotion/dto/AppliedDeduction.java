package id.co.nativeapp.carwash.promotion.dto;

import id.co.nativeapp.carwash.promotion.domain.PromoRuleType;
import id.co.nativeapp.money.Money;
import java.util.Objects;
import java.util.UUID;

/**
 * One deduction {@link id.co.nativeapp.carwash.promotion.service.PromotionEngineService PromotionEngineService}
 * decided to apply — either a rule-backed deduction (persisted as an {@code applied_promotion} audit
 * row) or the manual discount ({@code ruleId == null}, never persisted there — a manual discount is
 * not a rule). Ported verbatim from restaurant-service.
 *
 * @param ruleId the {@link id.co.nativeapp.carwash.promotion.domain.PromoRule} id, or {@code null}
 *     for the manual discount
 * @param couponId the {@link id.co.nativeapp.carwash.promotion.domain.Coupon} that gated this
 *     deduction, or {@code null} for an automatic rule / the manual discount
 * @param nameSnapshot a human-readable label (the rule's name, or {@code "Manual discount"})
 * @param typeSnapshot the rule's type, or {@code null} for the manual discount
 * @param rateBpSnapshot the rule's basis-point rate, or {@code null} for a fixed-amount rule / the
 *     manual discount
 * @param lineRef the {@code carwash_ticket_line} id this deduction landed on (line-scope rules
 *     only), or {@code null} for a ticket-level rule / the manual discount
 * @param amount the deduction amount — the CLAMPED, final amount after {@link
 *     id.co.nativeapp.carwash.promotion.service.PromotionEngineService PromotionEngineService}'s sequential
 *     clamp (composition rule 5)
 */
public record AppliedDeduction(
    UUID ruleId,
    UUID couponId,
    String nameSnapshot,
    PromoRuleType typeSnapshot,
    Long rateBpSnapshot,
    UUID lineRef,
    Money amount) {

  public AppliedDeduction {
    Objects.requireNonNull(nameSnapshot, "nameSnapshot");
    Objects.requireNonNull(amount, "amount");
  }

  /** Returns a copy of this deduction with a different (typically clamped) amount. */
  public AppliedDeduction withAmount(Money newAmount) {
    return new AppliedDeduction(ruleId, couponId, nameSnapshot, typeSnapshot, rateBpSnapshot, lineRef, newAmount);
  }
}
