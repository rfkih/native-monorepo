package id.co.nativeapp.carwash.promotion.domain;

/**
 * Thrown by {@code PromotionAdminService}/{@code PromotionAdminWriter} when a create/patch request
 * for a {@code promo_rule} or {@code coupon} carries a type/parameter mismatch (e.g. a {@code
 * PERCENT_OFF_ORDER} rule with no {@code rateBp}, or a {@code PERCENT_OFF_LINE} rule with no {@code
 * scopeKind}/{@code scopeRefId}), or when a coupon references an unknown {@code ruleId}. Ported
 * verbatim from restaurant-service.
 *
 * <p>Maps to {@code 422 Unprocessable Entity} via {@code config.PromotionAdvice}.
 */
public class PromoRuleValidationException extends RuntimeException {

  public PromoRuleValidationException(String message) {
    super(message);
  }
}
