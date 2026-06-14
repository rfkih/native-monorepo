package id.co.nativeapp.org.company;

import id.co.nativeapp.security.ApiExceptionHandler;
import jakarta.validation.constraints.NotBlank;
import java.util.UUID;

/**
 * Create-org-unit request body: {@code POST /api/v1/org-units}. Adds a node to the bound company's
 * org tree under an optional parent. The company id comes from the bound tenant scope, never the
 * body (rule 5).
 *
 * <p>The bean-validation constraints are checked by {@code @Valid}: a missing/blank {@code name} or
 * {@code type} fails fast with a {@code 400} from {@link ApiExceptionHandler}. The {@link
 * OrgUnitType} validity of {@code type} and the parent→child hierarchy are enforced by the domain
 * ({@link OrgUnit} / {@link OrgUnitType}), also mapped to {@code 400}.
 *
 * @param name the org-unit name
 * @param type the org-unit type (e.g. {@code "branch"}); validated by {@link OrgUnitType}
 * @param parentId the parent org unit, or {@code null} for a top-level node (a business_unit)
 */
public record CreateOrgUnitRequest(@NotBlank String name, @NotBlank String type, UUID parentId) {}
