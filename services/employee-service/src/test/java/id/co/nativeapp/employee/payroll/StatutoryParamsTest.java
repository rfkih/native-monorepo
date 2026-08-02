package id.co.nativeapp.employee.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import id.co.nativeapp.employee.employee.domain.PtkpStatus;
import id.co.nativeapp.employee.payroll.domain.StatutoryParams;
import org.junit.jupiter.api.Test;

/**
 * Load-time validation of {@link StatutoryParams} — the bridge between the rule DATA (numbers in
 * JSON) and the calculator MACHINERY. A pure unit test (no Spring/DB).
 *
 * <p>Focus (#34): a PROGRESSIVE-shaped table's TOP bracket/band MUST be the agreed unbounded
 * sentinel so the top rate covers all income above its floor. A FINITE top bound would silently
 * leave income above it untaxed/unrated, so it is rejected the moment the table is loaded
 * (fail-fast) rather than only when a high-enough earner trips a run-time walk.
 *
 * <p>Also covers the Track P phase P1 additions: the {@code TER_TABLE} (PMK 168/2023) and {@code
 * ANNUAL_PROGRESSIVE} (UU HPP 7/2021 Art-17) parsers, {@code HOURLY_RATE_TABLE} (PP 35/2021,
 * parsed/validated but dormant until phase P7), and the {@code PERCENTAGE_CEILING} additive {@code
 * base_kind}/{@code employer_adds_to_tax_base} back-compat.
 */
class StatutoryParamsTest {

  @Test
  void anUnboundedTopBracketLoadsAndSortsAscendingByFloor() {
    // Brackets supplied out of order; the loader sorts them and accepts the unbounded top cap.
    String json =
        "{\"brackets\":["
            + "{\"floor_minor\":50000000,\"cap_minor\":"
            + StatutoryParams.UNBOUNDED_TOP_CAP_MINOR
            + ",\"rate_bp\":1500},"
            + "{\"floor_minor\":0,\"cap_minor\":50000000,\"rate_bp\":1000}]}";

    StatutoryParams.ProgressiveParams params = StatutoryParams.progressive(json);

    assertThat(params.brackets()).hasSize(2);
    assertThat(params.brackets().get(0).floorMinor()).isZero();
    assertThat(params.brackets().get(1).capMinor())
        .isEqualTo(StatutoryParams.UNBOUNDED_TOP_CAP_MINOR);
  }

