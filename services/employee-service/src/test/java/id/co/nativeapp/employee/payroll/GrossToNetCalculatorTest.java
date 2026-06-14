package id.co.nativeapp.employee.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import id.co.nativeapp.employee.employee.PtkpStatus;
import id.co.nativeapp.employee.payroll.PayrollInputs.EarningInput;
import id.co.nativeapp.employee.payroll.PayrollInputs.PersonInput;
import id.co.nativeapp.employee.payroll.PayrollResult.ComputedLine;
import id.co.nativeapp.employee.payroll.PayrollResult.PersonResult;
import id.co.nativeapp.money.Money;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Gross-to-net correctness against a KNOWN illustrative rule set, asserting EXACT minor-unit
 * outputs including the progressive-bracket + ceiling boundaries and HALF_EVEN rounding. The engine
 * is pure (no Spring/DB), so this is a fast, deterministic unit test — and it doubles as the
 * machinery-vs-numbers proof: the test supplies the NUMBERS as data; the calculator supplies only
 * the ALGORITHM.
 */
class GrossToNetCalculatorTest {

  private static final String IDR = "IDR";
  private final GrossToNetCalculator calculator = new GrossToNetCalculator();

  private PayComponent component(
      String key,
      PayComponentKind kind,
      CalcType calcType,
      PayComponentBearer bearer,
      boolean taxable,
      String ruleKey,
      int order) {
    return new PayComponent(key, kind, calcType, bearer, "GL-" + key, taxable, ruleKey, order);
  }

  private StatutoryRule rule(
      String key, StatutoryCalcType calcType, String params, RuleProvenance provenance) {
    return new StatutoryRule(
        key,
        "ILLUSTRATIVE-2026.1",
        calcType,
        params,
        IDR,
        provenance,
        "test",
        LocalDate.of(2026, 1, 1),
        StatutoryRule.OPEN_ENDED);
  }

  /** The illustrative rule set used across the assertions. */
  private Map<String, StatutoryRule> illustrativeRules() {
    return Map.of(
        "BPJS_KESEHATAN",
            rule(
                "BPJS_KESEHATAN",
                StatutoryCalcType.PERCENTAGE_CEILING,
                "{\"ceiling_minor\":10000000,\"employee_bp\":100,\"employer_bp\":400,"
                    + "\"reduces_tax_base\":true}",
                RuleProvenance.ILLUSTRATIVE_PLACEHOLDER),
        "PPH21_PROGRESSIVE",
            rule(
                "PPH21_PROGRESSIVE",
                StatutoryCalcType.PROGRESSIVE_BRACKET,
                "{\"brackets\":[{\"floor_minor\":0,\"cap_minor\":50000000,\"rate_bp\":1000},"
                    + "{\"floor_minor\":50000000,\"cap_minor\":999999999999,\"rate_bp\":1500}]}",
                RuleProvenance.ILLUSTRATIVE_PLACEHOLDER),
        "PTKP_RELIEF",
            rule(
                "PTKP_RELIEF",
                StatutoryCalcType.RELIEF_TABLE,
                "{\"annualization_months\":12,\"ptkp\":{\"TK0\":54000000,\"K1\":63000000}}",
                RuleProvenance.ILLUSTRATIVE_PLACEHOLDER));
  }

  private List<PayComponent> statutoryComponents() {
    return List.of(
        component(
            "BPJS_KES_EE",
            PayComponentKind.DEDUCTION,
            CalcType.STATUTORY_CEILING,
            PayComponentBearer.EMPLOYEE,
            false,
            "BPJS_KESEHATAN",
            20),
        component(
            "BPJS_KES_ER",
            PayComponentKind.DEDUCTION,
            CalcType.STATUTORY_CEILING,
            PayComponentBearer.EMPLOYER,
            false,
            "BPJS_KESEHATAN",
            21),
        component(
            "PPH21",
            PayComponentKind.DEDUCTION,
            CalcType.STATUTORY_PROGRESSIVE,
            PayComponentBearer.EMPLOYEE,
            false,
            "PPH21_PROGRESSIVE",
            30));
  }

  private PayComponent baseComponent() {
    return component(
        "BASE",
        PayComponentKind.EARNING,
        CalcType.FIXED,
        PayComponentBearer.EMPLOYEE,
        true,
        null,
        0);
  }

