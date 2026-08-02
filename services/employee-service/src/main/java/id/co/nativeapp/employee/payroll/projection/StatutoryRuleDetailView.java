package id.co.nativeapp.employee.payroll.projection;

import java.time.LocalDate;

/**
 * Read projection for {@code GET /api/v1/payroll-setup/rules/{ruleKey}} — the full row INCLUDING
 * {@code params_json}, for the console's override dialog. {@code statutory_rule} carries no PII
 * (rule figures, not employee data), so returning every column here is a legitimate wide projection
 * (CODE-STRUCTURE §3.3), not a {@code SELECT *} of the entity. Lives in the feature's {@code
 * projection} sub-package.
 */
public interface StatutoryRuleDetailView {

  String getRuleKey();

  String getRuleVersion();

  String getCalcType();

  String getParamsJson();

  String getCurrency();

  String getProvenance();

  String getSourceNote();

  LocalDate getEffectiveFrom();

  LocalDate getEffectiveTo();
}
