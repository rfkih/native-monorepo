package id.co.nativeapp.employee.payroll.projection;

import java.time.Instant;
import java.util.UUID;

/**
 * Read projection for the per-period run list ({@code GET /api/v1/payroll-runs?period=}) — the
 * company-level totals only (plaintext non-PII columns; rule 8 Money pairs), never the payslip
 * lines. Lives in the feature's {@code projection} package (CODE-STRUCTURE §3.3); snake_case
 * native-query aliases map to these accessors.
 */
public interface PayrollRunView {

  UUID getId();

  String getPeriod();

  Integer getRunSeq();

  String getStatus();

  String getBaseCurrency();

  Long getGrossTotalMinor();

  Long getEmployeeDeductionTotalMinor();

  Long getEmployerContributionTotalMinor();

  Long getNetTotalMinor();

  Boolean getUsesIllustrativeRules();

  Instant getPostedAt();

  /** The FROZEN per-employee work-input breakdown (Track P Phase P7), {@code "{}"} if none. */
  String getWorkInputsJson();
}
