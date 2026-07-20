package id.co.nativeapp.org.user.service;

import id.co.nativeapp.org.company.dto.CreateCompanyCommand;
import id.co.nativeapp.org.company.dto.CreateCompanyResult;
import id.co.nativeapp.org.company.service.CompanyService;
import id.co.nativeapp.org.user.dto.SignupRequest;
import id.co.nativeapp.org.user.dto.SignupResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Orchestrates the public sign-up flow: email pre-check → create company (new tenant) → create
 * Keycloak owner user + assign role.
 *
 * <p><strong>NOT {@code @Transactional}.</strong> Like {@link CompanyService}, this service
 * orchestrates across two subsystems (the org-service DB and Keycloak). Making it transactional
 * would be meaningless for the Keycloak calls (they are outside the DB transaction), and the
 * tenant-bootstrap call inside {@link CompanyService#createCompany} MUST run its own scoped
 * transaction. The only unit that MUST be transactional is the inner {@code CompanyWriter.create}
 * call, which is already {@code @Transactional} via the proxy.
 *
 * <p><strong>Failure compensation.</strong> The email pre-check eliminates the common duplicate
 * case cleanly. If Keycloak's {@code createUser} call fails AFTER the company was persisted, the
 * company row remains (an orphaned tenant). This is logged as a WARN carrying only the {@code
 * companyId} (a tenant UUID, not PII) — never the owner email or password. The orphaned tenant is a
 * known residual accepted in this slice (no distributed transaction); a follow-up can implement a
 * compensating cleanup job. The service rethrows as a {@link KeycloakAdminException} (→ {@code
 * 502}) so the caller knows the sign-up did not complete.
 *
 * <p><strong>PII / credential hygiene (HR-6).</strong> The {@code ownerPassword} from the request
 * is passed directly to {@link KeycloakAdminClient#createUser} and is NEVER stored in a local
 * variable that reaches a log statement or an exception message in this class.
 */
@Service
public class SignupService {

  private static final Logger log = LoggerFactory.getLogger(SignupService.class);

  private final CompanyService companyService;
  private final KeycloakAdminClient keycloak;

  public SignupService(CompanyService companyService, KeycloakAdminClient keycloak) {
    this.companyService = companyService;
    this.keycloak = keycloak;
  }

  /**
   * Executes the sign-up flow and returns the identity of the new tenant.
   *
   * @param request the validated sign-up request
   * @return a {@link SignupResponse} containing the new {@code companyId} and {@code ownerEmail}
   * @throws EmailAlreadyExistsException if a Keycloak account for {@code ownerEmail} already exists
   *     (→ {@code 409})
   * @throws KeycloakAdminException if Keycloak is unreachable or returns an unexpected error after
   *     the company was created (→ {@code 502}); the company is left as an orphaned tenant
   */
  public SignupResponse signup(SignupRequest request) {
    // Step 1: pre-check the email in Keycloak BEFORE creating the company, so the common duplicate
    // case is rejected cleanly without an orphaned company row.
    if (keycloak.usernameOrEmailExists(request.ownerEmail())) {
      throw new EmailAlreadyExistsException(request.ownerEmail());
    }

    // Step 2: create the company + first business (new tenant). This calls into CompanyService
    // which opens a TenantContext scope over the freshly generated company id and delegates to a
    // @Transactional CompanyWriter, exactly as the tenant bootstrap always does.
    CreateCompanyResult result =
        companyService.createCompany(
            new CreateCompanyCommand(
                request.companyName(),
                request.baseCurrency(),
                request.defaultLanguage(),
                request.firstBusinessName(),
                request.firstBusinessType(),
                request.ownerEmail() // actor = the signing-up owner's email
                ));

    String companyId = result.company().getId().toString();

    // Step 3: create the Keycloak user and assign the owner role. If this fails AFTER the company
    // was persisted, log a WARN (no PII beyond the company id) and rethrow as 502 — the orphaned
    // tenant is documented as an accepted residual for this slice.
    try {
      String keycloakUserId =
          keycloak.createUser(request.ownerEmail(), request.ownerPassword(), companyId);
      keycloak.assignRealmRole(keycloakUserId, "owner");
    } catch (EmailAlreadyExistsException e) {
      // Race: a duplicate slipped through the pre-check. Treat as a duplicate sign-up; the company
      // row is still the orphaned-tenant residual but we surface a 409 instead of a 502.
      log.warn(
          "signup: race-condition duplicate email after company {} was created; "
              + "orphaned company row remains",
          companyId);
      throw e;
    } catch (KeycloakAdminException e) {
      // Keycloak call failed AFTER the company was persisted.
      log.warn(
          "signup: Keycloak call failed after company {} was created; "
              + "orphaned company row remains — follow-up cleanup required",
          companyId);
      throw e;
    }

    log.info("signup: new tenant {} created successfully", companyId);
    return new SignupResponse(companyId, request.ownerEmail());
  }
}
