package id.co.nativeapp.loyalty.earnrule.domain;

/**
 * Thrown when a non-owner/manager caller attempts to write an earn rule — a company-wide,
 * money-routing configuration (it decides how much every future sale earns) — → {@code 403}.
 */
public class EarnRuleWriteForbiddenException extends RuntimeException {

  public EarnRuleWriteForbiddenException() {
    super("Earn-rule writes require the owner or manager role");
  }
}
