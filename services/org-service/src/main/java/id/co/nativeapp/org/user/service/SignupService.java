package id.co.nativeapp.org.user.service;

import id.co.nativeapp.org.company.domain.CountryDefaults;
import id.co.nativeapp.org.company.dto.CreateCompanyCommand;
import id.co.nativeapp.org.company.service.CompanyService;
import id.co.nativeapp.org.user.dto.SignupRequest;
import id.co.nativeapp.org.user.dto.SignupResponse;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Orchestrates the public sign-up flow: email pre-check → create Keycloak owner user (tagged with a
 * pre-generated tenant id) + assign role → create company (new tenant) under that id.
 *
 * <p><strong>NOT {@code @Transactional}.</strong> Like {@link CompanyService}, this service
 * orchestrates across two subsystems (the org-service DB and Keycloak). Making it transactional
 * would be meaningless for the Keycloak calls (they are outside the DB transaction), and the
 * tenant-bootstrap call inside {@link CompanyService#createCompany} MUST run its own scoped
 * transaction. The only unit that MUST be transactional is the inner {@code CompanyWriter.create}
 * call, which is already {@code @Transactional} via the proxy.
 *
 * <p><strong>Ordering + failure compensation.</strong> The Keycloak user is created FIRST, under a
 * company id generated here (server-side — rule 5), and the company row second. This inversion (vs.
 * the original company-first flow) exists because the two failure residuals are not symmetric:
 *
 * <ul>
 *   <li>Company-first: a Keycloak failure leaves an <em>orphaned tenant row</em> whose bootstrap
 *       outbox events were already published — not compensatable with a simple delete.
 *   <li>Keycloak-first (this flow): a company-creation failure is compensated by ONE idempotent
 *       {@link KeycloakAdminClient#deleteUser} call; nothing was published. Only if the
 *       compensation itself also fails (a double failure) does a residual remain — an orphaned
 *       Keycloak user that blocks the email — and that is logged at ERROR carrying only the
 *       Keycloak user id (never the email or password).
 * </ul>
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
   * @return a {@link SignupResponse} containing the new {@code companyId}, {@code ownerEmail}, and
   *     whether email verification is required before first login
   * @throws EmailAlreadyExistsException if a Keycloak account for {@code ownerEmail} already exists
   *     (→ {@code 409}); also covers the pre-check race, where nothing is persisted yet
   * @throws KeycloakAdminException if Keycloak is unreachable or returns an unexpected error (→
   *     {@code 502}); when this happens before company creation nothing is persisted
   */
  public SignupResponse signup(SignupRequest request) {
    // Step 0: validate the country and derive the base currency (ADR 0025) BEFORE any Keycloak
    // call — an invalid country must fail with a clean 400 while NOTHING exists yet, never after
    // a user was created (which would spend a compensation on a validation error).
    String country = CountryDefaults.requireValidCountry(request.country());
    String baseCurrency = CountryDefaults.baseCurrencyFor(country);

    // Step 1: pre-check the email in Keycloak so the common duplicate case is rejected cleanly.
    // A race that slips past this is still caught: createUser maps Keycloak's 409 to
    // EmailAlreadyExistsException, and at that point NOTHING has been persisted.
    if (keycloak.usernameOrEmailExists(request.ownerEmail())) {
      throw new EmailAlreadyExistsException(request.ownerEmail());
    }

    // Step 2: generate the new tenant id HERE (server-side — rule 5) so the Keycloak user can be
    // tagged with it before the company exists.
    UUID companyId = UUID.randomUUID();

    // Step 3: create the Keycloak user + owner role FIRST. If any of this fails, nothing has been
    // persisted on our side; a role-assignment failure compensates by deleting the just-created
    // user so the email is not left blocked. First/last name are Keycloak-native fields; the
    // last name is optional (mononyms).
    String keycloakUserId =
        keycloak.createUser(
            request.ownerEmail(),
            request.ownerPassword(),
            request.ownerFirstName().strip(),
            blankToNull(request.ownerLastName()),
            companyId.toString(),
            Instant.now());
    try {
      keycloak.assignRealmRole(keycloakUserId, "owner");

      // Step 4: create the company + first business (new tenant) under the pre-generated id. This
      // calls into CompanyService, which opens a TenantContext scope over that id and delegates to
      // a @Transactional CompanyWriter, exactly as the tenant bootstrap always does. The base
      // currency is the DERIVED one — never anything the client sent.
      companyService.createCompany(
          new CreateCompanyCommand(
              request.companyName(),
              baseCurrency,
              request.defaultLanguage(),
              country,
              blankToNull(request.phone()),
              request.companySize(),
              request.primaryInterest(),
              request.firstBusinessName(),
              request.vertical(),
              request.ownerEmail() // actor = the signing-up owner's email
              ),
          companyId);
    } catch (RuntimeException e) {
      compensateUserCreation(keycloakUserId);
      throw e;
    }

    log.info("signup: new tenant {} created successfully", companyId);
    return new SignupResponse(
        companyId.toString(), request.ownerEmail(), keycloak.emailVerificationRequired());
  }

  /**
   * Compensation: deletes the owner's Keycloak user after a downstream signup step failed. Never
   * throws — the original failure must propagate, not the compensation's. A failed compensation is
   * the accepted double-failure residual: an orphaned Keycloak user that blocks the email, logged
   * at ERROR (user id only — never the email or password) for manual cleanup.
   */
  /** Optional-field normalization: {@code null}/blank → {@code null}, else stripped. */
  private static String blankToNull(String value) {
    if (value == null) {
      return null;
    }
    String stripped = value.strip();
    return stripped.isEmpty() ? null : stripped;
  }

  private void compensateUserCreation(String keycloakUserId) {
    try {
      keycloak.deleteUser(keycloakUserId);
      log.warn("signup: rolled back Keycloak user {} after a failed signup step", keycloakUserId);
    } catch (RuntimeException cleanupFailure) {
      log.error(
          "signup: compensation failed — orphaned Keycloak user {} remains and blocks its email;"
              + " manual cleanup required",
          keycloakUserId,
          cleanupFailure);
    }
  }
}
