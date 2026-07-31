package id.co.nativeapp.restaurant.selforderaccess.domain;

/**
 * Thrown when a caller whose role set does not include {@code owner}/{@code manager} attempts a
 * self-order-access management write ({@code GET}/{@code POST /rotate} — both are staff-facing,
 * dashboard-authenticated endpoints, never anonymous). Maps to {@code 403 Forbidden} via {@code
 * config.SelfOrderAdvice}.
 *
 * <p>Mirrors the fleet's {@code ManualDiscountForbiddenException} empty-roles-pass semantics: an
 * EMPTY role set is let through (the {@code X-Roles} header only exists behind the gateway, so a
 * real cashier token is always denied; a headerless request is the gateway-less dev recipe or a
 * direct service-layer test).
 */
public class SelfOrderAccessForbiddenException extends RuntimeException {

  public SelfOrderAccessForbiddenException() {
    super("Self-order QR access management requires the owner or manager role");
  }
}
