package id.co.nativeapp.org.company.service;

/**
 * Thrown when a caller supplies a plan tier value outside {@link
 * id.co.nativeapp.org.company.domain.Company#PLAN_TIERS} (neither {@code FREE} nor {@code FULL}) to
 * {@code PUT /api/v1/companies/current/plan-tier}.
 *
 * <p>Mapped to HTTP {@code 422 Unprocessable Entity} by {@link
 * id.co.nativeapp.org.company.config.PlanTierAdvice} — deliberately distinct from the shared {@code
 * libs/security ApiExceptionHandler}'s generic {@code IllegalArgumentException -> 400} mapping, so
 * this specific, well-formed-but-domain-rejected request gets a stable {@code 422} type URI,
 * matching the convention finance-service uses for a business-rule violation on an otherwise
 * well-formed request (e.g. {@code MismatchedPostingCurrencyException}).
 */
public class InvalidPlanTierException extends RuntimeException {

  public InvalidPlanTierException(String planTier) {
    // Safe: the tier value itself is not PII and is suitable for diagnostics.
    super("Invalid plan tier: '" + planTier + "'. Allowed values are: FREE, FULL");
  }
}
