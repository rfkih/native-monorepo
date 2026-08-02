package id.co.nativeapp.employee.me.service;

import id.co.nativeapp.employee.assignment.dto.AssignmentResponse;
import id.co.nativeapp.employee.assignment.repository.AssignmentRepository;
import id.co.nativeapp.employee.employee.domain.Employee;
import id.co.nativeapp.employee.employee.dto.ContractResponse;
import id.co.nativeapp.employee.employee.repository.EmployeeRepository;
import id.co.nativeapp.employee.employee.repository.EmploymentContractRepository;
import id.co.nativeapp.employee.me.domain.EmployeeNotLinkedException;
import id.co.nativeapp.employee.me.dto.MeProfileResponse;
import id.co.nativeapp.employee.me.dto.MyPayslipDetailResponse;
import id.co.nativeapp.employee.me.dto.MyPayslipHeaderResponse;
import id.co.nativeapp.employee.me.dto.MyPayslipLineResponse;
import id.co.nativeapp.employee.me.dto.MySalesResponse;
import id.co.nativeapp.employee.payroll.domain.CompensationPackage;
import id.co.nativeapp.employee.payroll.domain.EarningParamKind;
import id.co.nativeapp.employee.payroll.domain.EarningRule;
import id.co.nativeapp.employee.payroll.domain.MetricInput;
import id.co.nativeapp.employee.payroll.domain.PayComponentBearer;
import id.co.nativeapp.employee.payroll.domain.PayComponentKind;
import id.co.nativeapp.employee.payroll.domain.PayrollRun;
import id.co.nativeapp.employee.payroll.domain.PayslipLine;
import id.co.nativeapp.employee.payroll.repository.CompensationPackageRepository;
import id.co.nativeapp.employee.payroll.repository.EarningRuleRepository;
import id.co.nativeapp.employee.payroll.repository.MetricInputRepository;
import id.co.nativeapp.employee.payroll.repository.PayrollRunRepository;
import id.co.nativeapp.employee.payroll.repository.PayslipLineRepository;
import id.co.nativeapp.money.Money;
import id.co.nativeapp.tenant.TenantContext;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The employee self-service read side ({@code /api/v1/me/**}). The caller is resolved EXCLUSIVELY
 * from {@link TenantContext#require()}'s actor — the JWT {@code sub} the gateway injects — via the
 * V7 employee↔login link; no request parameter can widen the view, so a caller only ever reads
 * their OWN rows (within the RLS-scoped tenant, rule 5).
 *
 * <p><strong>The PII stance (rule 6):</strong> NIK/bank stay MASKED even to the person themselves;
 * only payslip AMOUNTS are decrypted, and only via {@link #payslipDetail(UUID)} which loads
 * strictly the caller's own lines. An unlinked login fails closed with {@link
 * EmployeeNotLinkedException} (404) on every read.
 */
@Service
public class MeReader {

  /**
   * The sales metric a restaurant sale publishes at employee grain — the own-sales commission base.
   */
  private static final String SALES_METRIC = "sales_amount";

  private final EmployeeRepository employeeRepository;
  private final AssignmentRepository assignmentRepository;
  private final EmploymentContractRepository contractRepository;
  private final PayslipLineRepository payslipLineRepository;
  private final PayrollRunRepository payrollRunRepository;
  private final MetricInputRepository metricInputRepository;
  private final CompensationPackageRepository compPackageRepository;
  private final EarningRuleRepository earningRuleRepository;

  public MeReader(
      EmployeeRepository employeeRepository,
      AssignmentRepository assignmentRepository,
      EmploymentContractRepository contractRepository,
      PayslipLineRepository payslipLineRepository,
      PayrollRunRepository payrollRunRepository,
      MetricInputRepository metricInputRepository,
      CompensationPackageRepository compPackageRepository,
      EarningRuleRepository earningRuleRepository) {
    this.employeeRepository = employeeRepository;
    this.assignmentRepository = assignmentRepository;
    this.contractRepository = contractRepository;
    this.payslipLineRepository = payslipLineRepository;
    this.payrollRunRepository = payrollRunRepository;
    this.metricInputRepository = metricInputRepository;
    this.compPackageRepository = compPackageRepository;
    this.earningRuleRepository = earningRuleRepository;
  }

  /**
   * The caller's own sales for the period + a commission preview. Sums the caller's employee-grain
   * {@code sales_amount} metrics (subject = their login sub) and, if they have an active commission
   * rule, previews {@code rate × sales}. The estimate is a PREVIEW — the posted payslip is
   * authoritative. Currency comes from the caller's open compensation package (its amount is never
   * read into the response — only its currency).
   */
  @Transactional(readOnly = true)
  public MySalesResponse salesSummary(String period) {
    Employee me = resolveMe();
    LocalDate asOf = LocalDate.now();
    String monthPrefix =
        (period == null || period.isBlank()) ? asOf.toString().substring(0, 7) : period.strip();

    // The caller's packages, fetched ONCE (both currency and the commission rate read from them).
    List<CompensationPackage> packages = compPackageRepository.findByEmployeeId(me.getId());

    // Currency from the open compensation package (never expose the amount). The IDR fallback is
    // the single-currency-slice default when the caller has sales but no covering package.
    // Single-currency assumption: the commission math treats the sale currency AS this currency —
    // the metric feed carries no currency of its own (same caveat as the authoritative path,
    // PayrollRunWriter.resolvePercentOfMetricEarning).
    String currency =
        packages.stream()
            .filter(p -> p.coversAsOf(asOf))
            .findFirst()
            .map(p -> p.getBasePay().currency().getCurrencyCode())
            .orElse("IDR");

    long sales = 0L;
    Integer commissionBp = null;
    if (me.getUserId() != null) {
      try {
        UUID subject = UUID.fromString(me.getUserId());
        sales = sumSingleGrain(SALES_METRIC, subject, monthPrefix);
      } catch (IllegalArgumentException ignore) {
        // A non-UUID sub keys no metric rows — sales stays 0.
      }
      commissionBp = activeCommissionBasisPoints(packages, SALES_METRIC, asOf);
    }

    Long estimate =
        commissionBp == null
            ? null
            : Money.ofMinor(sales, currency).applyBasisPoints(commissionBp).amountMinor();
    return new MySalesResponse(monthPrefix, sales, currency, commissionBp, estimate);
  }

  /**
   * Sums the subject's metric rows for the month, resolving to a SINGLE period grain — the same
   * no-double-count rule the authoritative payroll run enforces. In practice the restaurant
   * producer emits {@code sales_amount} only at the daily grain, so this only ever sees one grain;
   * the guard keeps the preview honest were a future producer to emit the same metric at the
   * monthly grain.
   */
  private long sumSingleGrain(String metricKey, UUID subject, String monthPrefix) {
    long total = 0L;
    Integer grainLen = null;
    for (MetricInput row :
        metricInputRepository.findByMetricKeyAndSubjectIdAndPeriodStartingWith(
            metricKey, subject, monthPrefix)) {
      int rowLen = row.getPeriod().length();
      if (grainLen == null) {
        grainLen = rowLen;
      } else if (grainLen != rowLen) {
        // Mixed daily/monthly rows would double-count; the preview stays on the first grain seen.
        continue;
      }
      total += row.getValue();
    }
    return total;
  }

  /**
   * The caller's active {@code PERCENT_OF_METRIC} rate (bp) FOR THE GIVEN metric on an open
   * package, or null. Matches on {@code metricKey} so a commission on some other metric never
   * previews against {@code sales_amount} sales.
   */
  private Integer activeCommissionBasisPoints(
      List<CompensationPackage> packages, String metricKey, LocalDate asOf) {
    for (CompensationPackage pkg : packages) {
      if (!pkg.coversAsOf(asOf)) {
        continue;
      }
      for (EarningRule rule :
          earningRuleRepository.findByCompensationPackageIdAndParamKind(
              pkg.getId(), EarningParamKind.PERCENT_OF_METRIC)) {
        if (rule.coversAsOf(asOf)
            && metricKey.equals(rule.getMetricKey())
            && rule.getPercentBasisPoints() != null) {
          return rule.getPercentBasisPoints();
        }
      }
    }
    return null;
  }

  /** The caller's profile (PII masked) with their assignments and contracts. */
  @Transactional(readOnly = true)
  public MeProfileResponse profile() {
    Employee me = resolveMe();
    List<AssignmentResponse> assignments =
        assignmentRepository.findByEmployeeId(me.getId()).stream()
            .map(AssignmentResponse::from)
            .toList();
    List<ContractResponse> contracts =
        contractRepository.findByEmployeeId(me.getId()).stream()
            .map(ContractResponse::from)
            .toList();
    return new MeProfileResponse(
        me.getId(),
        me.getFullName(),
        me.getPtkpStatus().name(),
        me.getStatus().name(),
        me.maskedNik(),
        me.maskedBankAccount(),
        me.hasNpwp(),
        me.maskedNpwp(),
        assignments,
        contracts);
  }

  /** The caller's payslip run headers, newest first (no amounts — the detail read decrypts). */
  @Transactional(readOnly = true)
  public List<MyPayslipHeaderResponse> payslipHeaders(String period) {
    Employee me = resolveMe();
    String normalized = (period == null || period.isBlank()) ? null : period.strip();
    // Projection-to-DTO mapping happens here in the service layer (CODE-STRUCTURE §3.3).
    return payslipLineRepository.findMyPayslipHeaders(me.getId(), normalized).stream()
        .map(
            v ->
                new MyPayslipHeaderResponse(
                    v.getRunId(),
                    v.getPeriod(),
                    v.getRunSeq(),
                    v.getPostedAt(),
                    v.getLineCount(),
                    Boolean.TRUE.equals(v.getIllustrative())))
        .toList();
  }

  /**
   * The caller's OWN payslip for one run, with REAL amounts — the only consumer of the decrypted
   * line read. Empty when the run is unknown to the tenant OR carries none of the caller's lines
   * (both collapse to the same 404 upstream — anti-enumeration).
   */
  @Transactional(readOnly = true)
  public Optional<MyPayslipDetailResponse> payslipDetail(UUID runId) {
    Employee me = resolveMe();
    List<PayslipLine> lines =
        payslipLineRepository.findByPayrollRunIdAndEmployeeId(runId, me.getId());
    if (lines.isEmpty()) {
      return Optional.empty();
    }
    PayrollRun run = payrollRunRepository.findById(runId).orElse(null);
    if (run == null) {
      return Optional.empty();
    }

    long gross = 0;
    long deductions = 0;
    boolean illustrative = false;
    String currency = run.getBaseCurrency();
    List<MyPayslipLineResponse> lineResponses = new java.util.ArrayList<>();
    for (PayslipLine line : lines) {
      long amountMinor = line.getAmount().amountMinor();
      String lineCurrency = line.getAmount().currency().getCurrencyCode();
      boolean employeeBorne = line.getBearer() == PayComponentBearer.EMPLOYEE;
      if (employeeBorne && line.getKind() == PayComponentKind.EARNING) {
        gross += amountMinor;
      } else if (employeeBorne && line.getKind() == PayComponentKind.DEDUCTION) {
        deductions += amountMinor;
      }
      illustrative = illustrative || line.isIllustrative();
      lineResponses.add(
          new MyPayslipLineResponse(
              line.getComponentKey(),
              line.getKind().name(),
              line.getBearer().name(),
              amountMinor,
              lineCurrency,
              line.isIllustrative()));
    }
    return Optional.of(
        new MyPayslipDetailResponse(
            runId,
            run.getPeriod(),
            run.getRunSeq(),
            currency,
            gross,
            deductions,
            gross - deductions,
            illustrative,
            lineResponses));
  }

  /**
   * Resolves the calling login to their employee row: actor (the JWT sub) → {@code
   * employee.user_id}. RLS scopes the lookup to the bound tenant, so the same sub in another tenant
   * resolves nothing (fail-closed 404).
   */
  private Employee resolveMe() {
    String actor = TenantContext.require().actor();
    return employeeRepository.findByUserId(actor).orElseThrow(EmployeeNotLinkedException::new);
  }
}