  @Test
  void aFiniteTopBracketCapIsRejectedAtLoadTime() {
    // A single bracket capped at a FINITE 100,000,000 — top income above it would be untaxed.
    String json = "{\"brackets\":[{\"floor_minor\":0,\"cap_minor\":100000000,\"rate_bp\":1000}]}";

    assertThatThrownBy(() -> StatutoryParams.progressive(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unbounded sentinel")
        .hasMessageContaining(Long.toString(StatutoryParams.UNBOUNDED_TOP_CAP_MINOR));
  }

  @Test
  void aFiniteTopCapIsRejectedEvenWhenLowerBracketsAreUnbounded() {
    // The HIGHEST-floor bracket is the one that must be unbounded; a finite cap there is rejected
    // even if a lower bracket happens to carry the sentinel value.
    String json =
        "{\"brackets\":["
            + "{\"floor_minor\":0,\"cap_minor\":50000000,\"rate_bp\":1000},"
            + "{\"floor_minor\":50000000,\"cap_minor\":250000000,\"rate_bp\":1500}]}";

    assertThatThrownBy(() -> StatutoryParams.progressive(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unbounded sentinel");
  }

  // ---------------------------------------------------------------------
  // TER_TABLE (PMK 168/2023)
  // ---------------------------------------------------------------------

  /**
   * A minimal but VALID TER table, unbounded sentinel + all 8 PTKP statuses mapped per PMK
   * 168/2023.
   */
  private String terJson(String bandsA, String bandsB, String bandsC) {
    return "{\"ptkp_category\":{"
        + "\"TK0\":\"A\",\"TK1\":\"A\",\"K0\":\"A\","
        + "\"TK2\":\"B\",\"TK3\":\"B\",\"K1\":\"B\",\"K2\":\"B\","
        + "\"K3\":\"C\"},"
        + "\"categories\":{"
        + "\"A\":{\"bands\":["
        + bandsA
        + "]},"
        + "\"B\":{\"bands\":["
        + bandsB
        + "]},"
        + "\"C\":{\"bands\":["
        + bandsC
        + "]}},"
        + "\"no_npwp_surcharge_bp\":12000}";
  }

  private static final String UNBOUNDED = Long.toString(StatutoryParams.UNBOUNDED_TOP_CAP_MINOR);

  @Test
  void terTableMapsPtkpStatusesToCategoriesPerPmk168AndLooksUpTheEffectiveRateOnWholeGross() {
    // PMK 168/2023 category grouping: A = TK0/TK1/K0, B = TK2/TK3/K1/K2, C = K3.
    String json =
        terJson(
            "{\"up_to_minor\":" + UNBOUNDED + ",\"rate_bp\":500}",
            "{\"up_to_minor\":" + UNBOUNDED + ",\"rate_bp\":1500}",
            "{\"up_to_minor\":" + UNBOUNDED + ",\"rate_bp\":2500}");

    StatutoryParams.TerTableParams params = StatutoryParams.terTable(json);

    assertThat(params.categoryFor(PtkpStatus.TK0).rateBpFor(1_000_000L)).isEqualTo(500);
    assertThat(params.categoryFor(PtkpStatus.TK1).rateBpFor(1_000_000L)).isEqualTo(500);
    assertThat(params.categoryFor(PtkpStatus.K0).rateBpFor(1_000_000L)).isEqualTo(500);
    assertThat(params.categoryFor(PtkpStatus.TK2).rateBpFor(1_000_000L)).isEqualTo(1500);
    assertThat(params.categoryFor(PtkpStatus.TK3).rateBpFor(1_000_000L)).isEqualTo(1500);
    assertThat(params.categoryFor(PtkpStatus.K1).rateBpFor(1_000_000L)).isEqualTo(1500);
    assertThat(params.categoryFor(PtkpStatus.K2).rateBpFor(1_000_000L)).isEqualTo(1500);
    assertThat(params.categoryFor(PtkpStatus.K3).rateBpFor(1_000_000L)).isEqualTo(2500);
    assertThat(params.noNpwpSurchargeBp()).isEqualTo(12000);
  }

  @Test
  void terTableRejectsAMissingPtkpStatusMapping() {
    // K3 is left unmapped — an unmapped status would silently resolve no TER rate at run time.
    String json =
        "{\"ptkp_category\":{"
            + "\"TK0\":\"A\",\"TK1\":\"A\",\"TK2\":\"A\",\"TK3\":\"A\","
            + "\"K0\":\"A\",\"K1\":\"A\",\"K2\":\"A\"},"
            + "\"categories\":{\"A\":{\"bands\":[{\"up_to_minor\":"
            + UNBOUNDED
            + ",\"rate_bp\":500}]}},"
            + "\"no_npwp_surcharge_bp\":12000}";

    assertThatThrownBy(() -> StatutoryParams.terTable(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("K3");
  }

  @Test
  void terTableRejectsUnsortedBandsRatherThanSilentlySortingThem() {
    // Bands supplied out of order. Unlike PROGRESSIVE_BRACKET, a TER table is REJECTED, not sorted.
    String json =
        terJson(
            "{\"up_to_minor\":10000000,\"rate_bp\":1000},"
                + "{\"up_to_minor\":5000000,\"rate_bp\":500},"
                + "{\"up_to_minor\":"
                + UNBOUNDED
                + ",\"rate_bp\":1500}",
            "{\"up_to_minor\":" + UNBOUNDED + ",\"rate_bp\":1500}",
            "{\"up_to_minor\":" + UNBOUNDED + ",\"rate_bp\":2500}");

    assertThatThrownBy(() -> StatutoryParams.terTable(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("ascending");
  }

  @Test
  void terTableRejectsAMissingTopSentinel() {
    // Category A's top band caps out at a FINITE 20,000,000 — gross above it would have no rate.
    String json =
        terJson(
            "{\"up_to_minor\":20000000,\"rate_bp\":500}",
            "{\"up_to_minor\":" + UNBOUNDED + ",\"rate_bp\":1500}",
            "{\"up_to_minor\":" + UNBOUNDED + ",\"rate_bp\":2500}");

    assertThatThrownBy(() -> StatutoryParams.terTable(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unbounded sentinel");
  }

  @Test
  void terTableRejectsARateBpOutOfRange() {
    String json =
        terJson(
            "{\"up_to_minor\":" + UNBOUNDED + ",\"rate_bp\":10001}",
            "{\"up_to_minor\":" + UNBOUNDED + ",\"rate_bp\":1500}",
            "{\"up_to_minor\":" + UNBOUNDED + ",\"rate_bp\":2500}");

    assertThatThrownBy(() -> StatutoryParams.terTable(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("basis-point range");
  }

  @Test
  void terTableRejectsPtkpCategoryReferencingAnUnknownCategory() {
    String json =
        "{\"ptkp_category\":{"
            + "\"TK0\":\"Z\",\"TK1\":\"A\",\"TK2\":\"A\",\"TK3\":\"A\","
            + "\"K0\":\"A\",\"K1\":\"A\",\"K2\":\"A\",\"K3\":\"A\"},"
            + "\"categories\":{\"A\":{\"bands\":[{\"up_to_minor\":"
            + UNBOUNDED
            + ",\"rate_bp\":500}]}},"
            + "\"no_npwp_surcharge_bp\":12000}";

    assertThatThrownBy(() -> StatutoryParams.terTable(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("TK0")
        .hasMessageContaining("Z");
  }

  // ---------------------------------------------------------------------
  // ANNUAL_PROGRESSIVE (UU HPP 7/2021 Art-17 December true-up)
  // ---------------------------------------------------------------------

  @Test
  void annualProgressiveParsesTheArt17BracketsAndBiayaJabatanFields() {
    // UU HPP 7/2021: 5% <=60jt, 15% <=250jt, 25% <=500jt, 30% <=5M, 35% above.
    String json =
        "{\"brackets\":["
            + "{\"floor_minor\":0,\"cap_minor\":60000000,\"rate_bp\":500},"
            + "{\"floor_minor\":60000000,\"cap_minor\":250000000,\"rate_bp\":1500},"
            + "{\"floor_minor\":250000000,\"cap_minor\":500000000,\"rate_bp\":2500},"
            + "{\"floor_minor\":500000000,\"cap_minor\":5000000000,\"rate_bp\":3000},"
            + "{\"floor_minor\":5000000000,\"cap_minor\":"
            + UNBOUNDED
            + ",\"rate_bp\":3500}],"
            + "\"occupational_cost_bp\":500,"
            + "\"occupational_cost_cap_annual_minor\":6000000,"
            + "\"no_npwp_surcharge_bp\":12000}";

    StatutoryParams.AnnualProgressiveParams params = StatutoryParams.annualProgressive(json);

    assertThat(params.brackets()).hasSize(5);
    assertThat(params.occupationalCostBp()).isEqualTo(500);
    assertThat(params.occupationalCostCapAnnualMinor()).isEqualTo(6_000_000L);
    assertThat(params.noNpwpSurchargeBp()).isEqualTo(12000);
  }

  @Test
  void annualProgressiveRejectsAFiniteTopBracketCap() {
    String json =
        "{\"brackets\":[{\"floor_minor\":0,\"cap_minor\":60000000,\"rate_bp\":500}],"
            + "\"occupational_cost_bp\":500,\"occupational_cost_cap_annual_minor\":6000000}";

    assertThatThrownBy(() -> StatutoryParams.annualProgressive(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unbounded sentinel");
  }

  @Test
  void annualProgressiveRequiresTheOccupationalCostFields() {
    String json =
        "{\"brackets\":[{\"floor_minor\":0,\"cap_minor\":" + UNBOUNDED + ",\"rate_bp\":500}]}";

    assertThatThrownBy(() -> StatutoryParams.annualProgressive(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("occupational_cost_bp");
  }

  // ---------------------------------------------------------------------
  // PERCENTAGE_CEILING additive base_kind / employer_adds_to_tax_base (back-compat)
  // ---------------------------------------------------------------------

  @Test
  void ceilingParamsOldShapeJsonParsesUnchangedWithBackwardCompatibleDefaults() {
    // The pre-phase-P1 shape — no base_kind, no employer_adds_to_tax_base — still parses, so every
    // existing illustrative rule row is unaffected.
    String json =
        "{\"ceiling_minor\":10000000,\"employee_bp\":100,\"employer_bp\":400,"
            + "\"reduces_tax_base\":true}";

    StatutoryParams.CeilingParams params = StatutoryParams.ceiling(json);

    assertThat(params.baseKind()).isEqualTo(StatutoryParams.BaseKind.TAXABLE_GROSS);
    assertThat(params.employerAddsToTaxBase()).isFalse();
    assertThat(params.reducesTaxBase()).isTrue();
  }

  @Test
  void ceilingParamsNewShapeJsonParsesBasePayAndEmployerAddsToTaxBase() {
    String json =
        "{\"ceiling_minor\":10000000,\"employee_bp\":100,\"employer_bp\":400,"
            + "\"reduces_tax_base\":false,\"base_kind\":\"BASE_PAY\","
            + "\"employer_adds_to_tax_base\":true}";

    StatutoryParams.CeilingParams params = StatutoryParams.ceiling(json);

    assertThat(params.baseKind()).isEqualTo(StatutoryParams.BaseKind.BASE_PAY);
    assertThat(params.employerAddsToTaxBase()).isTrue();
  }

  // ---------------------------------------------------------------------
  // HOURLY_RATE_TABLE (PP 35/2021 overtime — parsed/validated in P1, consumed in P7)
  // ---------------------------------------------------------------------

  @Test
  void hourlyRateTableParsesTheMonthlyDivisorAndWeekdayRestDayTiers() {
    // PP 35/2021: weekday 1.5x first hour then 2x; rest/holiday 2x h1-7, 3x h8, 4x h9-10.
    String json =
        "{\"monthly_divisor\":173,\"tiers\":{"
            + "\"weekday\":[{\"hours\":1,\"multiplier_bp\":15000},{\"hours\":null,\"multiplier_bp\":20000}],"
            + "\"rest_day\":[{\"hours\":7,\"multiplier_bp\":20000},{\"hours\":1,\"multiplier_bp\":30000},"
            + "{\"hours\":null,\"multiplier_bp\":40000}]}}";

    StatutoryParams.HourlyRateTableParams params = StatutoryParams.hourlyRateTable(json);

    assertThat(params.monthlyDivisor()).isEqualTo(173);
    assertThat(params.tiers()).containsKeys("weekday", "rest_day");
    assertThat(params.tiers().get("weekday")).hasSize(2);
    assertThat(params.tiers().get("weekday").get(0).hours()).isEqualTo(1);
    assertThat(params.tiers().get("weekday").get(1).hours()).isNull();
    assertThat(params.tiers().get("weekday").get(1).multiplierBp()).isEqualTo(20000);
  }

  @Test
  void hourlyRateTableRejectsANonPositiveMonthlyDivisor() {
    String json =
        "{\"monthly_divisor\":0,\"tiers\":{\"weekday\":[{\"hours\":null,\"multiplier_bp\":20000}]}}";

    assertThatThrownBy(() -> StatutoryParams.hourlyRateTable(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("monthly_divisor");
  }

  @Test
  void hourlyRateTableRejectsAnOpenEndedTierBeforeTheLastPosition() {
    // A null (open-ended) hours entry must be the LAST entry — one before the end is ambiguous.
    String json =
        "{\"monthly_divisor\":173,\"tiers\":{\"weekday\":["
            + "{\"hours\":null,\"multiplier_bp\":15000},{\"hours\":1,\"multiplier_bp\":20000}]}}";

    assertThatThrownBy(() -> StatutoryParams.hourlyRateTable(json))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("open-ended");
  }
}
