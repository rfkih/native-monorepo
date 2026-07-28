package id.co.nativeapp.employee.payroll.service;

import id.co.nativeapp.employee.payroll.dto.PayrollSetupResponse;
import id.co.nativeapp.employee.payroll.repository.PayComponentRepository;
import id.co.nativeapp.employee.payroll.repository.StatutoryRuleRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads the tenant's payroll-setup status — whether the pay-component catalog + statutory rules
 * exist and their provenance. RLS-scoped (rule 5): one tenant's seed is invisible to another. The
 * console gates the Payroll tab on this and shows the loud illustrative banner whenever the
 * provenance is not {@code OFFICIAL}.
 */
@Service
public class PayrollSetupReader {

  private final PayComponentRepository payComponentRepository;
  private final StatutoryRuleRepository statutoryRuleRepository;

  public PayrollSetupReader(
      PayComponentRepository payComponentRepository,
      StatutoryRuleRepository statutoryRuleRepository) {
    this.payComponentRepository = payComponentRepository;
    this.statutoryRuleRepository = statutoryRuleRepository;
  }

  /** The bound tenant's setup status ({@code count}/{@code DISTINCT} scalars — no projection). */
  @Transactional(readOnly = true)
  public PayrollSetupResponse status() {
    long componentCount = payComponentRepository.count();
    List<String> provenances = statutoryRuleRepository.findDistinctProvenances();
    String provenance =
        provenances.isEmpty() ? null : provenances.size() == 1 ? provenances.get(0) : "MIXED";
    String illustrativeVersion = statutoryRuleRepository.findLatestIllustrativeVersion();
    return new PayrollSetupResponse(
        componentCount > 0, componentCount, provenance, illustrativeVersion);
  }
}
