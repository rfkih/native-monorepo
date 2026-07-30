package id.co.nativeapp.restaurant.promotion.service;

import id.co.nativeapp.restaurant.config.ActorRolesProvider;
import id.co.nativeapp.restaurant.promotion.domain.ManualDiscountForbiddenException;
import org.springframework.stereotype.Component;

/**
 * Enforces that a positive manual (staff-entered) discount at checkout/pay time requires the {@code
 * owner} or {@code manager} role — shared by every write path that accepts a {@code discountMinor}
 * ({@code OrderWriter.checkout}/{@code payParked}, {@code BillWriter.payBill}) so the guard cannot
 * be bypassed via one path while enforced on another (the {@code OutletAccessGuard} sharing
 * pattern).
 *
 * <p><strong>Empty-roles-pass semantics</strong> (mirrors carwash {@code
 * CatalogService.requireStaffWriteRole}): an EMPTY role set is let through. The {@code X-Roles}
 * header only exists behind the gateway, which always stamps it on an authenticated route — so a
 * real cashier token IS denied. A headerless request means the gateway-less dev recipe or a direct
 * service-layer test, where the {@code OutletAccessGuard} grandfather clause applies the same
 * trust.
 */
@Component
public class ManualDiscountGuard {

  private final ActorRolesProvider actorRoles;

  public ManualDiscountGuard(ActorRolesProvider actorRoles) {
    this.actorRoles = actorRoles;
  }

  /**
   * Enforces the guard for a given {@code discountMinor} request value. A {@code null} or
   * non-positive value is always allowed (no manual discount requested).
   *
   * @throws ManualDiscountForbiddenException if {@code discountMinor > 0} and the actor's role set
   *     is non-empty and does not include {@code owner}/{@code manager}
   */
  public void enforce(Long discountMinor) {
    if (discountMinor == null || discountMinor <= 0) {
      return;
    }
    if (!actorRoles.currentRoles().isEmpty() && !actorRoles.isOwnerOrManager()) {
      throw new ManualDiscountForbiddenException();
    }
  }
}
