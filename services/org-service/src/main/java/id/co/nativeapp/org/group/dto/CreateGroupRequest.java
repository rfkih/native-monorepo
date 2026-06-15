package id.co.nativeapp.org.group.dto;

import id.co.nativeapp.security.ApiExceptionHandler;
import jakarta.validation.constraints.NotBlank;

/**
 * Create-group request body: {@code POST /api/v1/consolidation-groups}. Defines a consolidation
 * group whose <strong>lead is the calling tenant</strong> — the lead company id comes from the
 * bound tenant scope, never the body (rule 5), so a caller cannot define a group led by another
 * company.
 *
 * <p>The bean-validation constraints are checked by {@code @Valid}: a missing/blank {@code name} or
 * {@code reportingCurrency} fails fast with a {@code 400} from {@link ApiExceptionHandler}. The
 * ISO-4217 validity of {@code reportingCurrency} is enforced by the domain ({@link
 * ConsolidationGroup}), also mapped to {@code 400}.
 *
 * @param name the group name
 * @param reportingCurrency the group's reporting (presentation) currency; an ISO-4217 code,
 *     immutable once set
 */
public record CreateGroupRequest(@NotBlank String name, @NotBlank String reportingCurrency) {}
