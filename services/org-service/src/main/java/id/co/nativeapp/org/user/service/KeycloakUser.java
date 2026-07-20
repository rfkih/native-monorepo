package id.co.nativeapp.org.user.service;

import java.util.List;

/**
 * Lightweight representation of a Keycloak user as returned by the Admin REST API.
 *
 * <p>Carries only the fields needed by user management operations: identity ({@code id}, {@code
 * username}, {@code email}), account state ({@code enabled}), the tenant attribution ({@code
 * companyId} — extracted from the {@code company_id} user attribute), and the user's business realm
 * roles ({@code roles}: owner/manager/cashier).
 *
 * <p>This is NOT a domain entity — it is a data carrier from the Keycloak Admin API, analogous to a
 * projection. It lives in the service layer (only used by {@link KeycloakAdminClient} and {@link
 * UserService}).
 *
 * <p><strong>PII hygiene.</strong> The record does not carry the user's password or any credential
 * material. The {@code email} field is carried here because it is identity data needed by the
 * caller, NOT for logging — callers must not log it.
 *
 * @param id the Keycloak user UUID
 * @param username the Keycloak username (same as email for Native users)
 * @param email the user's email address — do NOT log this (PII)
 * @param enabled whether the account is enabled in Keycloak
 * @param companyId the value of the {@code company_id} user attribute, or {@code null} if not set
 * @param roles the business realm roles assigned to this user (owner/manager/cashier); never null,
 *     may be empty if the user has no business roles
 */
public record KeycloakUser(
    String id,
    String username,
    String email,
    boolean enabled,
    String companyId,
    List<String> roles) {

  /** The known business roles used to identify which roles to replace on a role change. */
  static final List<String> BUSINESS_ROLES = List.of("owner", "manager", "cashier");

  public KeycloakUser {
    roles = roles == null ? List.of() : List.copyOf(roles);
  }
}
