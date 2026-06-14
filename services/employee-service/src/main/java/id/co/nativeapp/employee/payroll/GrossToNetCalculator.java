package id.co.nativeapp.employee.payroll;

import id.co.nativeapp.employee.payroll.PayrollInputs.DeductionInput;
import id.co.nativeapp.employee.payroll.PayrollInputs.EarningInput;
import id.co.nativeapp.employee.payroll.PayrollInputs.PersonInput;
import id.co.nativeapp.employee.payroll.PayrollResult.ComputedLine;
import id.co.nativeapp.employee.payroll.PayrollResult.PersonResult;
import id.co.nativeapp.money.Money;
import java.util.ArrayList;
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
 *   <li>EARNINGS — base + each earning; produce gross_earnings and a taxable_base.
 *   <li>SOCIAL INSURANCE — percentage-with-ceiling rules; employee leg (DEDUCTION) + employer leg
 *       (EMPLOYER-borne, not net); some legs reduce the income-tax base.
 *   <li>INCOME TAX — progressive brackets on annualized (taxable - deductible_social - PTKP
 *       relief), de-annualized; an EMPLOYEE deduction.
 *   <li>OTHER DEDUCTIONS — non-statutory employee deductions.
 *   <li>NET = gross_earnings - sum(EMPLOYEE-bearing deductions).
 * </ol>
 */
@Component
public class GrossToNetCalculator {

  private static final long BP_DENOMINATOR = 10_000L;

  /** Computes the full per-person result deterministically. */
  public PersonResult compute(PersonInput input) {
    Money zero = Money.ofMinor(0L, input.basePay().currency());
    List<ComputedLine> lines = new ArrayList<>();
    boolean usesIllustrative = false;

    // ---- 1. EARNINGS -------------------------------------------------------
    Money grossEarnings = zero;
    Money taxableBase = zero;

    // BASE line.
    lines.add(
        new ComputedLine(input.baseComponent(), input.basePay(), input.basePay(), null, false));
    grossEarnings = grossEarnings.plus(input.basePay());
    if (input.baseComponent().isTaxable()) {
      taxableBase = taxableBase.plus(input.basePay());
    }

    // Other earnings (allowances, commission, metric-driven).
    for (EarningInput earning : input.earnings()) {
      Money amount = requireBase(earning.amount(), input);
      lines.add(new ComputedLine(earning.component(), amount, amount, null, false));
      grossEarnings = grossEarnings.plus(amount);
      if (earning.taxable()) {
        taxableBase = taxableBase.plus(amount);
      }
    }

    // ---- 2. SOCIAL INSURANCE (percentage-with-ceiling) --------------------
    Money employerContributions = zero;
    Money employeeDeductions = zero;
    Money deductibleSocial = zero; // social legs that reduce the income-tax base

    for (PayComponent component : statutoryByCalc(input, StatutoryCalcType.PERCENTAGE_CEILING)) {
      StatutoryRule rule = input.resolvedRules().get(component.getStatutoryRuleKey());
      if (rule == null) {
        continue;
      }
      usesIllustrative |= rule.isIllustrative();
      StatutoryParams.CeilingParams params = StatutoryParams.ceiling(rule.getParamsJson());
      Money ceiling = Money.ofMinor(params.ceilingMinor(), input.basePay().currency());
      Money cappedBase = taxableBase.min(ceiling);

      Money leg = cappedBase.applyBasisPoints(legBp(component, params));
      ComputedLine line =
          new ComputedLine(
              component, leg, cappedBase, rule.getRuleVersion(), rule.isIllustrative());
      lines.add(line);

      if (component.getBearer() == PayComponentBearer.EMPLOYEE) {
        employeeDeductions = employeeDeductions.plus(leg);
        if (params.reducesTaxBase()) {
          deductibleSocial = deductibleSocial.plus(leg);
        }
      } else {
        employerContributions = employerContributions.plus(leg);
      }
    }

    // ---- 3. INCOME TAX (progressive brackets + PTKP relief) ---------------
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
      Money relief = Money.ofMinor(0L, input.basePay().currency());
      if (reliefRule != null) {
        usesIllustrative |= reliefRule.isIllustrative();
        StatutoryParams.ReliefParams reliefParams =
            StatutoryParams.relief(reliefRule.getParamsJson());
        months = reliefParams.annualizationMonths();
        long reliefMinor = reliefParams.ptkpByStatus().getOrDefault(input.ptkpStatus().name(), 0L);
        relief = Money.ofMinor(reliefMinor, input.basePay().currency());
      }

      // Period taxable income for the tax = taxable base minus deductible social legs.
      Money periodTaxable = taxableBase.minus(deductibleSocial);
      // Annualize, subtract relief, floor at zero.
      Money annualTaxable = periodTaxable.multiply(months).minus(relief);
      Money annualTax =
          walkBrackets(annualTaxable, brackets.brackets(), input.basePay().currency());
      // De-annualize back to the period.
      Money periodTax = annualTax.mulDiv(1L, months);

      lines.add(
          new ComputedLine(
              component, periodTax, periodTaxable, rule.getRuleVersion(), rule.isIllustrative()));
      // Income tax is always an EMPLOYEE deduction.
      employeeDeductions = employeeDeductions.plus(periodTax);
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
   * Walks the sorted brackets accumulating tax: for each {@code [floor, cap, rate_bp]} while {@code
   * income > floor}, {@code tax += round((min(income,cap) - floor) * rate_bp / 10000)}. The
   * multiply-then-divide rounds once (HALF_EVEN) per bracket.
   */
  private Money walkBrackets(
      Money income, List<StatutoryParams.Bracket> brackets, java.util.Currency currency) {
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
