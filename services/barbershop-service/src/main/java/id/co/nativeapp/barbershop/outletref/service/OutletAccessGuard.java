package id.co.nativeapp.barbershop.outletref.service;

import id.co.nativeapp.barbershop.config.ActorRolesProvider;
import id.co.nativeapp.barbershop.outletref.domain.OutletNotAssignedException;
import id.co.nativeapp.barbershop.outletref.repository.UserOutletAssignmentRefRepository;
import id.co.nativeapp.tenant.TenantContext;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Outlet-scoping enforcement: an attendant/cashier may only ring a ticket at an outlet they are
 * ACTIVELY assigned to (ported verbatim from carwash-service's {@code outletref} feature — the
 * Phase 5 policy).
 *
 * <p><strong>Policy (signed-off, restaurant-service Phase 5):</strong>
 *
 * <ol>
 *   <li>Owner or manager roles → bypass unconditionally (no assignment row required).
 *   <li>Cashier/attendant with an ACTIVE assignment for {@code (actor, businessId)} → allow.
 *   <li>Grandfather clause: if the company has ZERO rows in {@code user_outlet_assignment_ref}
 *       (outlet scoping was never adopted by the tenant) → allow (legacy-safe default).
 *   <li>Otherwise → {@link OutletNotAssignedException} (403 Forbidden, RFC-7807).
 * </ol>
 *
 * <p>Must run inside the caller's {@code @Transactional} boundary so the {@link
 * id.co.nativeapp.tenant.RlsAutoApplyAspect} GUC is already set — the repository queries are
 * tenant-scoped by RLS (rule 5).
 */
@Component
public class OutletAccessGuard {

  private final UserOutletAssignmentRefRepository outletRefRepository;
  private final ActorRolesProvider rolesProvider;

  public OutletAccessGuard(
      UserOutletAssignmentRefRepository outletRefRepository, ActorRolesProvider rolesProvider) {
    this.outletRefRepository = outletRefRepository;
    this.rolesProvider = rolesProvider;
  }

  /**
   * Enforces the outlet-scoping policy for the current actor against {@code businessId}.
   *
   * @param businessId the outlet (org-unit) being rung
   * @throws OutletNotAssignedException when a cashier/attendant is not assigned to {@code
   *     businessId} and the tenant has adopted outlet scoping (403 via the RFC-7807 handler)
   */
  public void enforce(UUID businessId) {
    // Owner/manager bypass: no assignment check needed.
    if (rolesProvider.isOwnerOrManager()) {
      return;
    }
    // Only cashiers/attendants (and unknown roles) reach here — apply the default-closed check.
    String actor = TenantContext.require().actor();

    // Fast path: active assignment exists.
    if (outletRefRepository.hasActiveAssignment(actor, businessId)) {
      return;
    }

    // Grandfather clause: if the company has never received any assignment events, scoping is not
    // adopted — allow the attendant through (legacy-safe default).
    if (outletRefRepository.countAllForCompany() == 0L) {
      return;
    }

    throw new OutletNotAssignedException(actor, businessId);
  }
}
