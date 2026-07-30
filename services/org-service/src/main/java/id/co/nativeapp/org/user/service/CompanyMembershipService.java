package id.co.nativeapp.org.user.service;

import id.co.nativeapp.org.company.dto.CreateCompanyCommand;
import id.co.nativeapp.org.company.dto.CreateCompanyResult;
import id.co.nativeapp.org.company.service.CompanyService;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Orchestrates creating a company FOR AN AUTHENTICATED LOGIN and binding the login to it — the
 * multi-company "add another business" flow, and the fix for the oidc onboarding loop where {@code
 * POST /api/v1/companies} created a company but never bound its creator, leaving the login unable
 * to ever reach it (ADR 0021).
 *
 * <p><strong>Membership-first with compensating removal</strong> — the mirror of {@link
 * SignupService}'s Keycloak-first + compensating-delete:
 *
 * <ol>
 *   <li>generate the new {@code companyId} server-side (rule 5);
 *   <li>ADD the membership to the creator's Keycloak {@code company_id} attribute first (so a
 *       company row can never exist without its creator being able to reach it);
 *   <li>create the company under that id (the standard tenant bootstrap via {@link
 *       CompanyService});
 *   <li>on any create failure, COMPENSATE by removing the just-added membership — never throwing
 *       from the compensation (the original failure propagates). The double-failure residual is a
 *       dangling membership pointing at a company that does not exist; the {@code /mine} reader
 *       skips unknown ids, so it is benign and self-describing in the logs.
 * </ol>
 *
 * <p>The enlarged membership set reaches the login's token on its next refresh (the multivalued
 * {@code company_id} mapper); the console triggers a silent renew right after creating.
 */
@Service
public class CompanyMembershipService {

  private static final Logger log = LoggerFactory.getLogger(CompanyMembershipService.class);

  private final KeycloakAdminClient keycloak;
  private final CompanyService companyService;

  public CompanyMembershipService(KeycloakAdminClient keycloak, CompanyService companyService) {
    this.keycloak = keycloak;
    this.companyService = companyService;
  }

  /**
   * Creates a company and binds {@code keycloakUserId} (the JWT {@code sub}) to it.
   *
   * @param command the create-company command (carries the request-edge actor)
   * @param keycloakUserId the creator's Keycloak user id — gains the membership
   * @return the new company + first business
   * @throws KeycloakAdminException if the membership add fails (nothing persisted on our side)
   */
  public CreateCompanyResult createCompanyForUser(
      CreateCompanyCommand command, String keycloakUserId) {
    // The new tenant id: generated here, never taken from the request (rule 5) — up front, so the
    // membership can be added BEFORE the company row exists.
    UUID companyId = UUID.randomUUID();

    keycloak.addCompanyToUser(keycloakUserId, companyId.toString());
    try {
      return companyService.createCompany(command, companyId);
    } catch (RuntimeException e) {
      compensateMembershipAdd(keycloakUserId, companyId.toString());
      throw e;
    }
  }

  /**
   * Compensation: removes the just-added membership after the company create failed. Never throws —
   * the original failure must propagate. A failed compensation leaves a dangling membership (an id
   * with no company row), which the {@code /mine} reader skips; logged at ERROR for manual cleanup.
   */
  private void compensateMembershipAdd(String keycloakUserId, String companyId) {
    try {
      keycloak.removeCompanyFromUser(keycloakUserId, companyId);
      log.warn(
          "create-company: rolled back membership {} for Keycloak user {} after a failed create",
          companyId,
          keycloakUserId);
    } catch (RuntimeException cleanupFailure) {
      log.error(
          "create-company: compensation failed — Keycloak user {} keeps a dangling membership {};"
              + " manual cleanup required",
          keycloakUserId,
          companyId,
          cleanupFailure);
    }
  }
}
