package id.co.nativeapp.employee.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import id.co.nativeapp.employee.employee.domain.PtkpStatus;
import id.co.nativeapp.employee.payroll.domain.CalcType;
import id.co.nativeapp.employee.payroll.domain.PayComponent;
import id.co.nativeapp.employee.payroll.domain.PayComponentBearer;
import id.co.nativeapp.employee.payroll.domain.PayComponentKind;
import id.co.nativeapp.employee.payroll.domain.PayrollInputs.AnnualContext;
import id.co.nativeapp.employee.payroll.domain.PayrollInputs.EarningInput;
import id.co.nativeapp.employee.payroll.domain.PayrollInputs.PersonInput;
import id.co.nativeapp.employee.payroll.domain.RuleProvenance;
import id.co.nativeapp.employee.payroll.domain.StatutoryCalcType;
import id.co.nativeapp.employee.payroll.domain.StatutoryRule;
import id.co.nativeapp.employee.payroll.dto.PayrollResult.ComputedLine;
import id.co.nativeapp.employee.payroll.dto.PayrollResult.PersonResult;
import id.co.nativeapp.employee.payroll.service.GrossToNetCalculator;
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
            illustrativeRules(),
            true,
            null);

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
            illustrativeRules(),
            true,
            null);

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
            illustrativeRules(),
            true,
            null);

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
            illustrativeRules(),
            true,
            null);

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
  void aFiniteTopBracketCapIsRejectedAtLoadTimeSoTopIncomeIsNeverLeftUntaxed() {
    // A MISCONFIGURED progressive table whose top bracket cap (100,000,000) is FINITE (not the
    // agreed unbounded sentinel) would silently leave income above the cap UNTAXED. Loading the
    // rule
    // (StatutoryParams.progressive, invoked the moment the calculator resolves the table) now FAILS
    // LOUDLY at load time — fail-fast, regardless of the actual income — rather than only when a
    // high-enough earner trips the run-time walkBrackets net.
    Map<String, StatutoryRule> brokenRules =
        Map.of(
            "PPH21_PROGRESSIVE",
            rule(
                "PPH21_PROGRESSIVE",
                StatutoryCalcType.PROGRESSIVE_BRACKET,
                // No unbounded top bracket — caps out at a finite 100,000,000.
                "{\"brackets\":[{\"floor_minor\":0,\"cap_minor\":100000000,\"rate_bp\":1000}]}",
                RuleProvenance.ILLUSTRATIVE_PLACEHOLDER));
    Money base = Money.ofMinor(50_000_000L, IDR);
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
            brokenRules,
            true,
            null);

    assertThatThrownBy(() -> calculator.compute(input))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unbounded sentinel");
  }

  // =========================================================================
  // TER_TABLE (PMK 168/2023) — Track P phase P1
  // =========================================================================

  private PayComponent pph21TerComponent() {
    return component(
        "PPH21",
        PayComponentKind.DEDUCTION,
        CalcType.STATUTORY_PROGRESSIVE,
        PayComponentBearer.EMPLOYEE,
        false,
        "PPH21_TER",
        30);
  }

  private PersonInput terInput(Money base, StatutoryRule terRule, boolean hasNpwp) {
    return new PersonInput(
        UUID.randomUUID(),
        PtkpStatus.TK0,
        baseComponent(),
        base,
        List.of(),
        List.of(pph21TerComponent()),
        List.of(),
        Map.of("PPH21_TER", terRule),
        hasNpwp,
        null);
  }

  @Test
  void
      terMonthlyPphAppliesTheEffectiveRateOnTheWholeGrossWithFirstBandGreaterThanOrEqualSelection() {
    // PMK 168/2023: an EFFECTIVE-rate lookup on the WHOLE gross, never a marginal walk. A toy
    // 3-band category table: 5% up to 5,000,000; 10% up to 10,000,000; 15% unbounded.
    String terJson =
        "{\"ptkp_category\":{\"TK0\":\"A\",\"TK1\":\"A\",\"TK2\":\"A\",\"TK3\":\"A\","
            + "\"K0\":\"A\",\"K1\":\"A\",\"K2\":\"A\",\"K3\":\"A\"},"
            + "\"categories\":{\"A\":{\"bands\":["
            + "{\"up_to_minor\":5000000,\"rate_bp\":500},"
            + "{\"up_to_minor\":10000000,\"rate_bp\":1000},"
            + "{\"up_to_minor\":999999999999,\"rate_bp\":1500}]}},"
            + "\"no_npwp_surcharge_bp\":12000}";
    StatutoryRule terRule =
        rule(
            "PPH21_TER",
            StatutoryCalcType.TER_TABLE,
            terJson,
            RuleProvenance.ILLUSTRATIVE_PLACEHOLDER);

    // Exactly at the first band's edge -> stays in band 1 (first band, ascending, whose
    // up_to_minor >= gross).
    PersonResult atEdge =
        calculator.compute(terInput(Money.ofMinor(5_000_000L, IDR), terRule, true));
    assertThat(lineAmount(atEdge, "PPH21")).isEqualTo(Money.ofMinor(250_000L, IDR)); // 5%

    // Just above the first edge -> band 2, and the WHOLE gross is multiplied (proof it is NOT a
    // marginal walk: a marginal split over [0,5M]@5% would leave only the span above 5M at 10%).
    PersonResult justAbove =
        calculator.compute(terInput(Money.ofMinor(6_000_000L, IDR), terRule, true));
    assertThat(lineAmount(justAbove, "PPH21")).isEqualTo(Money.ofMinor(600_000L, IDR)); // 10%

    // Exactly at the second band's edge -> a marginal walk of [0,5M]@5% + [5M,10M]@10% would give
    // 750,000; the effective-rate table instead taxes the WHOLE 10,000,000 at 10% = 1,000,000.
    PersonResult secondEdge =
        calculator.compute(terInput(Money.ofMinor(10_000_000L, IDR), terRule, true));
    assertThat(lineAmount(secondEdge, "PPH21")).isEqualTo(Money.ofMinor(1_000_000L, IDR));

    // Above every finite band -> the unbounded top band.
    PersonResult top = calculator.compute(terInput(Money.ofMinor(12_000_000L, IDR), terRule, true));
    assertThat(lineAmount(top, "PPH21")).isEqualTo(Money.ofMinor(1_800_000L, IDR)); // 15%

    // TER never annualizes / subtracts PTKP / biaya jabatan: net = gross - PPh21 only.
    assertThat(top.net())
        .isEqualTo(Money.ofMinor(12_000_000L, IDR).minus(Money.ofMinor(1_800_000L, IDR)));
  }

  @Test
  void terAppliesTheNoNpwpX120PercentSurchargeUnderUuPphArt21Section5a() {
    String terJson =
        "{\"ptkp_category\":{\"TK0\":\"A\",\"TK1\":\"A\",\"TK2\":\"A\",\"TK3\":\"A\","
            + "\"K0\":\"A\",\"K1\":\"A\",\"K2\":\"A\",\"K3\":\"A\"},"
            + "\"categories\":{\"A\":{\"bands\":[{\"up_to_minor\":999999999999,\"rate_bp\":1000}]}},"
            + "\"no_npwp_surcharge_bp\":12000}";
    StatutoryRule terRule =
        rule(
            "PPH21_TER",
            StatutoryCalcType.TER_TABLE,
            terJson,
            RuleProvenance.ILLUSTRATIVE_PLACEHOLDER);

    PersonResult withNpwp =
        calculator.compute(terInput(Money.ofMinor(10_000_000L, IDR), terRule, true));
    assertThat(lineAmount(withNpwp, "PPH21")).isEqualTo(Money.ofMinor(1_000_000L, IDR));

    PersonResult withoutNpwp =
        calculator.compute(terInput(Money.ofMinor(10_000_000L, IDR), terRule, false));
    assertThat(lineAmount(withoutNpwp, "PPH21")).isEqualTo(Money.ofMinor(1_200_000L, IDR)); // x1.20
  }

  // =========================================================================
  // Two-pass tax-base assembly (Track P phase P1, the BPJS tax-treatment matrix)
  // =========================================================================

  @Test
  void anEmployerLegFlaggedTaxableRaisesThePphTaxBaseButNeverGrossEarningsOrNet() {
    Money base = Money.ofMinor(20_000_000L, IDR);
    Map<String, StatutoryRule> rules =
        Map.of(
            "BPJS_KESEHATAN",
                rule(
                    "BPJS_KESEHATAN",
                    StatutoryCalcType.PERCENTAGE_CEILING,
                    "{\"ceiling_minor\":10000000,\"employee_bp\":100,\"employer_bp\":400,"
                        + "\"reduces_tax_base\":true,\"employer_adds_to_tax_base\":true}",
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
                    "{\"annualization_months\":12,\"ptkp\":{\"TK0\":54000000}}",
                    RuleProvenance.ILLUSTRATIVE_PLACEHOLDER));
    PersonInput input =
        new PersonInput(
            UUID.randomUUID(),
            PtkpStatus.TK0,
            baseComponent(),
            base,
            List.of(),
            statutoryComponents(),
            List.of(),
            rules,
            true,
            null);

    PersonResult result = calculator.compute(input);

    // Gross/net stay driven by CASH earnings only — the employer leg never touches them directly.
    assertThat(result.grossEarnings()).isEqualTo(Money.ofMinor(20_000_000L, IDR));
    assertThat(lineAmount(result, "BPJS_KES_ER")).isEqualTo(Money.ofMinor(400_000L, IDR));
    assertThat(result.employerContributions()).isEqualTo(Money.ofMinor(400_000L, IDR));

    // PPh21 is HIGHER than the golden baseline (2,101,667, computesExactGrossToNetForAKnownCase)
    // because the employer's 400,000 BPJS-Kes-ER leg now raises the TAX BASE (a notional taxable
    // fringe benefit): grossBruto = 20,000,000 + 400,000 = 20,400,000; periodTaxable =
    // 20,400,000 - 100,000(EE) = 20,300,000; annual = 243,600,000 - 54,000,000 = 189,600,000;
    // tax = 50,000,000*10% + 139,600,000*15% = 5,000,000 + 20,940,000 = 25,940,000;
    // period = 25,940,000/12 = 2,161,666.67 -> HALF_EVEN -> 2,161,667.
    assertThat(lineAmount(result, "PPH21")).isEqualTo(Money.ofMinor(2_161_667L, IDR));
    assertThat(result.net()).isEqualTo(Money.ofMinor(17_738_333L, IDR));
  }

  @Test
  void baseKindBasePayBasesTheSocialLegOnBasePayInsteadOfACommissionLadenTaxableGross() {
    Money base = Money.ofMinor(5_000_000L, IDR);
    PayComponent commission =
        component(
            "COMMISSION",
            PayComponentKind.EARNING,
            CalcType.FIXED,
            PayComponentBearer.EMPLOYEE,
            true,
            null,
            15);
    PayComponent jhtEe =
        component(
            "JHT_EE",
            PayComponentKind.DEDUCTION,
            CalcType.STATUTORY_CEILING,
            PayComponentBearer.EMPLOYEE,
            false,
            "BPJS_JHT",
            22);
    Map<String, StatutoryRule> rules =
        Map.of(
            "BPJS_JHT",
            rule(
                "BPJS_JHT",
                StatutoryCalcType.PERCENTAGE_CEILING,
                "{\"ceiling_minor\":999999999999,\"employee_bp\":200,\"employer_bp\":370,"
                    + "\"reduces_tax_base\":false,\"base_kind\":\"BASE_PAY\"}",
                RuleProvenance.ILLUSTRATIVE_PLACEHOLDER));
    PersonInput input =
        new PersonInput(
            UUID.randomUUID(),
            PtkpStatus.TK0,
            baseComponent(),
            base,
            List.of(new EarningInput(commission, Money.ofMinor(15_000_000L, IDR))),
            List.of(jhtEe),
            List.of(),
            rules,
            true,
            null);

    PersonResult result = calculator.compute(input);

    // Taxable gross (base + commission) is 20,000,000 — under the DEFAULT TAXABLE_GROSS base_kind
    // the 2% JHT-EE leg would be 400,000. base_kind=BASE_PAY instead bases it on the 5,000,000
    // base pay ONLY: 2% * 5,000,000 = 100,000 (needed so BPJS is on wage, not commission-laden
    // gross).
    assertThat(lineAmount(result, "JHT_EE")).isEqualTo(Money.ofMinor(100_000L, IDR));
    assertThat(result.grossEarnings()).isEqualTo(Money.ofMinor(20_000_000L, IDR));
  }

  // =========================================================================
  // ANNUAL_PROGRESSIVE — the December/final-month Art-17 true-up (Track P phases P1/P3)
  // =========================================================================

  private static final String ART17_ANNUAL_JSON =
      "{\"brackets\":["
          + "{\"floor_minor\":0,\"cap_minor\":60000000,\"rate_bp\":500},"
          + "{\"floor_minor\":60000000,\"cap_minor\":250000000,\"rate_bp\":1500},"
          + "{\"floor_minor\":250000000,\"cap_minor\":500000000,\"rate_bp\":2500},"
          + "{\"floor_minor\":500000000,\"cap_minor\":5000000000,\"rate_bp\":3000},"
          + "{\"floor_minor\":5000000000,\"cap_minor\":999999999999,\"rate_bp\":3500}],"
          + "\"occupational_cost_bp\":500,"
          + "\"occupational_cost_cap_annual_minor\":6000000,"
          + "\"no_npwp_surcharge_bp\":12000}";

  private PayComponent pph21AnnualComponent() {
    return component(
        "PPH21",
        PayComponentKind.DEDUCTION,
        CalcType.STATUTORY_PROGRESSIVE,
        PayComponentBearer.EMPLOYEE,
        false,
        "PPH21_ANNUAL",
        30);
  }

  private PersonInput annualInput(
      Money base, boolean hasNpwp, AnnualContext context, Map<String, StatutoryRule> rules) {
    return new PersonInput(
        UUID.randomUUID(),
        PtkpStatus.TK0,
        baseComponent(),
        base,
        List.of(),
        List.of(pph21AnnualComponent()),
        List.of(),
        rules,
        hasNpwp,
        context);
  }

  private Map<String, StatutoryRule> annualRules() {
    return Map.of(
        "PPH21_ANNUAL",
            rule(
                "PPH21_ANNUAL",
                StatutoryCalcType.ANNUAL_PROGRESSIVE,
                ART17_ANNUAL_JSON,
                RuleProvenance.ILLUSTRATIVE_PLACEHOLDER),
        "PTKP_RELIEF",
            rule(
                "PTKP_RELIEF",
                StatutoryCalcType.RELIEF_TABLE,
                "{\"annualization_months\":12,\"ptkp\":{\"TK0\":54000000}}",
                RuleProvenance.ILLUSTRATIVE_PLACEHOLDER));
  }

  @Test
  void decemberTrueUpCapsBiayaJabatanAtSixMillionAndProducesANegativeRefundLineWhenOverWithheld() {
    // annual_gross = 110,000,000 (Jan-Nov) + 10,000,000 (December) = 120,000,000; 5% biaya jabatan
    // = 6,000,000 exactly hits the PMK 250/2008 annual cap. pengurang = 6,000,000 + 2,000,000
    // (cumulative JHT/JP-EE); PTKP TK0 = 54,000,000 (PMK 101/2016). netAnnual = 120,000,000 -
    // 8,000,000 - 54,000,000 = 58,000,000 (already a round 1000). UU HPP 7/2021: 58,000,000 sits
    // wholly in the first (5%) bracket -> annual liability 2,900,000. Already-withheld Jan-Nov
    // (3,200,000) EXCEEDS the annual liability -> December's line is NEGATIVE (a refund).
    AnnualContext context = new AnnualContext(110_000_000L, 2_000_000L, 3_200_000L, 12);
    PersonInput input = annualInput(Money.ofMinor(10_000_000L, IDR), true, context, annualRules());

    PersonResult result = calculator.compute(input);

    assertThat(lineAmount(result, "PPH21")).isEqualTo(Money.ofMinor(-300_000L, IDR));
    // Net pay this month INCREASES by the refund (employeeDeductions includes the negative line).
    assertThat(result.net()).isEqualTo(Money.ofMinor(10_300_000L, IDR));
  }

  @Test
  void decemberTrueUpFloorsPkpToTheNearestThousand() {
    // annual_gross = 100,000,000 + 10,000,700 = 110,000,700; biaya jabatan (uncapped this time) =
    // 5,500,035; pengurang = 5,500,035 + 2,000,000 = 7,500,035; PTKP TK0 = 54,000,000. netAnnual =
    // 110,000,700 - 7,500,035 - 54,000,000 = 48,500,665 — NOT a round 1000, so UU HPP 7/2021's
    // floor-to-1000 PKP convention must drop the 665 minor units down to 48,500,000.
    AnnualContext context = new AnnualContext(100_000_000L, 2_000_000L, 2_000_000L, 12);
    PersonInput input = annualInput(Money.ofMinor(10_000_700L, IDR), true, context, annualRules());

    PersonResult result = calculator.compute(input);

    // The PPh21 line's calc_basis IS the floored PKP — direct proof of the floor.
    assertThat(line(result, "PPH21").calcBasis()).isEqualTo(Money.ofMinor(48_500_000L, IDR));
    // annual liability = 48,500,000 * 5% = 2,425,000; December line = 2,425,000 - 2,000,000.
    assertThat(lineAmount(result, "PPH21")).isEqualTo(Money.ofMinor(425_000L, IDR));
  }

  @Test
  void decemberTrueUpAppliesTheNoNpwpSurchargeToTheAnnualLiability() {
    // annual_gross = 70,000,000 (single month, no cumulative); biaya jabatan = 3,500,000; PTKP
    // TK0 = 54,000,000. netAnnual = 70,000,000 - 3,500,000 - 54,000,000 = 12,500,000 -> first
    // (5%) bracket -> liability 625,000. no_npwp_surcharge_bp (12000 = x1.20) applies to the
    // ANNUAL LIABILITY itself, exactly like the TER branch.
    AnnualContext context = new AnnualContext(0L, 0L, 0L, 1);

    PersonResult withNpwp =
        calculator.compute(
            annualInput(Money.ofMinor(70_000_000L, IDR), true, context, annualRules()));
    assertThat(lineAmount(withNpwp, "PPH21")).isEqualTo(Money.ofMinor(625_000L, IDR));

    PersonResult withoutNpwp =
        calculator.compute(
            annualInput(Money.ofMinor(70_000_000L, IDR), false, context, annualRules()));
    assertThat(lineAmount(withoutNpwp, "PPH21")).isEqualTo(Money.ofMinor(750_000L, IDR)); // x1.20
  }

  @Test
  void anAnnualProgressiveRuleWithNoAnnualContextFailsLoudlyRatherThanSkipTheTrueUp() {
    PersonInput input = annualInput(Money.ofMinor(10_000_000L, IDR), true, null, annualRules());

    assertThatThrownBy(() -> calculator.compute(input))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("AnnualContext");
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
