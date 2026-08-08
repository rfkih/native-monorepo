package id.co.nativeapp.security;

import java.util.UUID;

/**
 * The verified operator-session caller's context (ADR 0049 P2) — set as an {@code
 * HttpServletRequest} attribute by {@link OperatorSessionFilter} once an {@code X-Operator-Session}
 * token has passed OFFLINE verification (signature + {@code exp}), and read by {@link
 * OperatorContextProvider} from a vertical's service layer. Mirrors how restaurant-service's {@code
 * SelfOrderPrincipal}/{@code ActorRolesProvider} thread web-tier data into non-controller beans
 * without changing the shared {@code TenantContext} record.
 *
 * <p><strong>Not a tenant source.</strong> {@link #companyId()} is the token's claim, carried here
 * for the CONSUMER to assert against the already-bound {@code TenantContext} (never to bind it) —
 * {@link OperatorSessionFilter} deliberately does not touch {@code TenantContext} itself (ADR 0049
 * P2 §A: "avoids TenantContext filter-ordering fragility"). A consumer that finds a mismatch (wrong
 * company or wrong outlet) must reject the write outright, never silently fall back.
 *
 * @param companyId the tenant the operator-session token was minted for
 * @param businessId the outlet (business unit id) the token was minted for
 * @param operatorUserId the operator's Keycloak subject id ({@code employee.user_id}) — the id a
 *     consumer stamps as the seller / commission-metric subject, never a client-supplied value
 * @param operatorEmployeeId the operator's employee-service {@code employee.id}
 * @param role the operator's role on the assignment that authorized this session
 */
public record OperatorPrincipal(
    String companyId,
    UUID businessId,
    String operatorUserId,
    UUID operatorEmployeeId,
    String role) {

  /** The {@code HttpServletRequest} attribute key {@link OperatorSessionFilter} sets this under. */
  public static final String REQUEST_ATTRIBUTE = "id.co.nativeapp.security.OPERATOR_PRINCIPAL";
}
