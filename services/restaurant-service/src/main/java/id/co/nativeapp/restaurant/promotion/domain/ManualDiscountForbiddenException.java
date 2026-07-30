package id.co.nativeapp.restaurant.promotion.domain;

/**
 * Thrown when a caller whose role set does not include {@code owner} or {@code manager} attempts an
 * action reserved to those roles: a checkout/pay-parked/pay-bill request carrying a positive manual
 * {@code discountMinor} ({@code ManualDiscountGuard}), or a promotion admin CRUD write ({@code
 * PromotionAdminService}, the same money-routing bar as {@code ManualDiscountGuard} — a promo rule
 * or coupon decides what a checkout discounts and by how much).
 *
 * <p>Maps to {@code 403 Forbidden} via {@code config.PromotionAdvice} (one {@code
 * manual-discount-forbidden} type URI for both call sites — the reader-facing distinction is the
 * {@code detail} message, not a second exception type). Mirrors the carwash {@code
 * StaffProfileWriteForbiddenException} empty-roles-pass semantics: an EMPTY role set is let through
 * (the {@code X-Roles} header only exists behind the gateway, so a real cashier is always denied; a
 * headerless request is the gateway-less dev recipe or a direct service-layer test).
 */
public class ManualDiscountForbiddenException extends RuntimeException {

  public ManualDiscountForbiddenException() {
    this("A manual discount requires the owner or manager role");
  }

  public ManualDiscountForbiddenException(String message) {
    super(message);
  }
}
