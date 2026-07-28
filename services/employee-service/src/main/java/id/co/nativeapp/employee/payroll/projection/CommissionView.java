package id.co.nativeapp.employee.payroll.projection;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Read projection for an employee's commission rules ({@code PERCENT_OF_METRIC} earning rules).
 * Selects only non-PII config columns — never {@code fixed_amount_enc} — so no salary ciphertext is
 * decrypted on this path. Snake_case native-query aliases map to these accessors (CODE-STRUCTURE
 * §3.3).
 */
public interface CommissionView {

  UUID getId();

  String getMetricKey();

  Integer getPercentBasisPoints();

  LocalDate getEffectiveFrom();

  LocalDate getEffectiveTo();
}
