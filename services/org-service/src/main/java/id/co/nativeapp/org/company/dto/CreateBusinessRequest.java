package id.co.nativeapp.org.company.dto;

import id.co.nativeapp.security.ApiExceptionHandler;
import jakarta.validation.constraints.NotBlank;

/**
 * Create-business request body: add a business under an existing company. The company id comes from
 * the path ({@code /api/v1/companies/{companyId}/businesses}) and the tenant scope, never the body.
 *
 * <p>A business is ALWAYS created as a root {@code business_unit} with a seeded default outlet (ADR
 * 0012), so there is no {@code type} field; an unknown {@code type} property in an old request body
 * is ignored by deserialization, not rejected. Nested org units (outlets, teams) are created via
 * {@code POST /api/v1/org-units}.
 *
 * <p>The bean-validation constraints are checked by {@code @Valid}: a missing/blank field fails
 * fast with a {@code 400} from {@link ApiExceptionHandler}.
 *
 * @param name the business (org-unit) name
 */
public record CreateBusinessRequest(@NotBlank String name) {}
