package id.co.nativeapp.employee.payroll.service;

import id.co.nativeapp.employee.payroll.domain.PayComponent;
import id.co.nativeapp.employee.payroll.domain.PayComponentBearer;
import id.co.nativeapp.employee.payroll.domain.PayrollInputs.AnnualContext;
import id.co.nativeapp.employee.payroll.domain.PayrollInputs.DeductionInput;
import id.co.nativeapp.employee.payroll.domain.PayrollInputs.EarningInput;
import id.co.nativeapp.employee.payroll.domain.PayrollInputs.PersonInput;
import id.co.nativeapp.employee.payroll.domain.StatutoryCalcType;
import id.co.nativeapp.employee.payroll.domain.StatutoryParams;
import id.co.nativeapp.employee.payroll.domain.StatutoryRule;
import id.co.nativeapp.employee.payroll.dto.PayrollResult.ComputedLine;
import id.co.nativeapp.employee.payroll.dto.PayrollResult.PersonResult;
import id.co.nativeapp.money.Money;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * The pure, config-driven gross-to-net engine (design §2). It contains the ALGORITHMS only — every
 * statutory FIGURE is data in a {@link StatutoryRule}'s {@code params_json} (HR-9, zero Indonesian
 * numbers in Java). It is a {@code @Component} with no {@code @Transactional} and no repository: a
 * deterministic function of its {@link PersonInput}, so two runs over the same frozen rule set +
 * inputs are byte-identical (HR-7).
 *
 * <p>All arithmetic is on {@link Money} (integer minor units, never float); the run's single base
 * currency governs every line and {@link Money} throws on any currency mismatch (no implicit FX).
 * Division uses {@link Money#mulDiv}/{@link Money#applyBasisPoints} (HALF_EVEN, once per line).
 *
 * <p>Calculation order, on the AGGREGATED multi-assignment total:
 *
 * <ol>
 *   <li>EARNINGS — base + each earning; produce gross_earnings and the taxable cash-earnings gross.
 *   <li>SOCIAL INSURANCE — percentage-with-ceiling rules; employee leg (DEDUCTION) + employer leg
 *       (EMPLOYER-borne, not net); some legs reduce the income-tax base, some EMPLOYER legs are a
 *       taxable fringe benefit that raises the tax base ONLY (never grossEarnings/net/cash) — the
 *       two-pass tax-base assembly (Track P phase P1, the BPJS tax-treatment matrix).
 *   <li>INCOME TAX — exactly ONE of three families resolves per run, driven by the linked {@link
 *       StatutoryRule}'s {@link StatutoryCalcType}: the legacy {@code PROGRESSIVE_BRACKET}
 *       annualize/de-annualize path (byte-identical to the pre-TER illustrative behaviour), the
 *       {@code TER_TABLE} monthly effective-rate lookup (PMK 168/2023), or the {@code
 *       ANNUAL_PROGRESSIVE} December/final-month Art-17 true-up (needs a non-null {@link
 *       AnnualContext}).
 *   <li>OTHER DEDUCTIONS — non-statutory employee deductions.
 *   <li>NET = gross_earnings - sum(EMPLOYEE-bearing deductions).
 * </ol>
 */
@Component
public class GrossToNetCalculator {

  private static final long BP_DENOMINATOR = 10_000L;

  /**
   * PKP (taxable income) is floored to the nearest 1000 minor units (UU HPP 7/2021 convention).
   * Package-private (not {@code private}) so {@link PayrollReportReader}'s 1721-A1 export can reuse
   * the SAME rounding unit as the December Art-17 true-up — one source of truth for the tax math,
   * never a second hand-copied constant.
   */
  static final long PKP_FLOOR_UNIT = 1_000L;

  /** Computes the full per-person result deterministically. */
  public PersonResult compute(PersonInput input) {
    Currency currency = input.basePay().currency();
    Money zero = Money.ofMinor(0L, currency);
    List<ComputedLine> lines = new ArrayList<>();
    boolean usesIllustrative = false;

    // ---- 1. EARNINGS -------------------------------------------------------
    Money grossEarnings = zero;
    Money taxableCashEarnings = zero;

    // BASE line.
    lines.add(
        new ComputedLine(input.baseComponent(), input.basePay(), input.basePay(), null, false));
    grossEarnings = grossEarnings.plus(input.basePay());
    if (input.baseComponent().isTaxable()) {
      taxableCashEarnings = taxableCashEarnings.plus(input.basePay());
    }

    // Other earnings (allowances, commission, metric-driven).
    for (EarningInput earning : input.earnings()) {
      Money amount = requireBase(earning.amount(), input);
      lines.add(new ComputedLine(earning.component(), amount, amount, null, false));
      grossEarnings = grossEarnings.plus(amount);
      if (earning.taxable()) {
        taxableCashEarnings = taxableCashEarnings.plus(amount);
      }
    }

    // ---- 2. SOCIAL INSURANCE (percentage-with-ceiling) --------------------
    Money employerContributions = zero;
    Money employeeDeductions = zero;
    Money deductibleSocial = zero; // employee legs that reduce the income-tax base
    Money employerTaxableAdditions = zero; // employer legs that are a taxable fringe benefit

    for (PayComponent component : statutoryByCalc(input, StatutoryCalcType.PERCENTAGE_CEILING)) {
      StatutoryRule rule = input.resolvedRules().get(component.getStatutoryRuleKey());
      if (rule == null) {
        continue;
      }
      usesIllustrative |= rule.isIllustrative();
      StatutoryParams.CeilingParams params = StatutoryParams.ceiling(rule.getParamsJson());
      Money percentageBase =
          params.baseKind() == StatutoryParams.BaseKind.BASE_PAY
              ? input.basePay()
              : taxableCashEarnings;
      Money ceiling = Money.ofMinor(params.ceilingMinor(), currency);
      Money cappedBase = percentageBase.min(ceiling);

      Money leg = cappedBase.applyBasisPoints(legBp(component, params));
      ComputedLine line =
          new ComputedLine(
              component, leg, cappedBase, rule.getRuleVersion(), rule.isIllustrative());
      lines.add(line);

      if (component.getBearer() == PayComponentBearer.EMPLOYEE) {
        employeeDeductions = employeeDeductions.plus(leg);
      } else {
        employerContributions = employerContributions.plus(leg);
      }
      CeilingLegTaxBaseContribution contribution =
          classifyCeilingLegForTaxBase(component.getBearer(), params, leg);
      deductibleSocial = deductibleSocial.plus(contribution.deductibleSocialDelta());
      employerTaxableAdditions =
          employerTaxableAdditions.plus(contribution.employerTaxableAdditionDelta());
    }

    // The gross bruto (Track P phase P1 two-pass tax-base assembly): taxable cash earnings PLUS
    // taxable employer additions. Employer legs never touch grossEarnings/net — tax base only.
    Money grossBruto = taxableCashEarnings.plus(employerTaxableAdditions);

    // ---- 3. INCOME TAX ------------------------------------------------------
    // Exactly one of the three families below resolves statutory components per run: the legacy
    // PROGRESSIVE_BRACKET rule set does not also carry a TER_TABLE/ANNUAL_PROGRESSIVE rule (and
    // vice-versa) — statutoryByCalc filters per-component by its RESOLVED rule's calc type, so an
    // absent family is simply an empty loop (zero lines, zero deduction contribution).

    // 3a. Legacy PROGRESSIVE_BRACKET — BYTE-IDENTICAL to the pre-TER illustrative behaviour
    // (periodTaxable = grossBruto - deductibleSocial equals the old taxableBase - deductibleSocial
    // whenever no rule sets employer_adds_to_tax_base, true for every existing illustrative rule).
    StatutoryRule reliefRule = ruleByCalc(input.resolvedRules(), StatutoryCalcType.RELIEF_TABLE);
    for (PayComponent component : statutoryByCalc(input, StatutoryCalcType.PROGRESSIVE_BRACKET)) {
      StatutoryRule rule = input.resolvedRules().get(component.getStatutoryRuleKey());
      if (rule == null) {
        continue;
      }
      usesIllustrative |= rule.isIllustrative();
      StatutoryParams.ProgressiveParams brackets =
          StatutoryParams.progressive(rule.getParamsJson());

      int months = 12;
      Money relief = zero;
      if (reliefRule != null) {
        usesIllustrative |= reliefRule.isIllustrative();
        StatutoryParams.ReliefParams reliefParams =
            StatutoryParams.relief(reliefRule.getParamsJson());
        months = reliefParams.annualizationMonths();
        long reliefMinor = reliefParams.ptkpByStatus().getOrDefault(input.ptkpStatus().name(), 0L);
        relief = Money.ofMinor(reliefMinor, currency);
      }

      // Period taxable income for the tax = gross bruto minus deductible social legs.
      Money periodTaxable = grossBruto.minus(deductibleSocial);
      // Annualize, subtract relief, floor at zero.
      Money annualTaxable = periodTaxable.multiply(months).minus(relief);
      Money annualTax = walkBrackets(annualTaxable, brackets.brackets(), currency);
      // De-annualize back to the period.
      Money periodTax = annualTax.mulDiv(1L, months);

      lines.add(
          new ComputedLine(
              component, periodTax, periodTaxable, rule.getRuleVersion(), rule.isIllustrative()));
      // Income tax is always an EMPLOYEE deduction.
      employeeDeductions = employeeDeductions.plus(periodTax);
    }

    // 3b. TER_TABLE — PMK 168/2023 monthly effective-rate-on-WHOLE-gross lookup. No annualization,
    // no PTKP subtraction, no biaya jabatan.
    //
    // Track P Phase P8 (ADR 0035) — the THR-month TER interplay: PMK 168/2023's masa-pajak mandate
    // applies TER to the MONTH's TOTAL gross bruto, not just THIS run's own. When a sibling run
    // (the OTHER RunType, SAME period) is already ACTIVE, `input.siblingContext()` carries its
    // gross bruto + already-withheld PPh21 (PayrollRunWriter#siblingPeriodContext); the effective
    // rate is looked up on the COMBINED gross, the combined tax computed on the COMBINED gross, and
    // the sibling's already-withheld amount subtracted to yield THIS run's own withholding delta
    // (which MAY be negative, mirroring the December true-up's refund-line precedent). When no
    // sibling is active (the overwhelming common case, and every pre-P8 run), `siblingContext()` is
    // null and combinedGrossBruto == grossBruto / siblingWithheld == zero — byte-identical to the
    // pre-P8 branch. This is ORDER-INVARIANT: whichever of a THR/REGULAR pair for a period computes
    // SECOND nets out to total withheld = TER_rate(combinedGrossBruto) x combinedGrossBruto
    // regardless of which one ran first (verified both orders, Track P Phase P8 tests).
    for (PayComponent component : statutoryByCalc(input, StatutoryCalcType.TER_TABLE)) {
      StatutoryRule rule = input.resolvedRules().get(component.getStatutoryRuleKey());
      if (rule == null) {
        continue;
      }
      usesIllustrative |= rule.isIllustrative();
      StatutoryParams.TerTableParams params = StatutoryParams.terTable(rule.getParamsJson());
      StatutoryParams.TerCategoryTable category = params.categoryFor(input.ptkpStatus());

      Money siblingGrossBruto = zero;
      Money siblingWithheld = zero;
      if (input.siblingContext() != null) {
        siblingGrossBruto = Money.ofMinor(input.siblingContext().grossBrutoMinor(), currency);
        siblingWithheld = Money.ofMinor(input.siblingContext().withheldMinor(), currency);
      }
      Money combinedGrossBruto = grossBruto.plus(siblingGrossBruto);

      int rateBp = category.rateBpFor(combinedGrossBruto.amountMinor());
      Money combinedTax = combinedGrossBruto.applyBasisPoints(rateBp);
      combinedTax = applyNoNpwpSurcharge(combinedTax, params.noNpwpSurchargeBp(), input.hasNpwp());
      Money tax = combinedTax.minus(siblingWithheld);

      lines.add(
          new ComputedLine(
              component, tax, combinedGrossBruto, rule.getRuleVersion(), rule.isIllustrative()));
      employeeDeductions = employeeDeductions.plus(tax);
    }

    // 3c. ANNUAL_PROGRESSIVE — the December/final-month Art-17 true-up (needs a non-null
    // AnnualContext; a resolved rule without one is a misconfiguration and fails loudly rather than
    // silently skip the true-up or compute against garbage).
    for (PayComponent component : statutoryByCalc(input, StatutoryCalcType.ANNUAL_PROGRESSIVE)) {
      StatutoryRule rule = input.resolvedRules().get(component.getStatutoryRuleKey());
      if (rule == null) {
        continue;
      }
      AnnualContext context = input.annualContext();
      if (context == null) {
        throw new IllegalStateException(
            "Statutory component '"
                + component.getComponentKey()
                + "' resolved an ANNUAL_PROGRESSIVE rule but no AnnualContext was supplied for"
                + " employee "
                + input.employeeId()
                + " — the December/final-month true-up requires the writer to inject year-to-date"
                + " figures (Track P phase P3)");
      }
      usesIllustrative |= rule.isIllustrative();
      StatutoryParams.AnnualProgressiveParams params =
          StatutoryParams.annualProgressive(rule.getParamsJson());

      Money annualGross =
          Money.ofMinor(context.cumulativeGrossBrutoMinor(), currency).plus(grossBruto);
      // Biaya jabatan (occupational cost) cap is PRORATED by monthsInYear (Track P phase P3 W2):
      // PMK 250/2008's Rp500,000/month figure is inherently monthly (the annual cap is simply
      // 12 * that), so a partial-year joiner's cap must scale down with them — an unprorated FULL
      // annual cap would let a 2-month joiner deduct a whole year's occupational-cost allowance.
      // monthsInYear = 12 (a full-year employee) divides back to the unprorated cap exactly
      // (identity), so this is byte-identical to the pre-W2 behaviour for the common case. PTKP
      // relief below is deliberately NOT prorated (see AnnualContext#monthsInYear Javadoc).
      Money occupationalCostCap =
          Money.ofMinor(params.occupationalCostCapAnnualMinor(), currency)
              .mulDiv(context.monthsInYear(), 12L);
      Money occupationalCost =
          annualGross.applyBasisPoints(params.occupationalCostBp()).min(occupationalCostCap);
      Money cumulativeDeductibleSocial =
          Money.ofMinor(context.cumulativeDeductibleSocialMinor(), currency);
      // Pengurang: biaya jabatan (capped) + JHT/JP-EE contributions Jan..(month-1) AND this month.
      Money pengurang = occupationalCost.plus(cumulativeDeductibleSocial).plus(deductibleSocial);

      Money relief = zero;
      if (reliefRule != null) {
        usesIllustrative |= reliefRule.isIllustrative();
        StatutoryParams.ReliefParams reliefParams =
            StatutoryParams.relief(reliefRule.getParamsJson());
        long reliefMinor = reliefParams.ptkpByStatus().getOrDefault(input.ptkpStatus().name(), 0L);
        relief = Money.ofMinor(reliefMinor, currency);
      }

      Money netAnnual = annualGross.minus(pengurang).minus(relief);
      if (netAnnual.isNegative()) {
        netAnnual = zero;
      }
      Money pkp = Money.ofMinor(floorToNearest(netAnnual.amountMinor(), PKP_FLOOR_UNIT), currency);

      Money annualLiability = walkBrackets(pkp, params.brackets(), currency);
      annualLiability =
          applyNoNpwpSurcharge(annualLiability, params.noNpwpSurchargeBp(), input.hasNpwp());

      Money cumulativeWithheld = Money.ofMinor(context.cumulativeWithheldMinor(), currency);
      // MAY be negative — an over-withheld employee gets a refund line this month.
      Money thisMonthLine = annualLiability.minus(cumulativeWithheld);

      lines.add(
          new ComputedLine(
              component, thisMonthLine, pkp, rule.getRuleVersion(), rule.isIllustrative()));
      employeeDeductions = employeeDeductions.plus(thisMonthLine);
    }

    // ---- 4. OTHER DEDUCTIONS ----------------------------------------------
    for (DeductionInput deduction : input.otherDeductions()) {
      Money amount = requireBase(deduction.amount(), input);
      lines.add(new ComputedLine(deduction.component(), amount, amount, null, false));
      if (deduction.component().getBearer() == PayComponentBearer.EMPLOYEE) {
        employeeDeductions = employeeDeductions.plus(amount);
      } else {
        employerContributions = employerContributions.plus(amount);
      }
    }

    // ---- 5. NET -----------------------------------------------------------
    Money net = grossEarnings.minus(employeeDeductions);

    return new PersonResult(
        input.employeeId(),
        List.copyOf(lines),
        grossEarnings,
        employeeDeductions,
        employerContributions,
        net,
        usesIllustrative);
  }

  /**
   * The two-pass tax-base assembly's classification of ONE {@code PERCENTAGE_CEILING} leg (Track P
   * phase P1's BPJS tax-treatment matrix): an EMPLOYEE-borne leg may REDUCE the tax base ({@code
   * reduces_tax_base}), an EMPLOYER-borne leg may ADD a taxable notional-fringe-benefit amount
   * ({@code employer_adds_to_tax_base}) — never both, and a leg matching neither flag contributes
   * zero to both.
   *
   * @param deductibleSocialDelta the EMPLOYEE-leg amount to add to {@code deductibleSocial} (zero
   *     unless {@code bearer == EMPLOYEE && params.reducesTaxBase()})
   * @param employerTaxableAdditionDelta the EMPLOYER-leg amount to add to {@code
   *     employerTaxableAdditions}/{@code grossBruto} (zero unless {@code bearer == EMPLOYER &&
   *     params.employerAddsToTaxBase()})
   */
  record CeilingLegTaxBaseContribution(
      Money deductibleSocialDelta, Money employerTaxableAdditionDelta) {}

  /**
   * Classifies one resolved {@code PERCENTAGE_CEILING} leg's tax-base contribution — see {@link
   * CeilingLegTaxBaseContribution}. Package-private (not {@code private}): {@link
   * PayrollReportReader}'s statutory reports reuse this EXACT classification (an injected {@link
   * GrossToNetCalculator} instance), resolving the CURRENT active rule for a historical line's
   * CURRENT catalog component, so {@code grossBruto} in a report can NEVER drift from what {@link
   * #compute} itself would have assembled for the SAME rule — this is the "one tax source of truth"
   * the class javadoc claims, made literally true by extracting the SAME method both callers invoke
   * (the W1 review finding this closes: a report that summed taxable EARNING lines only, silently
   * dropping the notional employer premiums BPJS_KES_ER/JKK_ER/JKM_ER that {@link #compute} DOES
   * fold into {@code grossBruto}, understated bruto for every taxpayer).
   */
  CeilingLegTaxBaseContribution classifyCeilingLegForTaxBase(
      PayComponentBearer bearer, StatutoryParams.CeilingParams params, Money legAmount) {
    Money zero = Money.ofMinor(0L, legAmount.currency());
    if (bearer == PayComponentBearer.EMPLOYEE) {
      return new CeilingLegTaxBaseContribution(params.reducesTaxBase() ? legAmount : zero, zero);
    }
    return new CeilingLegTaxBaseContribution(
        zero, params.employerAddsToTaxBase() ? legAmount : zero);
  }

  /**
   * Walks the sorted brackets accumulating tax: for each {@code [floor, cap, rate_bp]} while {@code
   * income > floor}, {@code tax += round((min(income,cap) - floor) * rate_bp / 10000)}. The
   * multiply-then-divide rounds once (HALF_EVEN) per bracket.
   *
   * <p>Package-private (not {@code private}): {@link PayrollReportReader}'s 1721-A1 export reuses
   * this EXACT bracket walk (an injected {@link GrossToNetCalculator} instance) to compute the
   * annual PPh21 liability from a reconstructed annual PKP — the same reasoning as {@code
   * PayrollRunWriter#classifyPayslipLine} being shared rather than re-derived: one bracket-walking
   * implementation, never two that could silently drift apart on this sensitive tax math.
   */
  Money walkBrackets(Money income, List<StatutoryParams.Bracket> brackets, Currency currency) {
    Money tax = Money.ofMinor(0L, currency);
    long incomeMinor = income.amountMinor();
    if (incomeMinor <= 0L) {
      return tax;
    }
    // Safety: the brackets are sorted ascending; the LAST one's cap must cover the highest income,
    // otherwise income above the top cap would be silently UNTAXED (a misconfigured OFFICIAL table
    // could leave high earners untaxed). Reject loudly rather than under-tax.
    long topCap = brackets.get(brackets.size() - 1).capMinor();
    if (incomeMinor > topCap) {
      throw new IllegalStateException(
          "Annualized taxable income exceeds the top progressive bracket cap ("
              + topCap
              + "); the top bracket must be effectively unbounded so high income is not left"
              + " untaxed — fix the bracket table");
    }
    for (StatutoryParams.Bracket bracket : brackets) {
      if (incomeMinor <= bracket.floorMinor()) {
        break;
      }
      long upper = Math.min(incomeMinor, bracket.capMinor());
      long span = upper - bracket.floorMinor();
      if (span <= 0L) {
        continue;
      }
      Money bandAmount = Money.ofMinor(span, currency);
      tax = tax.plus(bandAmount.applyBasisPoints(bracket.rateBp()));
    }
    return tax;
  }

  /**
   * Applies the UU PPh Art 21(5a) no-NPWP surcharge (typically {@code 12000} bp = 120%) to a
   * computed tax amount when the employee has no NPWP on file; returns the amount unchanged when
   * they do. Package-private — see {@link #walkBrackets} for why this is shared, not re-derived.
   */
  Money applyNoNpwpSurcharge(Money amount, int surchargeBp, boolean hasNpwp) {
    return hasNpwp ? amount : amount.mulDiv(surchargeBp, BP_DENOMINATOR);
  }

  /**
   * Floors a non-negative minor-unit amount to the nearest multiple of {@code unit} (PKP rounding).
   * Package-private — see {@link #walkBrackets} for why this is shared, not re-derived.
   */
  long floorToNearest(long amountMinor, long unit) {
    return (amountMinor / unit) * unit;
  }

  private long legBp(PayComponent component, StatutoryParams.CeilingParams params) {
    return component.getBearer() == PayComponentBearer.EMPLOYEE
        ? params.employeeBp()
        : params.employerBp();
  }

  /** Statutory components whose linked rule has the given calc type, in display order. */
  private List<PayComponent> statutoryByCalc(PersonInput input, StatutoryCalcType calcType) {
    List<PayComponent> selected = new ArrayList<>();
    for (PayComponent component : input.statutoryComponents()) {
      StatutoryRule rule = input.resolvedRules().get(component.getStatutoryRuleKey());
      if (rule != null && rule.getCalcType() == calcType) {
        selected.add(component);
      }
    }
    return selected;
  }

  private StatutoryRule ruleByCalc(Map<String, StatutoryRule> rules, StatutoryCalcType calcType) {
    return rules.values().stream()
        .filter(r -> r.getCalcType() == calcType)
        .findFirst()
        .orElse(null);
  }

  private Money requireBase(Money amount, PersonInput input) {
    amount.requireSameCurrencyAs(input.basePay());
    return amount;
  }

  static long bpDenominator() {
    return BP_DENOMINATOR;
  }
}
