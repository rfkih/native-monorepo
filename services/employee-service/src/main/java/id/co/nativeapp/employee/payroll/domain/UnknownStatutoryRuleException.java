package id.co.nativeapp.employee.payroll.domain;

/**
 * {@code PATCH /api/v1/payroll-setup/rules/{ruleKey}} (or the detail GET) named a {@code ruleKey}
 * with no currently-open ({@code effective_to = 9999-12-31}, active) row for the bound tenant.
 * Mapped to {@code 404 Not Found} — an override always supersedes an EXISTING open row; there is
 * nothing to override for a rule key that was never seeded.
 */
public class UnknownStatutoryRuleException extends RuntimeException {

  public UnknownStatutoryRuleException(String ruleKey) {
    super("No currently-open statutory rule for rule_key '" + ruleKey + "'");
  }
}
