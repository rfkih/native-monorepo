package id.co.nativeapp.restaurant.outletref.service;

import id.co.nativeapp.restaurant.config.ActorRolesProvider;
import id.co.nativeapp.restaurant.outletref.domain.OutletNotAssignedException;
import id.co.nativeapp.restaurant.outletref.repository.UserOutletAssignmentRefRepository;
import id.co.nativeapp.tenant.TenantContext;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Phase 5 outlet-scoping enforcement: a cashier may only ring sales at an outlet they are ACTIVELY
 * assigned to. Shared by every sale-recording write path — {@code OrderWriter} ({@code checkout},
 * {@code park}, {@code payParked}) and {@code BillWriter} ({@code open}, {@code payBill}) — so the
 * open-bills flow cannot be used to sidestep the order-path guard.
 *
 * <p><strong>Policy (signed-off):</strong>
 *
 * <ol>
 *   <li>Owner or manager roles → bypass unconditionally (no assignment row required).
 *   <li>Cashier with an ACTIVE assignment for {@code (actor, businessId)} → allow.
 *   <li>Grandfather clause: if the company has ZERO rows in {@code user_outlet_assignment_ref}
 *       (outlet scoping was never adopted by the tenant) → allow (legacy-safe default; preserves
 *       pre-outlet tenants, which only ever ring their implicit first business).
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
   * @throws OutletNotAssignedException when a cashier is not assigned to {@code businessId} and the
   *     tenant has adopted outlet scoping (403 via the RFC-7807 handler)
   */
  public void enforce(UUID businessId) {
    // Owner/manager bypass: no assignment check needed.
    if (rolesProvider.isOwnerOrManager()) {
      return;
    }
    // Only cashiers (and unknown roles) reach here — apply the default-closed check.
    String actor = TenantContext.require().actor();

    // Fast path: active assignment exists.
    if (outletRefRepository.hasActiveAssignment(actor, businessId)) {
      return;
    }

    // Grandfather clause: if the company has never received any assignment events, scoping is not
    // adopted — allow the cashier through (legacy-safe default).
    if (outletRefRepository.countAllForCompany() == 0L) {
      return;
    }

    throw new OutletNotAssignedException(actor, businessId);
  }
}
