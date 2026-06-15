package id.co.nativeapp.org.company.dto;

import id.co.nativeapp.security.ApiExceptionHandler;
import jakarta.validation.constraints.NotBlank;

/**
 * Create-business request body: add an org unit under an existing company. The company id comes
 * from the path ({@code /api/v1/companies/{companyId}/businesses}) and the tenant scope, never the
 * body.
 *
 * <p>The bean-validation constraints are checked by {@code @Valid}: a missing/blank field fails
 * fast with a {@code 400} from {@link ApiExceptionHandler}; the {@code OrgUnitType} validity of
 * {@code type} is enforced by the domain ({@link OrgUnitType}), also mapped to {@code 400}.
 *
 * @param name the org-unit name
 * @param type the org-unit type (e.g. {@code "outlet"}); validated by {@link OrgUnitType}
 */
public record CreateBusinessRequest(@NotBlank String name, @NotBlank String type) {}
