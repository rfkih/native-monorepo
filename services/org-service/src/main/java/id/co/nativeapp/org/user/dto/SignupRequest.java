package id.co.nativeapp.org.user.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
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
 * <p><strong>Whitelists are enforced HERE, not just in the client.</strong> {@code baseCurrency},
 * {@code defaultLanguage}, and {@code firstBusinessType} are {@code @Pattern}-restricted to the
 * platform's supported sets — a direct API call cannot create a tenant in an unsupported
 * configuration (e.g. an EUR company the finance stack cannot consolidate, or a {@code "xx"}
 * language no locale bundle exists for). Widening a set is a deliberate platform decision (new i18n
 * bundle / FX support), so the whitelist lives with the platform, not the client.
 *
 * @param companyName the company display name (used as-is in the tenant bootstrap)
 * @param baseCurrency the ISO-4217 base currency code for the new company (immutable once set);
 *     must be one of the platform-supported currencies
 * @param defaultLanguage the default language code for the new company; must be one of the
 *     platform-supported languages
 * @param firstBusinessName the name of the first business (org-unit) to create under the company
 * @param firstBusinessType the type of the first business; must be a supported org-unit type
 * @param ownerEmail the email address of the owner; becomes the Keycloak username and is checked
 *     for uniqueness before creating the tenant
 * @param ownerPassword the owner's initial password — NEVER logged anywhere in this codebase
 * @param termsAccepted whether the owner accepted the Terms of Service; must be {@code true} —
 *     consent is recorded on the Keycloak user as {@code terms_accepted_at}
 */
public record SignupRequest(
    @NotBlank String companyName,
    @NotBlank @Pattern(regexp = "IDR|USD", message = "unsupported currency") String baseCurrency,
    @NotBlank @Pattern(regexp = "en|id", message = "unsupported language") String defaultLanguage,
    @NotBlank String firstBusinessName,
    @NotBlank @Pattern(regexp = "business_unit|branch|outlet", message = "unsupported business type") String firstBusinessType,
    @NotBlank @Email String ownerEmail,
    @NotBlank @Size(min = 8, max = 128) String ownerPassword,
    @AssertTrue(message = "terms must be accepted") boolean termsAccepted) {}
