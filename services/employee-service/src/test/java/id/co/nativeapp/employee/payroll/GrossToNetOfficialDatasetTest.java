package id.co.nativeapp.employee.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.employee.employee.domain.PtkpStatus;
import id.co.nativeapp.employee.payroll.domain.CalcType;
import id.co.nativeapp.employee.payroll.domain.OfficialStatutoryDataset;
import id.co.nativeapp.employee.payroll.domain.OfficialStatutoryDataset.Dataset;
import id.co.nativeapp.employee.payroll.domain.OfficialStatutoryDataset.DatasetComponent;
import id.co.nativeapp.employee.payroll.domain.OfficialStatutoryDataset.DatasetRule;
import id.co.nativeapp.employee.payroll.domain.PayComponent;
import id.co.nativeapp.employee.payroll.domain.PayComponentBearer;
import id.co.nativeapp.employee.payroll.domain.PayComponentKind;
import id.co.nativeapp.employee.payroll.domain.PayrollInputs.PersonInput;
import id.co.nativeapp.employee.payroll.domain.StatutoryRule;
import id.co.nativeapp.employee.payroll.dto.PayrollResult.PersonResult;
import id.co.nativeapp.employee.payroll.service.GrossToNetCalculator;
import id.co.nativeapp.money.Money;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Calculate-on-OFFICIAL-rules proof (Track P phase P2, ADR 0031): loads the shipped {@code
 * ID-2026.1} dataset — exactly what {@code OfficialStatutorySeedWriter} would persist — and runs
 * the PURE {@link GrossToNetCalculator} for a 10,000,000 IDR TK/0 employee with an NPWP on file, at
 * the "unit context" the task allows (no Spring/DB needed: the calculator is a deterministic
 * function of its frozen rule set + person input, exactly as {@code PayrollRunWriter} would freeze
 * it).
 *
 * <p>Asserts every BPJS leg at its OFFICIAL rate/cap, the PPh21 TER path against the VERBATIM PMK
 * 168/2023 band table (biaya-jabatan-free: no {@code PPH21_ARTICLE17}/{@code BIAYA_JABATAN} line
 * ever appears because no pay_component links to them), and — the honest-posture proof, now flipped
 * — {@code usesIllustrativeRules} is {@code false} because every rule this run resolves (including
 * {@code PPH21_TER}, transcribed + cross-verified 2026-08-02) is OFFICIAL.
 */
class GrossToNetOfficialDatasetTest {

  private static final String IDR = "IDR";
  private final GrossToNetCalculator calculator = new GrossToNetCalculator();

