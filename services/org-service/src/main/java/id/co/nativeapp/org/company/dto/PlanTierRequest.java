package id.co.nativeapp.org.company.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code PUT /api/v1/companies/current/plan-tier} (ADR 0044).
 *
 * <p>Only a presence/blank check is bean-validated here (a missing/blank body field is a genuine
 * {@code 400}). The tier WHITELIST ({@code FREE} | {@code FULL}) is deliberately NOT enforced with
 * a {@code @Pattern} — an unrecognized value is a domain-rule violation reported as a stable {@code
 * 422} ({@link id.co.nativeapp.org.company.service.InvalidPlanTierException}), not a
 * bean-validation {@code 400}, so the service layer ({@link
 * id.co.nativeapp.org.company.service.PlanTierWriter}) owns that check against {@code
 * Company.PLAN_TIERS}.
 *
 * <p>The {@code @Size} cap matches the column width and stops an arbitrarily long value from being
 * reflected back in the 422 problem detail.
 *
 * @param planTier the requested tier ({@code FREE} or {@code FULL}, any case, trimmed)
 */
public record PlanTierRequest(@NotBlank @Size(max = 16) String planTier) {}
