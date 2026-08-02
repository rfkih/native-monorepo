package id.co.nativeapp.employee.payroll.projection;

import java.time.LocalDate;

/**
 * Read projection for {@code GET /api/v1/payroll-setup/rules} — the console's rules table. Deliber-
 * ately excludes {@code params_json}/{@code currency} (the console's list view never needs the
 * figures, only the identity/provenance/effective range); the full detail is a separate projection
 * ({@link StatutoryRuleDetailView}) behind {@code GET /rules/{ruleKey}}. Lives in the feature's
 * {@code projection} sub-package (CODE-STRUCTURE §3.3); snake_case native-query aliases map to
 * these accessors.
 */
public interface StatutoryRuleSummaryView {

  String getRuleKey();

  String getRuleVersion();

  String getCalcType();

  String getProvenance();

  LocalDate getEffectiveFrom();

  LocalDate getEffectiveTo();

  String getSourceNote();

  /**
   * Whether this row is the one that actually resolves (a same-day dataset activation can leave a
   * row with {@code effective_to = 9999-12-31} that is nonetheless {@link
   * id.co.nativeapp.employee.payroll.domain.StatutoryRule#supersede deactivated} — see its
   * javadoc). The console dims/badges a row where this is {@code false} rather than showing two
   * seemingly "current" rows for the same {@code rule_key}.
   */
  Boolean getActive();
}
