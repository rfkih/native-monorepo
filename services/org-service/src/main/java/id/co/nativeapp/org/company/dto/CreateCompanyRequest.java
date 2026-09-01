package id.co.nativeapp.org.company.dto;

import id.co.nativeapp.security.ApiExceptionHandler;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * Create-company request body. The company's {@code baseCurrency} (ISO-4217), {@code
 * defaultLanguage} and {@code vertical} are set HERE, at creation — never toggled later (CLAUDE.md
 * "Settings live at creation").
 *
 * <p>The bean-validation constraints are checked by {@code @Valid} on the handler param: a
 * missing/blank field fails fast with a {@code 400} from {@link ApiExceptionHandler}.
 *
 * <p><strong>One name, not two (ADR 0070).</strong> The org tree is flat ({@code company >
 * outlet}), so there is no "first business" to name separately from the company — the bootstrap
 * seeds one outlet named after the company, which the owner renames on the Outlets page. The {@code
 * vertical} moved to the TOP LEVEL of this body (it is a company attribute now, not an org-unit
 * one).
 *
 * <p><strong>Backward compatibility.</strong> The old nested {@code firstBusiness} object is still
 * ACCEPTED: its {@code name} is ignored, and its {@code vertical} is used when the top-level {@code
 * vertical} is absent (see {@link #effectiveVertical()}). That keeps an in-flight old console tab
 * working across the deploy — ADR 0062's version gate is a prompt, not a hard stop — rather than
 * failing its create with a 400. New clients send only the top-level field.
 *
 * <p><strong>Currency is derived from country, not chosen (ADR 0025).</strong> The controller
 * derives {@code baseCurrency} from {@code country} via {@link
 * id.co.nativeapp.org.company.domain.CountryDefaults} and re-derives it authoritatively — any
 * {@code baseCurrency} sent in the body is IGNORED (kept only for backward compatibility with older
 * clients), so an API caller cannot pick a currency either. The same rule already governs the
 * public signup.
 *
 * <p>{@code company_id} and the actor are intentionally absent — creating a company bootstraps a
 * NEW tenant whose id is generated server-side, and the actor comes from the request edge (the
 * {@code X-Actor} header / JWT {@code sub}), never trusted from the body (rule 5).
 *
 * @param name the company name
 * @param baseCurrency IGNORED — the base currency is derived from {@code country} server-side (ADR
 *     0025). Retained as an optional field so pre-derivation clients don't fail validation; any
 *     value is discarded.
 * @param defaultLanguage the company default language (e.g. {@code "en"}/{@code "id"})
 * @param country optional ISO 3166-1 alpha-2 country code (ADR 0025); {@code null} defaults to
 *     {@code "ID"} at the controller and DERIVES the base currency (ID → IDR, else USD)
 * @param vertical the company's business vertical (lowercase {@code restaurant} | {@code carwash} |
 *     {@code barbershop}); required — unless supplied via the legacy {@code firstBusiness} object
 * @param firstBusiness DEPRECATED (ADR 0070) — the legacy nested first-business object. Its name is
 *     ignored; its vertical is the fallback for a body that predates the top-level field.
 */
public record CreateCompanyRequest(
    @NotBlank String name,
    String baseCurrency,
    @NotBlank String defaultLanguage,
    @Pattern(regexp = "[A-Z]{2}", message = "must be an ISO 3166-1 alpha-2 code") String country,
    @Pattern(regexp = "restaurant|carwash|barbershop", message = "unsupported vertical") String vertical,
    @Valid BusinessRequest firstBusiness) {

  /**
   * Convenience constructor in the pre-country shape (kept so existing Java call sites don't
   * churn); {@code country} defaults to {@code null} → {@code "ID"} at the controller.
   *
   * @param name the company name
   * @param baseCurrency the ISO-4217 base currency code
   * @param defaultLanguage the company default language
   * @param vertical the company's business vertical
   */
  public CreateCompanyRequest(
      String name, String baseCurrency, String defaultLanguage, String vertical) {
    this(name, baseCurrency, defaultLanguage, null, vertical, null);
  }

  /**
   * The vertical to create the company with: the top-level field when present, else the legacy
   * nested {@code firstBusiness.vertical}. Never blank once {@link #isVerticalPresent()} has
   * passed.
   */
  public String effectiveVertical() {
    if (vertical != null && !vertical.isBlank()) {
      return vertical;
    }
    return firstBusiness == null ? null : firstBusiness.vertical();
  }

  /**
   * Cross-field rule: a vertical must arrive by ONE of the two routes. Enforced here rather than
   * with {@code @NotBlank} on the field, because either source is legitimate. Discovered by bean
   * validation as the {@code verticalPresent} boolean property — which is also the {@code field}
   * name an API client sees in the RFC-7807 error, so it is named to read sensibly there.
   */
  @AssertTrue(message = "vertical is required (restaurant | carwash | barbershop)")
  public boolean isVerticalPresent() {
    String effective = effectiveVertical();
    return effective != null && !effective.isBlank();
  }

  /**
   * DEPRECATED (ADR 0070) — the legacy nested first-business payload. Accepted so an old client's
   * body still creates a company; only its {@code vertical} is read, and only as a fallback.
   *
   * @param name IGNORED — the seeded outlet is named after the company
   * @param vertical the business vertical, used when the top-level {@code vertical} is absent
   */
  public record BusinessRequest(
      String name,
      @Pattern(regexp = "restaurant|carwash|barbershop", message = "unsupported vertical") String vertical) {}
}