  @Test
  void tenMillionTk0WithNpwpComputesEveryBpjsLegAtItsOfficialRateAndPphViaTheOfficialTerTable() {
    Dataset dataset = OfficialStatutoryDataset.load("ID-2026.1").orElseThrow();

    // Freeze the resolved rule set exactly as PayrollRunWriter.resolveStatutoryRules would.
    Map<String, StatutoryRule> resolvedRules = new LinkedHashMap<>();
    for (DatasetRule rule : dataset.rules()) {
      resolvedRules.put(
          rule.ruleKey(),
          new StatutoryRule(
              rule.ruleKey(),
              rule.ruleVersion(),
              rule.calcType(),
              rule.paramsJson(),
              IDR,
              rule.provenance(),
              rule.sourceNote(),
              rule.effectiveFrom(),
              StatutoryRule.OPEN_ENDED));
    }

    // Build the statutory pay-components exactly as a real tenant would carry them: the
    // ILLUSTRATIVE
    // catalog's BPJS_KES_EE/ER (the official dataset does not re-list them — they already exist
    // from
    // IllustrativeStatutorySeedWriter) PLUS the official dataset's 7 additions (proves fidelity
    // between the dataset's component-catalog section and what gets computed).
    List<PayComponent> statutoryComponents = new java.util.ArrayList<>();
    statutoryComponents.add(
        new PayComponent(
            "BPJS_KES_EE",
            PayComponentKind.DEDUCTION,
            CalcType.STATUTORY_CEILING,
            PayComponentBearer.EMPLOYEE,
            "2100-BPJS",
            false,
            "BPJS_KESEHATAN",
            20));
    statutoryComponents.add(
        new PayComponent(
            "BPJS_KES_ER",
            PayComponentKind.DEDUCTION,
            CalcType.STATUTORY_CEILING,
            PayComponentBearer.EMPLOYER,
            "5200-BPJS-ER",
            false,
            "BPJS_KESEHATAN",
            21));
    dataset.components().stream()
        .map(GrossToNetOfficialDatasetTest::toPayComponent)
        .forEach(statutoryComponents::add);
    PayComponent baseComponent =
        new PayComponent(
            "BASE",
            PayComponentKind.EARNING,
            CalcType.FIXED,
            PayComponentBearer.EMPLOYEE,
            "5100-SALARY",
            true,
            null,
            0);

    Money base = Money.ofMinor(10_000_000L, IDR);
    PersonInput input =
        new PersonInput(
            UUID.randomUUID(),
            PtkpStatus.TK0,
            baseComponent,
            base,
            List.of(),
            statutoryComponents,
            List.of(),
            resolvedRules,
            /* hasNpwp= */ true,
            /* annualContext= */ null);

    PersonResult result = calculator.compute(input);

    // ---- BPJS legs, each at its OFFICIAL rate, none capped at 10,000,000 base -----------------
    assertThat(lineAmount(result, "BPJS_KES_EE")).isEqualTo(Money.ofMinor(100_000L, IDR)); // 1%
    assertThat(lineAmount(result, "BPJS_KES_ER")).isEqualTo(Money.ofMinor(400_000L, IDR)); // 4%
    assertThat(lineAmount(result, "JHT_EE")).isEqualTo(Money.ofMinor(200_000L, IDR)); // 2%
    assertThat(lineAmount(result, "JHT_ER")).isEqualTo(Money.ofMinor(370_000L, IDR)); // 3.7%
    assertThat(lineAmount(result, "JP_EE")).isEqualTo(Money.ofMinor(100_000L, IDR)); // 1%
    assertThat(lineAmount(result, "JP_ER")).isEqualTo(Money.ofMinor(200_000L, IDR)); // 2%
    assertThat(lineAmount(result, "JKK_ER"))
        .isEqualTo(Money.ofMinor(54_000L, IDR)); // 0.54% class II
    assertThat(lineAmount(result, "JKM_ER")).isEqualTo(Money.ofMinor(30_000L, IDR)); // 0.3%

    // ---- PPh21 via TER, against the VERBATIM PMK 168/2023 band table -----------------------
    // grossBruto = 10,000,000 (taxable base) + 484,000 (taxable ER legs: BPJS-Kes-ER 400,000 +
    // JKK-ER 54,000 + JKM-ER 30,000; JHT/JP-ER are NOT taxable additions) = 10,484,000.
    // TK0 -> category A. Effective-rate lookup: the FIRST band (ascending) whose up_to_minor >=
    // 10,484,000. Walking category A's real bands around this figure:
    //   ... 9,650,000@175bp, 10,050,000@200bp, 10,350,000@225bp, 10,700,000@250bp, 11,050,000@300bp
    // ...
    // 10,484,000 > 10,350,000 (225bp band) but <= 10,700,000 -> the 10,700,000 band applies: 250bp
    // (2.5%), taxing the WHOLE gross bruto (never a marginal walk).
    // PPh21 = 10,484,000 * 250bp / 10000 = 262,100. No biaya jabatan, no PTKP subtraction (the TER
    // path never touches either).
    assertThat(lineAmount(result, "PPH21")).isEqualTo(Money.ofMinor(262_100L, IDR));

    // ---- Net = gross - EMPLOYEE-borne deductions only (BPJS-Kes/JHT/JP-EE + PPh21). -----------
    assertThat(result.grossEarnings()).isEqualTo(Money.ofMinor(10_000_000L, IDR));
    // 100,000 (BPJS-Kes-EE) + 200,000 (JHT-EE) + 100,000 (JP-EE) + 262,100 (PPh21) = 662,100.
    assertThat(result.employeeDeductions()).isEqualTo(Money.ofMinor(662_100L, IDR));
    assertThat(result.net()).isEqualTo(Money.ofMinor(9_337_900L, IDR));
    assertThat(result.employerContributions()).isEqualTo(Money.ofMinor(1_054_000L, IDR));

    // ---- Biaya-jabatan-free proof: exactly BASE + the 9 statutory lines (8 BPJS legs + PPh21),
    // nothing else. ------------------------------------------------------------------------------
    assertThat(result.lines()).hasSize(10);
    assertThat(result.lines())
        .noneMatch(
            l ->
                l.component().getStatutoryRuleKey() != null
                    && (l.component().getStatutoryRuleKey().equals("PPH21_ARTICLE17")
                        || l.component().getStatutoryRuleKey().equals("BIAYA_JABATAN")));

    // ---- THE HONEST-POSTURE PROOF, NOW FLIPPED: every rule this run resolves — including
    // PPH21_TER, transcribed + cross-verified 2026-08-02 (ADR 0031) — is OFFICIAL, so the run is
    // no longer flagged illustrative.
    assertThat(result.usesIllustrativeRules()).isFalse();
    assertThat(line(result, "PPH21").illustrative()).isFalse();
    assertThat(line(result, "PPH21").ruleVersion()).isEqualTo("ID-2026.1");
    assertThat(line(result, "BPJS_KES_EE").illustrative()).isFalse();
    assertThat(line(result, "JHT_EE").illustrative()).isFalse();
  }

