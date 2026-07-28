package id.co.nativeapp.employee.employee.domain;

import java.util.UUID;

/**
 * The login (Keycloak subject) is already linked to ANOTHER employee of the same company — a state
 * conflict, mapped to {@code 409}: one login drives one /me surface and one commission attribution,
 * so it can belong to at most one employee. The message names only ids, never PII (rule 6).
 */
public class UserAlreadyLinkedException extends RuntimeException {

  public UserAlreadyLinkedException(String userId, UUID existingEmployeeId) {
    super(
        "Login "
            + userId
            + " is already linked to employee "
            + existingEmployeeId
            + "; unlink it first");
  }
}
