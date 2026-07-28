package id.co.nativeapp.employee.payroll.service;

import id.co.nativeapp.employee.payroll.dto.PayrollSetupResponse;
import id.co.nativeapp.tenant.TenantContext;
import org.springframework.stereotype.Service;

/**
 * Orchestrates the console's one-click payroll bootstrap: delegates to the existing idempotent
 * {@link IllustrativeStatutorySeedWriter} (which owns the tx + RLS GUC and warns loudly — the V3
 * banner and the drift test remain the source of truth for the figures) and returns the refreshed
 * status.
 */
@Service
public class PayrollSetupService {

  private final IllustrativeStatutorySeedWriter seedWriter;
  private final PayrollSetupReader reader;

  public PayrollSetupService(
      IllustrativeStatutorySeedWriter seedWriter, PayrollSetupReader reader) {
    this.seedWriter = seedWriter;
    this.reader = reader;
  }

  /** Seeds the ILLUSTRATIVE PLACEHOLDER catalog + rules for the bound tenant (idempotent). */
  public PayrollSetupResponse seedIllustrative(String baseCurrency) {
    TenantContext.require();
    seedWriter.seed(baseCurrency);
    return reader.status();
  }
}
