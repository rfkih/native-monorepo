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
 * <p>Validated at the controller edge ({@code @Valid}). The {@code ownerPassword} is a credential
 * and is NEVER logged (rule 6 / HR-6).
 *
 * <p><strong>Server-side validation is authoritative.</strong> This endpoint is PUBLIC and
 * unauthenticated, so the React client's checks are advisory only — a direct API call bypasses
 * them. {@code ownerEmail} must be a syntactically valid email and {@code ownerPassword} must meet
 * a minimum length HERE, not just in the browser (security review, Increment 1). Password
 * complexity beyond length is enforced by Keycloak's realm password policy.
 *
 * <p><strong>Whitelists are enforced HERE, not just in the client.</strong> {@code country}, {@code
 * defaultLanguage}, {@code vertical}, {@code companySize} and {@code primaryInterest} are
 * {@code @Pattern}-restricted — a direct API call cannot create a tenant in an unsupported
 * configuration. Widening a set is a deliberate platform decision, so the whitelist lives with the
 * platform, not the client.
 *
 * <p><strong>There is deliberately NO {@code baseCurrency} field (ADR 0025).</strong> The base
 * currency is DERIVED from {@code country} by {@code CountryDefaults} ({@code ID → IDR}, else
 * {@code USD}) — Odoo-style. An old request body still carrying {@code baseCurrency} is ignored by
 * deserialization, not rejected (same posture as the removed {@code firstBusinessType}); the
 * server-side derivation always wins.
 *
 * <p>The first business is ALWAYS created as the root {@code business_unit} with a seeded default
 * outlet (ADR 0012).
 *
 * @param companyName the company display name (used as-is in the tenant bootstrap)
 * @param country the ISO 3166-1 alpha-2 country code where the company operates; the base currency
 *     is derived from it and both are immutable once set
 * @param defaultLanguage the default language code for the new company; must be one of the
 *     platform-supported languages
 * @param firstBusinessName the name of the first business (org-unit) to create under the company
 * @param vertical the business vertical of the first business (lowercase {@code restaurant} |
 *     {@code carwash} | {@code barbershop}); drives which POS its outlets get
 * @param ownerFirstName the owner's first (or only) name — becomes the Keycloak user's native
 *     {@code firstName}
 * @param ownerLastName the owner's last name; OPTIONAL — Indonesian mononyms are common, so a
 *     single-name owner simply leaves this out
 * @param phone optional contact phone in a lenient international format; stored on the company,
 *     never verified by SMS
 * @param companySize the employee-count band (Odoo-style funnel field; whitelisted)
 * @param primaryInterest why the owner is signing up (Odoo-style funnel field; whitelisted)
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
    @NotBlank @Pattern(regexp = "[A-Z]{2}", message = "must be an ISO 3166-1 alpha-2 code") String country,
    @NotBlank @Pattern(regexp = "en|id", message = "unsupported language") String defaultLanguage,
    @NotBlank String firstBusinessName,
    @NotBlank @Pattern(regexp = "restaurant|carwash|barbershop", message = "unsupported vertical") String vertical,
    @NotBlank @Size(max = 100) String ownerFirstName,
    @Size(max = 100) String ownerLastName,
    @Pattern(regexp = "\\+?[0-9][0-9 ()\\-]{5,31}", message = "invalid phone number") String phone,
    @NotBlank @Pattern(regexp = "1-5|6-20|21-50|51-250|250\\+", message = "unsupported company size") String companySize,
    @NotBlank @Pattern(
            regexp = "own-company|client-services|student|teacher",
            message = "unsupported interest")
        String primaryInterest,
    @NotBlank @Email String ownerEmail,
    @NotBlank @Size(min = 8, max = 128) String ownerPassword,
    @NotNull @AssertTrue(message = "terms must be accepted") Boolean termsAccepted) {}