  /**
   * P7 review W5 fix, recomputed: under {@code ID-2026.2}, the five BPJS legs carry {@code
   * base_kind=BASE_PAY} — they must compute on {@code basePay} ONLY, EXCLUDING a taxable COMMISSION
   * earning that {@code ID-2026.1}'s (pre-fix) {@code TAXABLE_GROSS} default would have folded in.
   * Proof: the SAME 10,000,000 base as the {@code ID-2026.1} golden test above, PLUS a 5,000,000
   * taxable commission — every BPJS leg comes out IDENTICAL to the commission-free {@code
   * ID-2026.1} test (proving commission is excluded), while PPh21 (which still taxes the WHOLE
   * gross bruto, commission included) is materially higher.
   */
  @Test
  void bpjsLegsUnderId20262ExcludeATaxableCommissionEarningFromTheirBase() {
    Dataset dataset = OfficialStatutoryDataset.load("ID-2026.2").orElseThrow();

    Map<String, StatutoryRule> resolvedRules = new LinkedHashMap<>();
    for (DatasetRule rule : dataset.rules()) {
      resolvedRules.put(
          rule.ruleKey(),
          new StatutoryRule(
              rule.ruleKey(),
              rule.ruleVersion(),
              rule.calcType(),
              rule.paramsJson(),
              IDR,
              rule.provenance(),
              rule.sourceNote(),
              rule.effectiveFrom(),
              StatutoryRule.OPEN_ENDED));
    }

    List<PayComponent> statutoryComponents = new java.util.ArrayList<>();
    statutoryComponents.add(
        new PayComponent(
            "BPJS_KES_EE",
            PayComponentKind.DEDUCTION,
            CalcType.STATUTORY_CEILING,
            PayComponentBearer.EMPLOYEE,
            "2100-BPJS",
            false,
            "BPJS_KESEHATAN",
            20));
    statutoryComponents.add(
        new PayComponent(
            "BPJS_KES_ER",
            PayComponentKind.DEDUCTION,
            CalcType.STATUTORY_CEILING,
            PayComponentBearer.EMPLOYER,
            "5200-BPJS-ER",
            false,
            "BPJS_KESEHATAN",
            21));
    dataset.components().stream()
        .map(GrossToNetOfficialDatasetTest::toPayComponent)
        .forEach(statutoryComponents::add);
    PayComponent baseComponent =
        new PayComponent(
            "BASE",
            PayComponentKind.EARNING,
            CalcType.FIXED,
            PayComponentBearer.EMPLOYEE,
            "5100-SALARY",
            true,
            null,
            0);
    PayComponent commissionComponent =
        new PayComponent(
            "COMMISSION",
            PayComponentKind.EARNING,
            CalcType.PERCENTAGE,
            PayComponentBearer.EMPLOYEE,
            "5120-COMMISSION",
            true,
            null,
            5);

    Money base = Money.ofMinor(10_000_000L, IDR);
    Money commission = Money.ofMinor(5_000_000L, IDR);
    PersonInput input =
        new PersonInput(
            UUID.randomUUID(),
            PtkpStatus.TK0,
            baseComponent,
            base,
            List.of(
                new id.co.nativeapp.employee.payroll.domain.PayrollInputs.EarningInput(
                    commissionComponent, commission)),
            statutoryComponents,
            List.of(),
            resolvedRules,
            /* hasNpwp= */ true,
            /* annualContext= */ null);

    PersonResult result = calculator.compute(input);

    // ---- BPJS legs — IDENTICAL to the commission-free ID-2026.1 golden test: the 5,000,000
    // commission never enters the base under base_kind=BASE_PAY. ------------------------------
    assertThat(lineAmount(result, "BPJS_KES_EE"))
        .isEqualTo(Money.ofMinor(100_000L, IDR)); // 1% of 10M
    assertThat(lineAmount(result, "BPJS_KES_ER"))
        .isEqualTo(Money.ofMinor(400_000L, IDR)); // 4% of 10M
    assertThat(lineAmount(result, "JHT_EE")).isEqualTo(Money.ofMinor(200_000L, IDR)); // 2% of 10M
    assertThat(lineAmount(result, "JHT_ER")).isEqualTo(Money.ofMinor(370_000L, IDR)); // 3.7% of 10M
    assertThat(lineAmount(result, "JP_EE")).isEqualTo(Money.ofMinor(100_000L, IDR)); // 1% of 10M
    assertThat(lineAmount(result, "JP_ER")).isEqualTo(Money.ofMinor(200_000L, IDR)); // 2% of 10M
    assertThat(lineAmount(result, "JKK_ER")).isEqualTo(Money.ofMinor(54_000L, IDR)); // 0.54% of 10M
    assertThat(lineAmount(result, "JKM_ER")).isEqualTo(Money.ofMinor(30_000L, IDR)); // 0.3% of 10M
    // Contrast (NOT asserted, for the record): under the PRE-FIX ID-2026.1 TAXABLE_GROSS default,
    // these legs would have computed on min(base+commission, ceiling) = min(15,000,000, ceiling)
    // instead — e.g. BPJS_KES_EE would have been 120,000 (1% of the 12,000,000 Kesehatan cap, since
    // 15M > 12M), not 100,000; JHT_EE would have been 300,000 (2% of 15,000,000, uncapped), not
    // 200,000 — a real, material overstatement the base_kind=BASE_PAY fix corrects.

    // ---- PPh21 STILL taxes the WHOLE gross bruto (commission included) — TER never excludes an
    // EARNING line, only the BPJS/JHT/JP/JKK/JKM percentage-ceiling BASE. ------------------------
    // grossBruto = taxableCashEarnings (base 10,000,000 + commission 5,000,000 = 15,000,000) +
    // employerTaxableAdditions (BPJS-Kes-ER 400,000 + JKK-ER 54,000 + JKM-ER 30,000 = 484,000)
    // = 15,484,000. Category A band walk: 13,750,000@500bp, 15,100,000@600bp, 16,950,000@700bp —
    // 15,484,000 > 15,100,000 but <= 16,950,000 -> the 700bp (7%) band applies to the WHOLE gross.
    // PPh21 = 15,484,000 * 700 / 10,000 = 1,083,880.
    assertThat(lineAmount(result, "PPH21")).isEqualTo(Money.ofMinor(1_083_880L, IDR));

    // ---- Net = gross - EMPLOYEE-borne deductions (BPJS-Kes/JHT/JP-EE + PPh21). ------------------
    assertThat(result.grossEarnings())
        .isEqualTo(Money.ofMinor(15_000_000L, IDR)); // base + commission
    // 100,000 + 200,000 + 100,000 + 1,083,880 = 1,483,880.
    assertThat(result.employeeDeductions()).isEqualTo(Money.ofMinor(1_483_880L, IDR));
    assertThat(result.net()).isEqualTo(Money.ofMinor(13_516_120L, IDR));
  }

  private static PayComponent toPayComponent(DatasetComponent c) {
    return new PayComponent(
        c.componentKey(),
        c.kind(),
        c.calcType(),
        c.bearer(),
        c.glAccount(),
        c.taxable(),
        c.statutoryRuleKey(),
        c.displayOrder());
  }

  private static id.co.nativeapp.employee.payroll.dto.PayrollResult.ComputedLine line(
      PersonResult result, String key) {
    return result.lines().stream()
        .filter(l -> l.component().getComponentKey().equals(key))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no line for " + key));
  }

  private static Money lineAmount(PersonResult result, String key) {
    return line(result, key).amount();
  }
}
