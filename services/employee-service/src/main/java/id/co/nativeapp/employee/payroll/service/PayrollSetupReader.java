package id.co.nativeapp.employee.payroll.service;

import id.co.nativeapp.employee.payroll.domain.StatutoryRule;
import id.co.nativeapp.employee.payroll.dto.PayrollSetupResponse;
import id.co.nativeapp.employee.payroll.dto.StatutoryRuleDetailResponse;
import id.co.nativeapp.employee.payroll.dto.StatutoryRuleResponse;
import id.co.nativeapp.employee.payroll.repository.PayComponentRepository;
import id.co.nativeapp.employee.payroll.repository.StatutoryRuleRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
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

  /**
   * The bound tenant's setup status ({@code count}/{@code DISTINCT} scalars — no projection).
   * Provenance is derived from rows CURRENTLY RESOLVABLE as of today (ADR 0031 review finding C2) —
   * a deactivated/date-closed illustrative row must not keep the badge stuck at {@code MIXED}
   * forever after the tenant activates a fully OFFICIAL dataset.
   */
  @Transactional(readOnly = true)
  public PayrollSetupResponse status() {
    long componentCount = payComponentRepository.count();
    List<String> provenances = statutoryRuleRepository.findDistinctProvenances(LocalDate.now());
    String provenance =
        provenances.isEmpty() ? null : provenances.size() == 1 ? provenances.get(0) : "MIXED";
    String illustrativeVersion = statutoryRuleRepository.findLatestIllustrativeVersion();
    return new PayrollSetupResponse(
        componentCount > 0, componentCount, provenance, illustrativeVersion);
  }

  /**
   * The bound tenant's rules table ({@code GET /api/v1/payroll-setup/rules}) — every row, no params
   * (a narrow projection read). Projection-to-DTO mapping happens here in the service layer
   * (CODE-STRUCTURE §3.3), mirroring {@code PayrollRunReader.listByPeriod}.
   */
  @Transactional(readOnly = true)
  public List<StatutoryRuleResponse> listRules() {
    return statutoryRuleRepository.findSummaryViews().stream()
        .map(
            v ->
                new StatutoryRuleResponse(
                    v.getRuleKey(),
                    v.getRuleVersion(),
                    v.getCalcType(),
                    v.getProvenance(),
                    v.getEffectiveFrom(),
                    v.getEffectiveTo(),
                    v.getSourceNote(),
                    Boolean.TRUE.equals(v.getActive())))
        .toList();
  }

  /**
   * A rule_key's currently-open row detail, including {@code paramsJson} ({@code GET
   * /api/v1/payroll-setup/rules/{ruleKey}}) — empty if the tenant has never seeded that rule_key.
   */
  @Transactional(readOnly = true)
  public Optional<StatutoryRuleDetailResponse> ruleDetail(String ruleKey) {
    return statutoryRuleRepository
        .findOpenDetailByRuleKey(ruleKey, StatutoryRule.OPEN_ENDED)
        .map(
            v ->
                new StatutoryRuleDetailResponse(
                    v.getRuleKey(),
                    v.getRuleVersion(),
                    v.getCalcType(),
                    v.getParamsJson(),
                    v.getCurrency(),
                    v.getProvenance(),
                    v.getSourceNote(),
                    v.getEffectiveFrom(),
                    v.getEffectiveTo()));
  }
}
