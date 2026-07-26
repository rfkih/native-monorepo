package id.co.nativeapp.org.user.dto;

/**
 * Response body for a successful {@code POST /api/v1/signup} ({@code 201 Created}).
 *
 * @param companyId the UUID of the newly created tenant company (generated server-side — rule 5)
 * @param ownerEmail the email address of the newly created owner (echoed for confirmation)
 * @param emailVerificationRequired {@code true} when the realm requires the owner to verify their
 *     email before first login ({@code native.keycloak-admin.require-email-verification}); the
 *     client uses this to show the "check your inbox" success state instead of "sign in now"
 */
public record SignupResponse(
    String companyId, String ownerEmail, boolean emailVerificationRequired) {}