  @Test
  void computesExactGrossToNetForAKnownCase() {
    Money base = Money.ofMinor(20_000_000L, IDR);
    PersonInput input =
        new PersonInput(
            UUID.randomUUID(),
            PtkpStatus.TK0,
            baseComponent(),
            base,
            List.of(),
            statutoryComponents(),
            List.of(),
            illustrativeRules());

    PersonResult result = calculator.compute(input);

    // Gross = base.
    assertThat(result.grossEarnings()).isEqualTo(Money.ofMinor(20_000_000L, IDR));

    // BPJS: capped at the 10,000,000 ceiling (boundary). EE 1% = 100,000; ER 4% = 400,000.
    Money bpjsEe = lineAmount(result, "BPJS_KES_EE");
    Money bpjsEr = lineAmount(result, "BPJS_KES_ER");
    assertThat(bpjsEe).isEqualTo(Money.ofMinor(100_000L, IDR));
    assertThat(bpjsEr).isEqualTo(Money.ofMinor(400_000L, IDR));

    // PPh21: periodTaxable = 20,000,000 - 100,000 (deductible BPJS EE) = 19,900,000.
    // annual = 19,900,000*12 - 54,000,000(TK0) = 184,800,000.
    // tax = 50,000,000*10% + 134,800,000*15% = 5,000,000 + 20,220,000 = 25,220,000.
    // period = 25,220,000 / 12 = 2,101,666.67 -> HALF_EVEN -> 2,101,667.
    Money pph21 = lineAmount(result, "PPH21");
    assertThat(pph21).isEqualTo(Money.ofMinor(2_101_667L, IDR));

    // Net = gross - (BPJS EE + PPh21) = 20,000,000 - 2,201,667 = 17,798,333.
    assertThat(result.employeeDeductions()).isEqualTo(Money.ofMinor(2_201_667L, IDR));
    assertThat(result.net()).isEqualTo(Money.ofMinor(17_798_333L, IDR));

    // Employer contributions (NOT net): only the employer BPJS leg.
    assertThat(result.employerContributions()).isEqualTo(Money.ofMinor(400_000L, IDR));

    // Illustrative flag propagated.
    assertThat(result.usesIllustrativeRules()).isTrue();

    // Statutory lines stamp the rule version (HR-7).
    assertThat(line(result, "PPH21").ruleVersion()).isEqualTo("ILLUSTRATIVE-2026.1");
    assertThat(line(result, "PPH21").illustrative()).isTrue();
  }

  @Test
  void incomeBelowReliefProducesZeroTaxAtTheBracketFloorBoundary() {
    // base 4,000,000 * 12 = 48,000,000 annual < 54,000,000 TK0 relief -> taxable below floor -> 0
    // tax.
    Money base = Money.ofMinor(4_000_000L, IDR);
    PersonInput input =
        new PersonInput(
            UUID.randomUUID(),
            PtkpStatus.TK0,
            baseComponent(),
            base,
            List.of(),
            statutoryComponents(),
            List.of(),
            illustrativeRules());

    PersonResult result = calculator.compute(input);

    // BPJS capped at base (4,000,000 < ceiling): EE 40,000, ER 160,000.
    assertThat(lineAmount(result, "BPJS_KES_EE")).isEqualTo(Money.ofMinor(40_000L, IDR));
    assertThat(lineAmount(result, "BPJS_KES_ER")).isEqualTo(Money.ofMinor(160_000L, IDR));
    // annual taxable = (4,000,000 - 40,000)*12 - 54,000,000 = 47,520,000 - 54,000,000 < 0 -> 0 tax.
    assertThat(lineAmount(result, "PPH21")).isEqualTo(Money.ofMinor(0L, IDR));
    assertThat(result.net()).isEqualTo(Money.ofMinor(3_960_000L, IDR)); // gross - BPJS EE
  }

