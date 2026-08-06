package id.co.nativeapp.org.company.service;

import id.co.nativeapp.org.company.config.ActorRolesProvider;
import id.co.nativeapp.org.company.domain.Company;
import id.co.nativeapp.org.company.repository.CompanyRepository;
import id.co.nativeapp.tenant.RlsAutoApplyAspect;
import id.co.nativeapp.tenant.TenantContext;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the {@code @Transactional} unit of work behind {@code PUT
 * /api/v1/companies/current/plan-tier} (ADR 0044) — the owner-only "Simple mode" toggle.
 *
 * <p>A distinct {@code @Component} bean (not a private method on {@link CompanyService}), the same
 * {@code *Writer} idiom {@link CompanyWriter} / {@link OrgUnitWriter} use, so the transactional
 * method is invoked through the Spring proxy: {@code @Transactional} is load-bearing here because
 * {@link RlsAutoApplyAspect} only sets the {@code app.current_tenant} GUC on a proxied
 * {@code @Transactional} call — a raw {@code TransactionTemplate} (or an un-proxied
 * self-invocation) would run the {@code findById}/mutate/flush below UNBOUND, and RLS fails closed
 * (the row becomes invisible) rather than open.
 *
 * <p><strong>Owner-only, enforced here (not just at the gateway).</strong> The gateway's route for
 * {@code /api/v1/companies/**} carries the coarse {@code DASHBOARD_ROLES} gate (owner AND manager),
 * so a manager token reaches this method — {@link ActorRolesProvider#isOwner()} is the actual
 * authorization boundary for this specific write (ADR 0044 D2: "a company setting should still be
 * owner-gated" even though the tier GATING itself is UI-only).
 */
@Component
public class PlanTierWriter {

  private final CompanyRepository companyRepository;
  private final ActorRolesProvider actorRoles;

  public PlanTierWriter(CompanyRepository companyRepository, ActorRolesProvider actorRoles) {
    this.companyRepository = companyRepository;
    this.actorRoles = actorRoles;
  }

  /**
   * Changes the bound tenant's plan tier.
   *
   * @param requestedTier the requested tier ({@code FREE} or {@code FULL}, any case, trimmed)
   * @throws PlanTierForbiddenException if the caller's role set is non-empty and does not include
   *     {@code owner} (→ {@code 403})
   * @throws InvalidPlanTierException if {@code requestedTier} is not a whitelisted tier (→ {@code
   *     422})
   * @throws NoSuchElementException if no company exists for the bound tenant (→ {@code 404})
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void changePlanTier(String requestedTier) {
    // Empty-roles-pass semantics (fleet ActorRolesProvider convention): an EMPTY role set is let
    // through (headerless = the gateway-less dev recipe or a direct service-layer test); a
    // non-empty set that lacks "owner" is denied.
    if (!actorRoles.currentRoles().isEmpty() && !actorRoles.isOwner()) {
      throw new PlanTierForbiddenException();
    }

    String tenant = TenantContext.require().companyId();
    Company company =
        companyRepository
            .findById(UUID.fromString(tenant))
            .orElseThrow(
                () -> new NoSuchElementException("No company found for the current tenant"));

    try {
      company.changePlanTier(requestedTier);
    } catch (IllegalArgumentException badValue) {
      // The aggregate's own whitelist guard rejected the value with the shared
      // IllegalArgumentException type (matches the country/vertical precedent) — translate to the
      // API-edge-specific 422 so this endpoint reports a stable, non-generic problem type.
      throw new InvalidPlanTierException(requestedTier);
    }

    companyRepository.flush();
  }
}
