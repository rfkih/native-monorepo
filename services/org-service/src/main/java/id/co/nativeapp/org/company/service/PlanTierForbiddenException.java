package id.co.nativeapp.org.company.service;

/**
 * Thrown when a caller whose role set is non-empty and does not include {@code owner} attempts
 * {@code PUT /api/v1/companies/current/plan-tier} ({@link PlanTierWriter}).
 *
 * <p>Changing a company-wide setting is a genuine authorization decision (ADR 0044 D2), not mere UI
 * curation — the gateway's route for {@code /api/v1/companies/**} is the coarse owner/manager
 * {@code DASHBOARD_ROLES} gate (a manager token reaches this endpoint), so this is the
 * finer-grained server-side owner-only guard, the same role-hierarchy idea {@code
 * InsufficientPrivilegeException} enforces for user management (only an owner may reset an
 * owner/manager login's password).
 *
 * <p>Empty-roles-pass semantics, mirroring the fleet's {@code ActorRolesProvider} convention (e.g.
 * restaurant-service's {@code SalesChannelForbiddenException}): an EMPTY role set is let through —
 * the {@code X-Roles} header only exists behind the gateway, so a real non-owner token is always
 * denied in production; a headerless request is the gateway-less dev recipe or a direct
 * service-layer test.
 *
 * <p>Mapped to HTTP {@code 403 Forbidden} by {@link
 * id.co.nativeapp.org.company.config.PlanTierAdvice}.
 */
public class PlanTierForbiddenException extends RuntimeException {

  public PlanTierForbiddenException() {
    super("Only an owner may change the company's plan tier");
  }
}
