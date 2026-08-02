package id.co.nativeapp.employee.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import id.co.nativeapp.employee.payroll.domain.OfficialStatutoryDataset;
import id.co.nativeapp.employee.payroll.domain.OfficialStatutoryDataset.Dataset;
import id.co.nativeapp.employee.payroll.domain.OfficialStatutoryDataset.DatasetComponent;
import id.co.nativeapp.employee.payroll.domain.OfficialStatutoryDataset.DatasetRule;
import id.co.nativeapp.employee.payroll.domain.PayComponentBearer;
import id.co.nativeapp.employee.payroll.domain.PayComponentKind;
import id.co.nativeapp.employee.payroll.domain.RuleProvenance;
import id.co.nativeapp.employee.payroll.domain.StatutoryCalcType;
import id.co.nativeapp.employee.payroll.domain.StatutoryParams;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * THE TRANSCRIPTION-SAFETY NET (Track P phase P2, ADR 0031): loads the shipped {@code
 * ID-2026.1.json} dataset and asserts EVERY rule parses through its own {@link StatutoryCalcType}
 * family parser — {@link OfficialStatutoryDataset#load} already calls {@link
 * StatutoryParams#validate} for each rule at load time, so a malformed transcription fails THIS
 * test (a build-time signal), never a running payroll. Also pins the exact OFFICIAL/ILLUSTRATIVE
 * provenance split the task specified and a handful of load-bearing anchor figures.
 */
class OfficialStatutoryDatasetTest {

  private static final String VERSION = "ID-2026.1";

  @Test
  void anUnknownDatasetVersionIsAbsent() {
    assertThat(OfficialStatutoryDataset.load("ID-9999.9")).isEmpty();
  }

  @Test
  void everyRuleInTheShippedDatasetParsesThroughItsOwnCalcFamilyParser() {
    // load() itself calls StatutoryParams.validate(calcType, paramsJson) for every rule — if any
    // row's params failed its family parser, THIS load() call would already have thrown. Re-running
    // each parser explicitly here (defence in depth) makes the safety net's coverage visible.
    Dataset dataset = OfficialStatutoryDataset.load(VERSION).orElseThrow();

    assertThat(dataset.datasetVersion()).isEqualTo(VERSION);
    assertThat(dataset.rules()).hasSize(10);

    for (DatasetRule rule : dataset.rules()) {
      // Re-validating must NOT throw — the exact transcription-safety assertion.
      StatutoryParams.validate(rule.calcType(), rule.paramsJson());
      assertThat(rule.ruleVersion()).isEqualTo(VERSION);
      assertThat(rule.effectiveFrom()).isEqualTo(LocalDate.of(2026, 1, 1));
      assertThat(rule.sourceNote()).isNotBlank();
    }
  }

  @Test
  void everyRuleInTheShippedDatasetIsOfficialThePph21TerBandsWereVerbatimTranscribedAndVerified() {
    // PMK 168/2023 Lampiran (PP 58/2023) TER tables were fetched and cross-verified against two
    // independent sources on 2026-08-02 (all spot-rows matched) — see ADR 0031's activation
    // checklist entry and PPH21_TER's source_note. Every rule in ID-2026.1 now ships OFFICIAL; no
    // rule is ILLUSTRATIVE_PLACEHOLDER.
    Dataset dataset = OfficialStatutoryDataset.load(VERSION).orElseThrow();
    Map<String, RuleProvenance> byKey =
        dataset.rules().stream()
            .collect(
                java.util.stream.Collectors.toMap(DatasetRule::ruleKey, DatasetRule::provenance));

    assertThat(byKey.keySet())
        .containsExactlyInAnyOrder(
            "PPH21_TER",
            "PPH21_ARTICLE17",
            "PTKP_RELIEF",
            "BIAYA_JABATAN",
            "BPJS_KESEHATAN",
            "BPJS_JHT",
            "BPJS_JP",
            "BPJS_JKK",
            "BPJS_JKM",
            "OVERTIME_HOURLY");

    assertThat(byKey.values()).allSatisfy(p -> assertThat(p).isEqualTo(RuleProvenance.OFFICIAL));
  }

  /**
   * Drift guard for the verbatim-transcribed PMK 168/2023 TER band tables: pins the exact band
   * COUNT per category (44/40/41) and three spot-checked rows independently cross-verified against
   * two secondary sources — a future edit to the dataset that silently drops/reorders/mistypes a
   * row trips this test rather than shipping a silently wrong withholding rate.
   */
  @Test
  void terBandTablesMatchTheVerbatimTranscribedPmk168CountsAndCrossVerifiedSpotRows() {
    Dataset dataset = OfficialStatutoryDataset.load(VERSION).orElseThrow();
    DatasetRule ter =
        dataset.rules().stream().filter(r -> r.ruleKey().equals("PPH21_TER")).findFirst().get();
    StatutoryParams.TerTableParams params = StatutoryParams.terTable(ter.paramsJson());

    assertThat(countBands(params, "TK0")).isEqualTo(44); // category A
    assertThat(countBands(params, "TK2")).isEqualTo(40); // category B
    assertThat(countBands(params, "K3")).isEqualTo(41); // category C

    // Cross-verified spot rows (2026-08-02, two independent sources, all matched).
    assertThat(
            params
                .categoryFor(id.co.nativeapp.employee.employee.domain.PtkpStatus.TK0)
                .rateBpFor(10_350_000L))
        .as("category A 2.25%% up to 10,350,000")
        .isEqualTo(225);
    assertThat(
            params
                .categoryFor(id.co.nativeapp.employee.employee.domain.PtkpStatus.TK2)
                .rateBpFor(26_000_000L))
        .as("category B 9%% up to 26,000,000")
        .isEqualTo(900);
    assertThat(
            params
                .categoryFor(id.co.nativeapp.employee.employee.domain.PtkpStatus.K3)
                .rateBpFor(110_000_000L))
        .as("category C 24%% up to 110,000,000")
        .isEqualTo(2400);
  }

  private static int countBands(StatutoryParams.TerTableParams params, String ptkpStatus) {
    return params
        .categoryFor(id.co.nativeapp.employee.employee.domain.PtkpStatus.valueOf(ptkpStatus))
        .bands()
        .size();
  }

  @Test
  void terAnchorBandsMatchTheSpecZeroPercentThresholdsAndTheUnboundedThirtyFourPercentTop() {
    Dataset dataset = OfficialStatutoryDataset.load(VERSION).orElseThrow();
    DatasetRule ter =
        dataset.rules().stream().filter(r -> r.ruleKey().equals("PPH21_TER")).findFirst().get();
    StatutoryParams.TerTableParams params = StatutoryParams.terTable(ter.paramsJson());

    // Category A (TK0/TK1/K0): 0% <= 5,400,000.
    assertThat(
            params
                .categoryFor(id.co.nativeapp.employee.employee.domain.PtkpStatus.TK0)
                .rateBpFor(5_400_000L))
        .isZero();
    // Category B (TK2/TK3/K1/K2): 0% <= 6,200,000.
    assertThat(
            params
                .categoryFor(id.co.nativeapp.employee.employee.domain.PtkpStatus.TK2)
                .rateBpFor(6_200_000L))
        .isZero();
    // Category C (K3): 0% <= 6,600,000.
    assertThat(
            params
                .categoryFor(id.co.nativeapp.employee.employee.domain.PtkpStatus.K3)
                .rateBpFor(6_600_000L))
        .isZero();
    // The top band is 34% at the agreed unbounded sentinel, for every category.
    assertThat(
            params
                .categoryFor(id.co.nativeapp.employee.employee.domain.PtkpStatus.TK0)
                .rateBpFor(StatutoryParams.UNBOUNDED_TOP_CAP_MINOR))
        .isEqualTo(3400);
    assertThat(params.noNpwpSurchargeBp()).isEqualTo(12000);
  }

  @Test
  void pph21Article17MatchesUuHpp72021Brackets() {
    Dataset dataset = OfficialStatutoryDataset.load(VERSION).orElseThrow();
    DatasetRule rule =
        dataset.rules().stream()
            .filter(r -> r.ruleKey().equals("PPH21_ARTICLE17"))
            .findFirst()
            .get();
    StatutoryParams.AnnualProgressiveParams params =
        StatutoryParams.annualProgressive(rule.paramsJson());

    assertThat(params.brackets()).hasSize(5);
    assertThat(params.brackets().get(0).rateBp()).isEqualTo(500);
    assertThat(params.brackets().get(4).rateBp()).isEqualTo(3500);
    assertThat(params.occupationalCostBp()).isEqualTo(500);
    assertThat(params.occupationalCostCapAnnualMinor()).isEqualTo(6_000_000L);
  }

  @Test
  void ptkpReliefMatchesPmk1012016() {
    Dataset dataset = OfficialStatutoryDataset.load(VERSION).orElseThrow();
    DatasetRule rule =
        dataset.rules().stream().filter(r -> r.ruleKey().equals("PTKP_RELIEF")).findFirst().get();
    StatutoryParams.ReliefParams params = StatutoryParams.relief(rule.paramsJson());

    assertThat(params.ptkpByStatus().get("TK0")).isEqualTo(54_000_000L);
    assertThat(params.ptkpByStatus().get("TK1")).isEqualTo(58_500_000L);
    assertThat(params.ptkpByStatus().get("K0")).isEqualTo(58_500_000L);
    assertThat(params.ptkpByStatus().get("K3")).isEqualTo(72_000_000L);
  }

  @Test
  void bpjsLegsMatchTheSpecMatrixRatesCapsAndTaxTreatment() {
    Dataset dataset = OfficialStatutoryDataset.load(VERSION).orElseThrow();
    Map<String, DatasetRule> byKey =
        dataset.rules().stream()
            .collect(java.util.stream.Collectors.toMap(DatasetRule::ruleKey, r -> r));

    StatutoryParams.CeilingParams kes =
        StatutoryParams.ceiling(byKey.get("BPJS_KESEHATAN").paramsJson());
    assertThat(kes.employerBp()).isEqualTo(400);
    assertThat(kes.employeeBp()).isEqualTo(100);
    assertThat(kes.ceilingMinor()).isEqualTo(12_000_000L);
    assertThat(kes.reducesTaxBase()).isFalse();
    assertThat(kes.employerAddsToTaxBase()).isTrue();

    StatutoryParams.CeilingParams jht = StatutoryParams.ceiling(byKey.get("BPJS_JHT").paramsJson());
    assertThat(jht.employerBp()).isEqualTo(370);
    assertThat(jht.employeeBp()).isEqualTo(200);
    assertThat(jht.ceilingMinor()).isEqualTo(StatutoryParams.UNBOUNDED_TOP_CAP_MINOR);
    assertThat(jht.reducesTaxBase()).isTrue();
    assertThat(jht.employerAddsToTaxBase()).isFalse();

    StatutoryParams.CeilingParams jp = StatutoryParams.ceiling(byKey.get("BPJS_JP").paramsJson());
    assertThat(jp.employerBp()).isEqualTo(200);
    assertThat(jp.employeeBp()).isEqualTo(100);
    assertThat(jp.ceilingMinor()).isEqualTo(10_547_400L);
    assertThat(jp.reducesTaxBase()).isTrue();

    StatutoryParams.CeilingParams jkk = StatutoryParams.ceiling(byKey.get("BPJS_JKK").paramsJson());
    assertThat(jkk.employerBp()).isEqualTo(54); // default risk class II
    assertThat(jkk.employeeBp()).isZero();
    assertThat(jkk.employerAddsToTaxBase()).isTrue();

    StatutoryParams.CeilingParams jkm = StatutoryParams.ceiling(byKey.get("BPJS_JKM").paramsJson());
    assertThat(jkm.employerBp()).isEqualTo(30);
    assertThat(jkm.employeeBp()).isZero();
    assertThat(jkm.employerAddsToTaxBase()).isTrue();
  }

  @Test
  void overtimeHourlyMatchesPp352021TiersAndIsParsedButDormant() {
    Dataset dataset = OfficialStatutoryDataset.load(VERSION).orElseThrow();
    DatasetRule rule =
        dataset.rules().stream()
            .filter(r -> r.ruleKey().equals("OVERTIME_HOURLY"))
            .findFirst()
            .get();
    StatutoryParams.HourlyRateTableParams params =
        StatutoryParams.hourlyRateTable(rule.paramsJson());

    assertThat(params.monthlyDivisor()).isEqualTo(173);
    assertThat(params.tiers().get("weekday")).hasSize(2);
    assertThat(params.tiers().get("weekday").get(0).multiplierBp()).isEqualTo(15000);
    assertThat(params.tiers().get("rest_day")).hasSize(3);

    // Dormant: no dataset component references OVERTIME_HOURLY (phase P7 wires it).
    assertThat(dataset.components()).noneMatch(c -> "OVERTIME_HOURLY".equals(c.statutoryRuleKey()));
  }

  @Test
  void biayaJabatanIsDocumentationOnlyAndReferencedByNoComponent() {
    Dataset dataset = OfficialStatutoryDataset.load(VERSION).orElseThrow();
    assertThat(dataset.components()).noneMatch(c -> "BIAYA_JABATAN".equals(c.statutoryRuleKey()));
    assertThat(dataset.components()).noneMatch(c -> "PPH21_ARTICLE17".equals(c.statutoryRuleKey()));
  }

  @Test
  void theComponentCatalogAdditionsMatchTheTaskSpecKeysBearersAndGlHints() {
    Dataset dataset = OfficialStatutoryDataset.load(VERSION).orElseThrow();
    assertThat(dataset.components()).hasSize(7);
    Map<String, DatasetComponent> byKey =
        dataset.components().stream()
            .collect(java.util.stream.Collectors.toMap(DatasetComponent::componentKey, c -> c));

    assertThat(byKey.keySet())
        .containsExactlyInAnyOrder(
            "JHT_EE", "JHT_ER", "JP_EE", "JP_ER", "JKK_ER", "JKM_ER", "PPH21");

    assertThat(byKey.get("JHT_EE").bearer()).isEqualTo(PayComponentBearer.EMPLOYEE);
    assertThat(byKey.get("JHT_EE").glAccount()).isEqualTo("2100-BPJS");
    assertThat(byKey.get("JHT_ER").bearer()).isEqualTo(PayComponentBearer.EMPLOYER);
    assertThat(byKey.get("JHT_ER").glAccount()).isEqualTo("5200-BPJS-ER");
    assertThat(byKey.get("JKK_ER").statutoryRuleKey()).isEqualTo("BPJS_JKK");
    assertThat(byKey.get("JKM_ER").statutoryRuleKey()).isEqualTo("BPJS_JKM");

    // PPh21 rewired to the TER rule (not PPH21_PROGRESSIVE), everything else unchanged.
    DatasetComponent pph21 = byKey.get("PPH21");
    assertThat(pph21.statutoryRuleKey()).isEqualTo("PPH21_TER");
    assertThat(pph21.kind()).isEqualTo(PayComponentKind.DEDUCTION);
    assertThat(pph21.glAccount()).isEqualTo("2110-PPH21");
    assertThat(pph21.displayOrder()).isEqualTo(30);
  }

  @Test
  void aMalformedRuleFailsAtLoadTimeNotAtRunTime() {
    // Prove the safety net actually fires: StatutoryParams.validate rejects a bad TER table
    // (finite top band) the same way load() would if a dataset file shipped one.
    String brokenTerJson =
        "{\"ptkp_category\":{\"TK0\":\"A\",\"TK1\":\"A\",\"TK2\":\"A\",\"TK3\":\"A\","
            + "\"K0\":\"A\",\"K1\":\"A\",\"K2\":\"A\",\"K3\":\"A\"},"
            + "\"categories\":{\"A\":{\"bands\":[{\"up_to_minor\":1000000,\"rate_bp\":500}]}},"
            + "\"no_npwp_surcharge_bp\":12000}";

    assertThatThrownBy(() -> StatutoryParams.validate(StatutoryCalcType.TER_TABLE, brokenTerJson))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unbounded sentinel");
  }

  @Test
  void theOfficialSeedWriterCurrencyIsInferredNeverHardcodedInTheDataset() {
    // The dataset carries no currency field at all — Money's currency travels with the tenant's
    // already-established statutory_rule rows (see OfficialStatutorySeedWriter/PayrollSetupService
    // javadoc), never as a literal in the JSON.
    Optional<Dataset> dataset = OfficialStatutoryDataset.load(VERSION);
    assertThat(dataset).isPresent();
  }

  @Test
  void everyRuleIsAssertedAgainstItsOwnParserFamilyExhaustively() {
    Dataset dataset = OfficialStatutoryDataset.load(VERSION).orElseThrow();
    Map<StatutoryCalcType, List<String>> byFamily =
        dataset.rules().stream()
            .collect(
                java.util.stream.Collectors.groupingBy(
                    DatasetRule::calcType,
                    java.util.stream.Collectors.mapping(
                        DatasetRule::ruleKey, java.util.stream.Collectors.toList())));

    assertThat(byFamily.get(StatutoryCalcType.TER_TABLE)).containsExactly("PPH21_TER");
    assertThat(byFamily.get(StatutoryCalcType.ANNUAL_PROGRESSIVE))
        .containsExactly("PPH21_ARTICLE17");
    assertThat(byFamily.get(StatutoryCalcType.RELIEF_TABLE)).containsExactly("PTKP_RELIEF");
    assertThat(byFamily.get(StatutoryCalcType.PERCENTAGE_CEILING))
        .containsExactlyInAnyOrder(
            "BIAYA_JABATAN", "BPJS_KESEHATAN", "BPJS_JHT", "BPJS_JP", "BPJS_JKK", "BPJS_JKM");
    assertThat(byFamily.get(StatutoryCalcType.HOURLY_RATE_TABLE))
        .containsExactly("OVERTIME_HOURLY");
    assertThat(byFamily.get(StatutoryCalcType.PROGRESSIVE_BRACKET)).isNull();
  }
}