  @Test
  void taxableIncomeWithinTheFirstBracketUsesOnlyTheTenPercentRate() {
    // Pick base so annual taxable lands strictly inside the first (10%) bracket, below 50,000,000.
    // base 6,000,000: BPJS EE 60,000; periodTaxable 5,940,000; annual 71,280,000 - 54,000,000(TK0)
    //   = 17,280,000 (< 50,000,000 -> only 10%). tax annual = 1,728,000; period = 144,000.
    Money base = Money.ofMinor(6_000_000L, IDR);
    PersonInput input =
        new PersonInput(
            UUID.randomUUID(),
            PtkpStatus.TK0,
            baseComponent(),
            base,
            List.of(),
            statutoryComponents(),
            List.of(),
            illustrativeRules());

    PersonResult result = calculator.compute(input);
    assertThat(lineAmount(result, "PPH21")).isEqualTo(Money.ofMinor(144_000L, IDR));
  }

  @Test
  void taxableEarningRaisesTheTaxableBaseButNonTaxableDoesNot() {
    Money base = Money.ofMinor(20_000_000L, IDR);
    PayComponent taxableAllowance =
        component(
            "MEAL",
            PayComponentKind.EARNING,
            CalcType.FIXED,
            PayComponentBearer.EMPLOYEE,
            true,
            null,
            10);
    PayComponent nonTaxableAllowance =
        component(
            "TRANSPORT",
            PayComponentKind.EARNING,
            CalcType.FIXED,
            PayComponentBearer.EMPLOYEE,
            false,
            null,
            11);
    PersonInput input =
        new PersonInput(
            UUID.randomUUID(),
            PtkpStatus.TK0,
            baseComponent(),
            base,
            List.of(
                new EarningInput(taxableAllowance, Money.ofMinor(1_000_000L, IDR)),
                new EarningInput(nonTaxableAllowance, Money.ofMinor(2_000_000L, IDR))),
            statutoryComponents(),
            List.of(),
            illustrativeRules());

    PersonResult result = calculator.compute(input);
    // Gross includes both allowances.
    assertThat(result.grossEarnings()).isEqualTo(Money.ofMinor(23_000_000L, IDR));
    // The non-taxable allowance must NOT raise PPh21 vs a taxable-only scenario — assert tax
    // reflects
    // taxable base 21,000,000 (base + meal), not 23,000,000.
    // periodTaxable = 21,000,000 - 100,000 = 20,900,000; annual = 250,800,000 - 54,000,000
    //   = 196,800,000; tax = 5,000,000 + (146,800,000*15%)=22,020,000 -> 27,020,000; /12 =
    // 2,251,666.67
    //   -> 2,251,667.
    assertThat(lineAmount(result, "PPH21")).isEqualTo(Money.ofMinor(2_251_667L, IDR));
  }

  @Test
  void aTopBracketCapBelowTheIncomeFailsLoudlySoTopIncomeIsNeverLeftUntaxed() {
    // A MISCONFIGURED progressive table whose top bracket cap (100,000,000) is BELOW the annualized
    // taxable income would silently leave the income above the cap UNTAXED. The walker must reject
    // it rather than under-tax.
    Map<String, StatutoryRule> brokenRules =
        Map.of(
            "PPH21_PROGRESSIVE",
            rule(
                "PPH21_PROGRESSIVE",
                StatutoryCalcType.PROGRESSIVE_BRACKET,
                // No unbounded top bracket — caps out at 100,000,000.
                "{\"brackets\":[{\"floor_minor\":0,\"cap_minor\":100000000,\"rate_bp\":1000}]}",
                RuleProvenance.ILLUSTRATIVE_PLACEHOLDER));
    Money base = Money.ofMinor(50_000_000L, IDR); // annualizes to 600,000,000 >> 100,000,000 cap
    PersonInput input =
        new PersonInput(
            UUID.randomUUID(),
            PtkpStatus.TK0,
            baseComponent(),
            base,
            List.of(),
            List.of(
                component(
                    "PPH21",
                    PayComponentKind.DEDUCTION,
                    CalcType.STATUTORY_PROGRESSIVE,
                    PayComponentBearer.EMPLOYEE,
                    false,
                    "PPH21_PROGRESSIVE",
                    30)),
            List.of(),
            brokenRules);

    assertThatThrownBy(() -> calculator.compute(input))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("top progressive bracket cap");
  }

  private static ComputedLine line(PersonResult result, String key) {
    return result.lines().stream()
        .filter(l -> l.component().getComponentKey().equals(key))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no line for " + key));
  }

  private static Money lineAmount(PersonResult result, String key) {
    return line(result, key).amount();
  }
}
