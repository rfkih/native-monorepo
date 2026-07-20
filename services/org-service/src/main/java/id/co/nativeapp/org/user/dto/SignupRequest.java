package id.co.nativeapp.org.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code POST /api/v1/signup}.
 *
 * <p>All fields are required and validated at the controller edge ({@code @Valid}). The {@code
 * ownerPassword} is a credential and is NEVER logged (rule 6 / HR-6).
 *
 * <p><strong>Server-side validation is authoritative.</strong> This endpoint is PUBLIC and
 * unauthenticated, so the React client's checks are advisory only — a direct API call bypasses
 * them. {@code ownerEmail} must be a syntactically valid email and {@code ownerPassword} must meet
 * a minimum length HERE, not just in the browser (security review, Increment 1). Password
 * complexity beyond length is enforced by Keycloak's realm password policy.
 *
 * @param companyName the company display name (used as-is in the tenant bootstrap)
 * @param baseCurrency the ISO-4217 base currency code for the new company (immutable once set)
 * @param defaultLanguage the default language code for the new company (e.g. {@code "en"} / {@code
 *     "id"})
 * @param firstBusinessName the name of the first business (org-unit) to create under the company
 * @param firstBusinessType the type of the first business (e.g. {@code "outlet"})
 * @param ownerEmail the email address of the owner; becomes the Keycloak username and is checked
 *     for uniqueness before creating the tenant
 * @param ownerPassword the owner's initial password — NEVER logged anywhere in this codebase
 */
public record SignupRequest(
    @NotBlank String companyName,
    @NotBlank String baseCurrency,
    @NotBlank String defaultLanguage,
    @NotBlank String firstBusinessName,
    @NotBlank String firstBusinessType,
    @NotBlank @Email String ownerEmail,
    @NotBlank @Size(min = 8, max = 128) String ownerPassword) {}
