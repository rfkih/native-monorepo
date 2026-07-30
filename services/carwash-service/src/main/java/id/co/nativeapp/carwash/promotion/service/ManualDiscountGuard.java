package id.co.nativeapp.carwash.promotion.service;

import id.co.nativeapp.carwash.config.ActorRolesProvider;
import id.co.nativeapp.carwash.promotion.domain.ManualDiscountForbiddenException;
import org.springframework.stereotype.Component;

/**
 * Enforces that a positive manual (staff-entered) discount at checkout time requires the {@code
 * owner} or {@code manager} role — ported verbatim from restaurant-service (ADR 0026), adapted to
 * carwash's single checkout write path ({@code TicketWriter.create} — no park/pay-parked/pay-bill
 * flows exist here, ADR 0023 decision 1).
 *
 * <p><strong>Empty-roles-pass semantics</strong> (mirrors carwash's own {@code
 * CatalogService.requireStaffWriteRole}): an EMPTY role set is let through. The {@code X-Roles}
 * header only exists behind the gateway, which always stamps it on an authenticated route — so a
 * real cashier/attendant token IS denied. A headerless request means the gateway-less dev recipe or
 * a direct service-layer test, where {@code OutletAccessGuard}'s grandfather clause applies the same
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
   * @throws ManualDiscountForbiddenException if {@code discountMinor > 0} and the actor's role set is
   *     non-empty and does not include {@code owner}/{@code manager}
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
