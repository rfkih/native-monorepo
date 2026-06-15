package id.co.nativeapp.finance.consolidation.dto;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * The authenticated requester reaching the group consolidation surface (P3d SEAM 4b) — the {@code
 * company_id} the JWT bound as the tenant, the acting principal, and the group-level roles the
 * token granted. Built by the controller FROM the verified token / bound {@link
 * id.co.nativeapp.tenant.TenantContext} (never from the request body or path), and passed to {@link
 * GroupConsolidationService} so the security crux runs OFF the token's identity, not a
 * client-chosen value.
 *
 * <p>The {@code companyId} is the tenant the JWT bound — the candidate lead. The service validates
 * it IS the lead of the requested group against the authoritative read model before binding the
 * group scope; a user can never bind a group their company does not lead.
 *
 * @param companyId the JWT-bound tenant ({@code company_id}); the candidate group lead
 * @param actor the acting principal (JWT {@code sub}) — stamped into the close audit columns
 * @param roles the group-level roles the token granted (see {@link GroupConsolidationRole})
 */
public record GroupRequester(UUID companyId, String actor, Set<String> roles) {

  public GroupRequester {
    Objects.requireNonNull(companyId, "companyId");
    Objects.requireNonNull(actor, "actor");
    roles = Set.copyOf(Objects.requireNonNull(roles, "roles"));
  }

  /**
   * Whether the requester holds the given group-level role (see {@link GroupConsolidationRole}).
   */
  public boolean hasRole(String role) {
    return roles.contains(role);
  }
}
