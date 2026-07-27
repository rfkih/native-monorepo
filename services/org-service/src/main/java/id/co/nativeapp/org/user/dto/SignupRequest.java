package id.co.nativeapp.org.user.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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
 * <p><strong>Whitelists are enforced HERE, not just in the client.</strong> {@code baseCurrency}
 * and {@code defaultLanguage} are {@code @Pattern}-restricted to the platform's supported sets — a
 * direct API call cannot create a tenant in an unsupported configuration (e.g. an EUR company the
 * finance stack cannot consolidate, or a {@code "xx"} language no locale bundle exists for).
 * Widening a set is a deliberate platform decision (new i18n bundle / FX support), so the whitelist
 * lives with the platform, not the client.
 *
 * <p>The first business is ALWAYS created as the root {@code business_unit} with a seeded default
 * outlet (ADR 0012) — there is no business-type choice; an unknown {@code firstBusinessType}
 * property in an old request body is ignored by deserialization, not rejected.
 *
 * @param companyName the company display name (used as-is in the tenant bootstrap)
 * @param baseCurrency the ISO-4217 base currency code for the new company (immutable once set);
 *     must be one of the platform-supported currencies
 * @param defaultLanguage the default language code for the new company; must be one of the
 *     platform-supported languages
 * @param firstBusinessName the name of the first business (org-unit) to create under the company
 * @param ownerEmail the email address of the owner; becomes the Keycloak username and is checked
 *     for uniqueness before creating the tenant
 * @param ownerPassword the owner's initial password — NEVER logged anywhere in this codebase
 * @param termsAccepted whether the owner accepted the Terms of Service; must be {@code true} —
 *     consent is recorded on the Keycloak user as {@code terms_accepted_at}. Deliberately the
 *     {@code Boolean} wrapper, not the primitive: Jackson 3 enables {@code
 *     FAIL_ON_NULL_FOR_PRIMITIVES} by default, so a body MISSING the field would fail
 *     deserialization (→ 500 via the catch-all) instead of reaching bean validation; with the
 *     wrapper, absent/null → {@code @NotNull} → a clean 400.
 */
public record SignupRequest(
    @NotBlank String companyName,
    @NotBlank @Pattern(regexp = "IDR|USD", message = "unsupported currency") String baseCurrency,
    @NotBlank @Pattern(regexp = "en|id", message = "unsupported language") String defaultLanguage,
    @NotBlank String firstBusinessName,
    @NotBlank @Email String ownerEmail,
    @NotBlank @Size(min = 8, max = 128) String ownerPassword,
    @NotNull @AssertTrue(message = "terms must be accepted") Boolean termsAccepted) {}
