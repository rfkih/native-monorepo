package id.co.nativeapp.employee.operator.domain;

import java.util.UUID;

/**
 * A PIN-verified operator whose employee record is not linked to a console login ({@code
 * employee.user_id} is null). An operator token's {@code operatorUserId} claim MUST be the Keycloak
 * subject id — it is what {@code Sale.sold_by_user_id} / {@code MetricPublished.subject} are
 * stamped from downstream (ADR 0049) — so minting a token with no subject would silently break
 * commission attribution; this is rejected instead. Mapped to {@code 409 Conflict} by the
 * employee-service advice (a state conflict: correct PIN, but the employee cannot yet be an
 * operator). The message names only the employee id (a UUID), never PII (rule 6).
 */
public class OperatorNotLinkedException extends RuntimeException {

  public OperatorNotLinkedException(UUID employeeId) {
    super("Employee " + employeeId + " has no linked console login and cannot be an operator");
  }
}
