package id.co.nativeapp.org.company.dto;

import id.co.nativeapp.security.ApiExceptionHandler;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * Create-company request body. The company's {@code baseCurrency} (ISO-4217) and {@code
 * defaultLanguage} are set HERE, at creation — never toggled later (CLAUDE.md "Settings live at
 * creation"). {@code firstBusiness} is the first org unit (the business) created together with the
 * company.
 *
 * <p>The bean-validation constraints are checked by {@code @Valid} on the handler param: a
 * missing/blank field fails fast with a {@code 400} from {@link ApiExceptionHandler}. The ISO-4217
 * validity of {@code baseCurrency} is enforced by the domain ({@link Company}), also mapped to
 * {@code 400}.
 *
 * <p>{@code company_id} and the actor are intentionally absent — creating a company bootstraps a
 * NEW tenant whose id is generated server-side, and the actor comes from the request edge (the
 * {@code X-Actor} header / JWT {@code sub}), never trusted from the body (rule 5).
 *
 * @param name the company name
 * @param baseCurrency the ISO-4217 base currency code (immutable once set)
 * @param defaultLanguage the company default language (e.g. {@code "en"}/{@code "id"})
 * @param country optional ISO 3166-1 alpha-2 country code (ADR 0025); {@code null} defaults to
 *     {@code "ID"} at the controller — this in-app path keeps its EXPLICIT {@code baseCurrency} (no
 *     derivation), unlike the public signup
 * @param firstBusiness the first business (a top-level org unit) to create with the company
 */
public record CreateCompanyRequest(
    @NotBlank String name,
    @NotBlank String baseCurrency,
    @NotBlank String defaultLanguage,
    @Pattern(regexp = "[A-Z]{2}", message = "must be an ISO 3166-1 alpha-2 code") String country,
    @NotNull @Valid BusinessRequest firstBusiness) {

  /**
   * Convenience constructor in the pre-country shape (kept so existing Java call sites don't
   * churn); {@code country} defaults to {@code null} → {@code "ID"} at the controller.
   *
   * @param name the company name
   * @param baseCurrency the ISO-4217 base currency code
   * @param defaultLanguage the company default language
   * @param firstBusiness the first business to create with the company
   */
  public CreateCompanyRequest(
      String name, String baseCurrency, String defaultLanguage, BusinessRequest firstBusiness) {
    this(name, baseCurrency, defaultLanguage, null, firstBusiness);
  }

  /**
   * The first-business payload nested in a create-company request (and the body of
   * create-business). A business is ALWAYS created as a root {@code business_unit} with a seeded
   * default outlet (ADR 0012), so there is no {@code type} field; an unknown {@code type} property
   * in an old request body is ignored by deserialization, not rejected.
   *
   * @param name the business / org-unit name
   * @param vertical the business vertical (lowercase {@code restaurant} | {@code carwash} | {@code
   *     barbershop}); required — drives which POS the unit's outlets get
   */
  public record BusinessRequest(
      @NotBlank String name,
      @NotBlank @Pattern(regexp = "restaurant|carwash|barbershop", message = "unsupported vertical") String vertical) {}
}
