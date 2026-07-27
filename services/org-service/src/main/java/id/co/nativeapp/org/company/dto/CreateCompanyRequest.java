package id.co.nativeapp.org.company.dto;

import id.co.nativeapp.security.ApiExceptionHandler;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

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
 * @param firstBusiness the first business (a top-level org unit) to create with the company
 */
public record CreateCompanyRequest(
    @NotBlank String name,
    @NotBlank String baseCurrency,
    @NotBlank String defaultLanguage,
    @NotNull @Valid BusinessRequest firstBusiness) {

  /**
   * The first-business payload nested in a create-company request (and the body of
   * create-business). A business is ALWAYS created as a root {@code business_unit} with a seeded
   * default outlet (ADR 0012), so there is no {@code type} field; an unknown {@code type} property
   * in an old request body is ignored by deserialization, not rejected.
   *
   * @param name the business / org-unit name
   */
  public record BusinessRequest(@NotBlank String name) {}
}
