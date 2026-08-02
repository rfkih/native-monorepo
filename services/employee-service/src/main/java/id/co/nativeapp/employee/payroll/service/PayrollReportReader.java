package id.co.nativeapp.employee.payroll.service;

import id.co.nativeapp.employee.employee.domain.Employee;
import id.co.nativeapp.employee.employee.repository.EmployeeRepository;
import id.co.nativeapp.employee.payroll.domain.PayComponent;
import id.co.nativeapp.employee.payroll.domain.PayComponentKind;
import id.co.nativeapp.employee.payroll.domain.PayslipLine;
import id.co.nativeapp.employee.payroll.domain.StatutoryCalcType;
import id.co.nativeapp.employee.payroll.domain.StatutoryParams;
import id.co.nativeapp.employee.payroll.domain.StatutoryRule;
import id.co.nativeapp.employee.payroll.repository.PayComponentRepository;
import id.co.nativeapp.employee.payroll.repository.PayslipLineRepository;
import id.co.nativeapp.employee.payroll.repository.StatutoryRuleRepository;
import id.co.nativeapp.money.Money;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Statutory CSV reports built on the P3 active-run history query family (Track P phase P9). Every
 * figure is decrypted/aggregated HERE, in the service layer, and only ever reaches a CSV string —
 * never a log line (rule 6).
 *
 * <p><strong>Honest parity note (see the plan's "Statutory outputs" section).</strong> Odoo's
 * Indonesian localisation ships essentially none of this (COA + tax tags only; Enterprise adds a
 * limited rule set and a payslip PDF, no 1721-A1/e-Bupot/SIPP). These three CSVs therefore EXCEED
 * Odoo's parity bar, but ship as flagged ILLUSTRATIVE LAYOUTS — a plausible, human-readable column
 * shape, not the certified DJP e-Bupot XML or BPJS SIPP-portal schema. Every export carries a
 * leading {@code #}-comment row saying so; the reader Javadoc on each method repeats the caveat
 * with its specific form/version citation.
 *
 * <p><strong>Historical-reconstruction approximation (shared with {@code
 * PayrollRunWriter#buildAnnualContext}, ADR 0031 P3 residual note).</strong> A stored {@code
 * payslip_line} carries only {@code component_key}/{@code kind}/{@code bearer} — not whether the
 * component was taxable, or which rule family produced a deduction, AT THE TIME the line was
 * produced. Every method below classifies a historical line by the CURRENT {@code pay_component}
 * catalog (taxable flag for an EARNING line; the CURRENT ACTIVE resolved {@link StatutoryRule} for
 * a DEDUCTION line's {@code statutory_rule_key}, {@link #resolveActiveRulesByKey}) rather than
 * re-resolving each historical month's ACTUAL rule as it stood that month — a real approximation
 * (documented, not silently assumed correct; a residual if a rule's shape changed mid-year), but
 * NOT a second hand-copied classification: {@code PPH21} for income tax stays a fixed component-key
 * check (there is no "resolve params" for the tax line itself), while every OTHER deduction line
 * (the BPJS legs) is classified via the LITERAL SAME {@link
 * GrossToNetCalculator#classifyCeilingLegForTaxBase} method {@link GrossToNetCalculator#compute}
 * itself calls — one tax-base-assembly source of truth, made true by extraction rather than claimed
 * by parallel hand-written logic (the W1 review finding this closes: the prior version summed
 * taxable EARNING lines only, silently dropping the notional employer BPJS premiums {@code
 * compute()} DOES fold into {@code grossBruto}, understating {@code annual_bruto_minor} / {@code
 * gross_bruto_minor} for every taxpayer).
 */
@Service
public class PayrollReportReader {

  private static final Logger log = LoggerFactory.getLogger(PayrollReportReader.class);

  /** {@code pay_component.component_key} for the monthly/annual income-tax deduction line. */
  private static final String COMPONENT_KEY_PPH21 = "PPH21";

  private static final String RULE_KEY_ARTICLE_17 = "PPH21_ARTICLE17";
  private static final String RULE_KEY_PTKP_RELIEF = "PTKP_RELIEF";

  private static final String BUKTI_1721A1_COMMENT =
      "# Bukti Potong 1721-A1 (annual PPh21 withholding slip, DJP form 1721-A1 per PER-14/PJ/2013"
          + " as amended) — LAYOUT ILLUSTRATIVE, not the certified DJP e-Bupot XML schema. Verify"
          + " every figure against the primary filing before submission. OWNER-ONLY (NIK/NPWP PII).";
  private static final String BUKTI_1721A1_HEADER =
      "nik,npwp,full_name,annual_bruto_minor,biaya_jabatan_minor,pengurang_minor,ptkp_minor,"
          + "pkp_minor,annual_pph21_minor,withheld_minor,currency";

  private static final String PPH21_MONTHLY_COMMENT =
      "# SPT Masa PPh 21 monthly summary — AGGREGATE ONLY, no per-employee figures. LAYOUT"
          + " ILLUSTRATIVE, not the certified DJP e-SPT/e-Bupot schema. Verify before filing.";
  private static final String PPH21_MONTHLY_HEADER =
      "period,headcount,no_npwp_count,gross_bruto_minor,pph21_withheld_minor,currency";

  private static final String BPJS_SUMMARY_COMMENT =
      "# BPJS contribution summary, per employee per program — OWNER-ONLY (per-employee wage is"
          + " salary-revealing, judged equivalent to payslip PII even though it is not NIK/NPWP)."
          + " LAYOUT ILLUSTRATIVE, not a certified BPJS/SIPP-portal schema. Verify before filing.";
  private static final String BPJS_SUMMARY_HEADER =
      "employee_name,program,wage_minor,employee_contribution_minor,employer_contribution_minor,"
          + "currency";

  /** Canonical display order for BPJS programs (Kesehatan first, matching the statutory matrix). */
  private static final List<String> BPJS_PROGRAM_ORDER =
      List.of("KESEHATAN", "JHT", "JP", "JKK", "JKM");

  private static final Map<String, BpjsLeg> BPJS_COMPONENT_LEGS =
      Map.ofEntries(
          Map.entry("BPJS_KES_EE", new BpjsLeg("KESEHATAN", true)),
          Map.entry("BPJS_KES_ER", new BpjsLeg("KESEHATAN", false)),
          Map.entry("JHT_EE", new BpjsLeg("JHT", true)),
          Map.entry("JHT_ER", new BpjsLeg("JHT", false)),
          Map.entry("JP_EE", new BpjsLeg("JP", true)),
          Map.entry("JP_ER", new BpjsLeg("JP", false)),
          Map.entry("JKK_ER", new BpjsLeg("JKK", false)),
          Map.entry("JKM_ER", new BpjsLeg("JKM", false)));

  private final PayslipLineRepository payslipLineRepository;
  private final PayComponentRepository payComponentRepository;
  private final EmployeeRepository employeeRepository;
  private final StatutoryRuleRepository statutoryRuleRepository;
  private final GrossToNetCalculator calculator;

  public PayrollReportReader(
      PayslipLineRepository payslipLineRepository,
      PayComponentRepository payComponentRepository,
      EmployeeRepository employeeRepository,
      StatutoryRuleRepository statutoryRuleRepository,
      GrossToNetCalculator calculator) {
    this.payslipLineRepository =
        Objects.requireNonNull(payslipLineRepository, "payslipLineRepository");
    this.payComponentRepository =
        Objects.requireNonNull(payComponentRepository, "payComponentRepository");
    this.employeeRepository = Objects.requireNonNull(employeeRepository, "employeeRepository");
    this.statutoryRuleRepository =
        Objects.requireNonNull(statutoryRuleRepository, "statutoryRuleRepository");
    this.calculator = Objects.requireNonNull(calculator, "calculator");
  }

  /**
   * The per-employee annual Bukti Potong 1721-A1 CSV for {@code year} (a validated 4-digit string).
   * OWNER-ONLY (bank-file-style PII gate: NIK + NPWP are the national id / tax id, both decrypted
   * at this boundary ONLY via {@link Employee#nikForStatutoryFile()}/{@link
   * Employee#npwpForStatutoryFile()} — never logged, rule 6).
   *
   * <p>{@code biaya_jabatan}/{@code pengurang}/{@code ptkp}/{@code pkp}/{@code annual_pph21} are
   * RECOMPUTED from the year's accumulated {@code annual_bruto}/deductible-social sums using the
   * SAME Art-17 formula (and the SAME {@link GrossToNetCalculator} bracket-walk/no-NPWP-surcharge
   * helpers) as the December true-up ({@code PayrollRunWriter}) — using the tenant's CURRENTLY
   * ACTIVE {@code PPH21_ARTICLE17}/{@code PTKP_RELIEF} rules as-of {@code year}-12-31. A tenant
   * that never activated the OFFICIAL dataset (no {@code PPH21_ARTICLE17} on file) still gets a row
   * with real {@code annual_bruto}/{@code withheld} sums, but those five derived columns are zero —
   * documented, not silently wrong.
   *
   * <p>Empty when no employee has an active payslip line in the year.
   */
  @Transactional(readOnly = true)
  public String bukti1721A1(String year) {
    Map<String, PayComponent> catalog = catalogByKey();
    LocalDate yearEnd = LocalDate.of(Integer.parseInt(year), 12, 31);
    Map<String, StatutoryRule> activeRulesByKey = resolveActiveRulesByKey(catalog, yearEnd);
    StatutoryRule annualRule =
        statutoryRuleRepository
            .findByRuleKeyAndActiveTrueAndEffectiveFromLessThanEqualAndEffectiveToGreaterThanEqual(
                RULE_KEY_ARTICLE_17, yearEnd, yearEnd)
            .orElse(null);
    StatutoryRule reliefRule =
        statutoryRuleRepository
            .findByRuleKeyAndActiveTrueAndEffectiveFromLessThanEqualAndEffectiveToGreaterThanEqual(
                RULE_KEY_PTKP_RELIEF, yearEnd, yearEnd)
            .orElse(null);
    StatutoryParams.AnnualProgressiveParams annualParams =
        annualRule == null ? null : StatutoryParams.annualProgressive(annualRule.getParamsJson());
    StatutoryParams.ReliefParams reliefParams =
        reliefRule == null ? null : StatutoryParams.relief(reliefRule.getParamsJson());

    List<UUID> employeeIds = payslipLineRepository.findDistinctEmployeeIdsForYear(year);
    List<Bukti1721A1Row> rows = new ArrayList<>();
    for (UUID employeeId : employeeIds) {
      Employee employee = employeeRepository.findById(employeeId).orElse(null);
      List<PayslipLine> lines =
          payslipLineRepository.findActiveLinesForEmployeeYear(employeeId, year);
      if (employee == null || lines.isEmpty()) {
        continue; // defensive: should not occur under RLS (the id came from this same tenant's
        // rows)
      }
      String currency = lines.get(0).getAmount().currency().getCurrencyCode();
      Money bruto = Money.ofMinor(0L, currency);
      Money deductibleSocial = Money.ofMinor(0L, currency);
      Money withheld = Money.ofMinor(0L, currency);
      for (PayslipLine line : lines) {
        PayComponent current = catalog.get(line.getComponentKey());
        if (line.getKind() == PayComponentKind.EARNING) {
          if (current != null && current.isTaxable()) {
            bruto = bruto.plus(line.getAmount());
          }
        } else if (line.getKind() == PayComponentKind.DEDUCTION) {
          if (COMPONENT_KEY_PPH21.equals(line.getComponentKey())) {
            withheld = withheld.plus(line.getAmount());
          } else {
            // W1 review fix: the notional employer BPJS premiums (BPJS_KES_ER/JKK_ER/JKM_ER) that
            // GrossToNetCalculator#compute folds into grossBruto MUST also land in annual_bruto
            // here, via the SAME classification method — see taxBaseContribution's javadoc.
            GrossToNetCalculator.CeilingLegTaxBaseContribution contribution =
                taxBaseContribution(line, catalog, activeRulesByKey);
            deductibleSocial = deductibleSocial.plus(contribution.deductibleSocialDelta());
            bruto = bruto.plus(contribution.employerTaxableAdditionDelta());
          }
        }
      }

      Money biayaJabatan = Money.ofMinor(0L, currency);
      Money pengurang = Money.ofMinor(0L, currency);
      Money ptkp = Money.ofMinor(0L, currency);
      Money pkp = Money.ofMinor(0L, currency);
      Money annualLiability = Money.ofMinor(0L, currency);
      if (annualParams != null) {
        int monthsInYear =
            Math.max(
                1, payslipLineRepository.findActivePeriodsForEmployeeYear(employeeId, year).size());
        Money occupationalCostCap =
            Money.ofMinor(annualParams.occupationalCostCapAnnualMinor(), currency)
                .mulDiv(monthsInYear, 12L);
        biayaJabatan =
            bruto.applyBasisPoints(annualParams.occupationalCostBp()).min(occupationalCostCap);
        pengurang = biayaJabatan.plus(deductibleSocial);
        if (reliefParams != null) {
          long reliefMinor =
              reliefParams.ptkpByStatus().getOrDefault(employee.getPtkpStatus().name(), 0L);
          ptkp = Money.ofMinor(reliefMinor, currency);
        }
        Money net = bruto.minus(pengurang).minus(ptkp);
        if (net.isNegative()) {
          net = Money.ofMinor(0L, currency);
        }
        pkp =
            Money.ofMinor(
                calculator.floorToNearest(net.amountMinor(), GrossToNetCalculator.PKP_FLOOR_UNIT),
                currency);
        annualLiability = calculator.walkBrackets(pkp, annualParams.brackets(), pkp.currency());
        annualLiability =
            calculator.applyNoNpwpSurcharge(
                annualLiability, annualParams.noNpwpSurchargeBp(), employee.hasNpwp());
      }

      rows.add(
          new Bukti1721A1Row(
              employee.nikForStatutoryFile(),
              employee.npwpForStatutoryFile(),
              employee.getFullName(),
              bruto,
              biayaJabatan,
              pengurang,
              ptkp,
              pkp,
              annualLiability,
              withheld,
              currency));
    }
    rows.sort(Comparator.comparing(Bukti1721A1Row::fullName, String.CASE_INSENSITIVE_ORDER));

    StringBuilder csv = new StringBuilder();
    csv.append(BUKTI_1721A1_COMMENT).append('\n').append(BUKTI_1721A1_HEADER).append('\n');
    for (Bukti1721A1Row row : rows) {
      csv.append(CsvFieldSupport.field(row.nik()))
          .append(',')
          .append(CsvFieldSupport.field(row.npwp()))
          .append(',')
          .append(CsvFieldSupport.field(row.fullName()))
          .append(',')
          .append(row.annualBruto().amountMinor())
          .append(',')
          .append(row.biayaJabatan().amountMinor())
          .append(',')
          .append(row.pengurang().amountMinor())
          .append(',')
          .append(row.ptkp().amountMinor())
          .append(',')
          .append(row.pkp().amountMinor())
          .append(',')
          .append(row.annualPph21().amountMinor())
          .append(',')
          .append(row.withheld().amountMinor())
          .append(',')
          .append(row.currency())
          .append('\n');
    }
    csv.append("# row_count=").append(rows.size()).append('\n');

    // Audit log: year + row count ONLY — zero PII (rule 6). No name, no NIK/NPWP, no amount.
    log.info("1721-A1 report generated year={} rows={}", year, rows.size());
    return csv.toString();
  }

  /**
   * The company-wide monthly SPT Masa PPh 21 summary CSV for {@code period} ({@code "YYYY-MM"}) —
   * AGGREGATE ONLY: sum of taxable gross bruto, sum of PPh21 withheld, headcount (distinct
   * employees with any active payslip line this period), and the count among them with no NPWP on
   * file. DASHBOARD_ROLES (owner or manager) — no per-employee figure crosses this boundary.
   *
   * <p>One data row when the period has at least one active line; header-only (zero rows)
   * otherwise.
   */
  @Transactional(readOnly = true)
  public String pph21Monthly(String period) {
    List<PayslipLine> lines = payslipLineRepository.findActiveLinesForPeriod(period);
    Map<String, PayComponent> catalog = catalogByKey();
    LocalDate periodEnd = YearMonth.parse(period).atEndOfMonth();
    Map<String, StatutoryRule> activeRulesByKey = resolveActiveRulesByKey(catalog, periodEnd);

    String currency = null;
    long grossBrutoMinor = 0L;
    long pph21Minor = 0L;
    Set<UUID> employeeIds = new LinkedHashSet<>();
    for (PayslipLine line : lines) {
      employeeIds.add(line.getEmployeeId());
      if (currency == null) {
        currency = line.getAmount().currency().getCurrencyCode();
      }
      PayComponent current = catalog.get(line.getComponentKey());
      if (line.getKind() == PayComponentKind.EARNING) {
        if (current != null && current.isTaxable()) {
          grossBrutoMinor += line.getAmount().amountMinor();
        }
      } else if (line.getKind() == PayComponentKind.DEDUCTION) {
        if (COMPONENT_KEY_PPH21.equals(line.getComponentKey())) {
          pph21Minor += line.getAmount().amountMinor();
        } else {
          // W1 review fix — see bukti1721A1's identical comment: the notional employer BPJS
          // premiums belong in gross bruto here too.
          GrossToNetCalculator.CeilingLegTaxBaseContribution contribution =
              taxBaseContribution(line, catalog, activeRulesByKey);
          grossBrutoMinor += contribution.employerTaxableAdditionDelta().amountMinor();
        }
      }
    }

    int noNpwpCount = 0;
    if (!employeeIds.isEmpty()) {
      for (Employee employee : employeeRepository.findAllById(employeeIds)) {
        if (!employee.hasNpwp()) {
          noNpwpCount++;
        }
      }
    }

    StringBuilder csv = new StringBuilder();
    csv.append(PPH21_MONTHLY_COMMENT).append('\n').append(PPH21_MONTHLY_HEADER).append('\n');
    if (currency != null) {
      csv.append(CsvFieldSupport.field(period))
          .append(',')
          .append(employeeIds.size())
          .append(',')
          .append(noNpwpCount)
          .append(',')
          .append(grossBrutoMinor)
          .append(',')
          .append(pph21Minor)
          .append(',')
          .append(currency)
          .append('\n');
    }

    // Audit log: period + headcount ONLY — zero PII (rule 6, and this whole report is aggregate).
    log.info("pph21-monthly report generated period={} headcount={}", period, employeeIds.size());
    return csv.toString();
  }

  /**
   * The company-wide per-employee, per-program BPJS contribution summary CSV for {@code period} —
   * wage (the capped contribution base, {@link PayslipLine#getCalcBasis()}), employee leg, employer
   * leg, per {Kesehatan, JHT, JP, JKK, JKM}. OWNER-ONLY — see the class Javadoc's judgment call:
   * this is NOT the NIK/NPWP class of PII 1721-A1 carries, but a named employee's BPJS wage is, in
   * practice, close to their base pay — salary-revealing in the same spirit as rule 6, so this
   * report is gated exactly like {@link #bukti1721A1} rather than the aggregate {@link
   * #pph21Monthly}.
   *
   * <p>One row per (employee, program with at least one leg this period), sorted by employee name
   * then the canonical program order (Kesehatan, JHT, JP, JKK, JKM).
   */
  @Transactional(readOnly = true)
  public String bpjsSummary(String period) {
    List<PayslipLine> lines = payslipLineRepository.findActiveLinesForPeriod(period);

    Map<UUID, Map<String, BpjsAccumulator>> byEmployee = new LinkedHashMap<>();
    for (PayslipLine line : lines) {
      BpjsLeg leg = BPJS_COMPONENT_LEGS.get(line.getComponentKey());
      if (leg == null) {
        continue;
      }
      Map<String, BpjsAccumulator> byProgram =
          byEmployee.computeIfAbsent(line.getEmployeeId(), k -> new LinkedHashMap<>());
      BpjsAccumulator acc = byProgram.computeIfAbsent(leg.program(), k -> new BpjsAccumulator());
      if (acc.currency == null) {
        acc.currency = line.getAmount().currency().getCurrencyCode();
      }
      if (acc.wageMinor == null && line.getCalcBasis() != null) {
        acc.wageMinor = line.getCalcBasis().amountMinor();
      }
      if (leg.employeeLeg()) {
        acc.employeeMinor += line.getAmount().amountMinor();
      } else {
        acc.employerMinor += line.getAmount().amountMinor();
      }
    }

    Map<UUID, Employee> employeesById = new LinkedHashMap<>();
    for (Employee employee : employeeRepository.findAllById(byEmployee.keySet())) {
      employeesById.put(employee.getId(), employee);
    }

    List<BpjsRow> rows = new ArrayList<>();
    for (Map.Entry<UUID, Map<String, BpjsAccumulator>> perEmployee : byEmployee.entrySet()) {
      Employee employee = employeesById.get(perEmployee.getKey());
      String name = employee != null ? employee.getFullName() : perEmployee.getKey().toString();
      for (Map.Entry<String, BpjsAccumulator> perProgram : perEmployee.getValue().entrySet()) {
        BpjsAccumulator acc = perProgram.getValue();
        rows.add(
            new BpjsRow(
                name,
                perProgram.getKey(),
                acc.wageMinor == null ? 0L : acc.wageMinor,
                acc.employeeMinor,
                acc.employerMinor,
                acc.currency));
      }
    }
    rows.sort(
        Comparator.comparing(BpjsRow::employeeName, String.CASE_INSENSITIVE_ORDER)
            .thenComparingInt(r -> BPJS_PROGRAM_ORDER.indexOf(r.program())));

    StringBuilder csv = new StringBuilder();
    csv.append(BPJS_SUMMARY_COMMENT).append('\n').append(BPJS_SUMMARY_HEADER).append('\n');
    for (BpjsRow row : rows) {
      csv.append(CsvFieldSupport.field(row.employeeName()))
          .append(',')
          .append(row.program())
          .append(',')
          .append(row.wageMinor())
          .append(',')
          .append(row.employeeMinor())
          .append(',')
          .append(row.employerMinor())
          .append(',')
          .append(row.currency() == null ? "" : row.currency())
          .append('\n');
    }
    csv.append("# row_count=").append(rows.size()).append('\n');

    // Audit log: period + row count ONLY — no employee name, no wage/contribution amount (rule 6).
    log.info("bpjs-summary report generated period={} rows={}", period, rows.size());
    return csv.toString();
  }

  private Map<String, PayComponent> catalogByKey() {
    Map<String, PayComponent> byKey = new LinkedHashMap<>();
    for (PayComponent component : payComponentRepository.findByActiveTrueOrderByDisplayOrderAsc()) {
      byKey.put(component.getComponentKey(), component);
    }
    return byKey;
  }

  /**
   * Resolves the CURRENTLY ACTIVE {@link StatutoryRule} for every DISTINCT {@code
   * statutory_rule_key} the {@code catalog} references, as-of {@code asOf} — the SAME
   * historical-reconstruction approximation the class javadoc documents (the CURRENT rule set, not
   * a per-month re-resolution of what was active back then). Feeds {@link #taxBaseContribution}.
   */
  private Map<String, StatutoryRule> resolveActiveRulesByKey(
      Map<String, PayComponent> catalog, LocalDate asOf) {
    Map<String, StatutoryRule> byKey = new LinkedHashMap<>();
    for (PayComponent component : catalog.values()) {
      String ruleKey = component.getStatutoryRuleKey();
      if (ruleKey == null || byKey.containsKey(ruleKey)) {
        continue;
      }
      statutoryRuleRepository
          .findByRuleKeyAndActiveTrueAndEffectiveFromLessThanEqualAndEffectiveToGreaterThanEqual(
              ruleKey, asOf, asOf)
          .ifPresent(rule -> byKey.put(ruleKey, rule));
    }
    return byKey;
  }

  /**
   * One historical {@link PayslipLine}'s tax-base contribution, via the LITERAL SAME classification
   * {@link GrossToNetCalculator#compute} uses for a live run (W1 review fix — the class javadoc's
   * "one tax source of truth" claim, made true by extraction): resolves the line's CURRENT catalog
   * component's CURRENT active rule and, when it is {@code PERCENTAGE_CEILING}, delegates to {@link
   * GrossToNetCalculator#classifyCeilingLegForTaxBase}. Zero for a line whose component is unknown,
   * has no statutory rule, or resolves a non-{@code PERCENTAGE_CEILING} family (e.g. the {@code
   * PPH21} income-tax line itself, which callers handle separately).
   */
  private GrossToNetCalculator.CeilingLegTaxBaseContribution taxBaseContribution(
      PayslipLine line,
      Map<String, PayComponent> catalog,
      Map<String, StatutoryRule> activeRulesByKey) {
    Money zero = Money.ofMinor(0L, line.getAmount().currency());
    PayComponent current = catalog.get(line.getComponentKey());
    if (current == null) {
      return new GrossToNetCalculator.CeilingLegTaxBaseContribution(zero, zero);
    }
    StatutoryRule rule = activeRulesByKey.get(current.getStatutoryRuleKey());
    if (rule == null || rule.getCalcType() != StatutoryCalcType.PERCENTAGE_CEILING) {
      return new GrossToNetCalculator.CeilingLegTaxBaseContribution(zero, zero);
    }
    StatutoryParams.CeilingParams params = StatutoryParams.ceiling(rule.getParamsJson());
    return calculator.classifyCeilingLegForTaxBase(line.getBearer(), params, line.getAmount());
  }

  private record Bukti1721A1Row(
      String nik,
      String npwp,
      String fullName,
      Money annualBruto,
      Money biayaJabatan,
      Money pengurang,
      Money ptkp,
      Money pkp,
      Money annualPph21,
      Money withheld,
      String currency) {}

  /** One BPJS component's (program, is-the-employee-leg) mapping. */
  private record BpjsLeg(String program, boolean employeeLeg) {}

  private static final class BpjsAccumulator {
    private String currency;
    private Long wageMinor;
    private long employeeMinor;
    private long employerMinor;
  }

  private record BpjsRow(
      String employeeName,
      String program,
      long wageMinor,
      long employeeMinor,
      long employerMinor,
      String currency) {}
}
