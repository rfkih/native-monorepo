package id.co.nativeapp.org.user.controller;

import id.co.nativeapp.org.user.dto.SignupRequest;
import id.co.nativeapp.org.user.dto.SignupResponse;
import id.co.nativeapp.org.user.service.SignupService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /api/v1/signup} — the <strong>public, unauthenticated</strong> sign-up endpoint.
 *
 * <p>Creates a new company (tenant) together with the owner's Keycloak user in one logical flow. No
 * bearer token is required. Validation errors → {@code 400}; duplicate email → {@code 409};
 * Keycloak unreachable → {@code 502}. Errors are RFC-7807 {@code application/problem+json}.
 *
 * <p>The new company id is generated server-side inside {@link SignupService}; it is never taken
 * from the request body (rule 5).
 */
@Tag(name = "Sign-up", description = "Public self-service sign-up — creates a new company tenant")
@RestController
@RequestMapping("/api/v1/signup")
public class SignupController {

  private final SignupService signupService;

  public SignupController(SignupService signupService) {
    this.signupService = signupService;
  }

  /**
   * Creates a new company + owner account. Public endpoint — no bearer token required.
   *
   * <p>Returns {@code 201 Created} with a {@link SignupResponse} body on success. Errors are
   * RFC-7807 {@code application/problem+json}: {@code 400} for validation, {@code 409} if the email
   * is already registered, {@code 502} if the Keycloak Admin API is unreachable.
   */
  @Operation(
      summary = "Sign up a new company",
      description =
          "Public endpoint — no bearer token required. Creates a new company (tenant) with the"
              + " given base currency, default language, and first business, then registers the"
              + " owner in Keycloak with the 'owner' realm role. Returns 201 with the new"
              + " companyId on success. Errors: 400 (validation), 409 (email already taken),"
              + " 502 (Keycloak unreachable).")
  @PostMapping
  public ResponseEntity<SignupResponse> signup(@Valid @RequestBody SignupRequest request) {
    SignupResponse body = signupService.signup(request);
    return ResponseEntity.status(201).body(body);
  }
}
