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
