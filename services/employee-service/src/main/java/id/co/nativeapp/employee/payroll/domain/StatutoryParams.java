package id.co.nativeapp.employee.payroll.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import id.co.nativeapp.employee.employee.domain.PtkpStatus;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Parses a {@link StatutoryRule}'s {@code params_json} into typed, immutable value objects the
 * {@link GrossToNetCalculator} reasons over. This is the bridge between the DATA (numbers in JSON)
 * and the MACHINERY (algorithms in Java): the parser knows the SHAPE of each calc-type's params but
 * holds ZERO statutory figures itself (HR-9). All amounts are {@code long} minor units — never a
 * float.
 *
 * <p>Param shapes by {@link StatutoryCalcType}:
 *
 * <ul>
 *   <li>{@code PROGRESSIVE_BRACKET}: {@code
 *       {"brackets":[{"floor_minor":L,"cap_minor":L,"rate_bp":I}, ...]}} (last bracket may use a
 *       very large cap_minor as +infinity).
 *   <li>{@code PERCENTAGE_CEILING}: {@code {"ceiling_minor":L,"employee_bp":I,"employer_bp":I,
 *       "reduces_tax_base":bool,"base_kind":"TAXABLE_GROSS"|"BASE_PAY","employer_adds_to_tax_base":
 *       bool}} — the last two are ADDITIVE (Track P phase P1) with backward-compatible defaults
 *       ({@code TAXABLE_GROSS}, {@code false}), so an existing rule row missing them still parses
 *       unchanged.
 *   <li>{@code RELIEF_TABLE}: {@code {"ptkp":{"TK0":L,"K1":L,...},"annualization_months":I}}.
 *   <li>{@code TER_TABLE}: {@code {"ptkp_category":{"TK0":"A",...},"categories":{"A":{"bands":
 *       [{"up_to_minor":L,"rate_bp":I},...]},"B":...,"C":...},"no_npwp_surcharge_bp":I}} (PMK
 *       168/2023).
 *   <li>{@code ANNUAL_PROGRESSIVE}: {@code {"brackets":[...],"occupational_cost_bp":I,
 *       "occupational_cost_cap_annual_minor":L,"no_npwp_surcharge_bp":I}} (UU HPP 7/2021 Art-17 +
 *       PMK 250/2008 biaya jabatan).
 *   <li>{@code HOURLY_RATE_TABLE}: {@code {"monthly_divisor":I,"tiers":{"weekday":[{"hours":I|
 *       null,"multiplier_bp":I},...],"rest_day":[...]}}} (PP 35/2021). Parsed/validated in Track P
 *       phase P1; consumed only from phase P7.
 * </ul>
 */
public final class StatutoryParams {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** The inclusive basis-point range [0, 10000] every rate_bp in this file must fall within. */
  private static final int MAX_BASIS_POINTS = 10_000;

  /**
   * The agreed "effectively unbounded" sentinel for a PROGRESSIVE-shaped table's TOP band/bracket
   * upper bound (#34). A progressive/effective-rate schedule's highest band must extend to
   * +infinity so its top rate applies to all income above its floor; a FINITE top bound would
   * silently leave income above it untaxed (PROGRESSIVE_BRACKET, ANNUAL_PROGRESSIVE) or unrated
   * (TER_TABLE). We model +infinity as this large-but-finite constant (well above any realistic
   * annualized IDR/USD minor-unit income, yet inside {@code long}/{@code jsonb} range) and REJECT
   * any such table whose top bound is not exactly this value at load time — turning a run-time
   * safety net into a fail-fast load-time invariant.
   */
  public static final long UNBOUNDED_TOP_CAP_MINOR = 999_999_999_999L;

  private StatutoryParams() {
    // static helpers
  }

  /**
   * One progressive bracket: tax {@code rate_bp} basis points on income in {@code [floor, cap]}.
   */
  public record Bracket(long floorMinor, long capMinor, int rateBp) {}

  /** Parsed {@code PROGRESSIVE_BRACKET} params: the sorted brackets. */
  public record ProgressiveParams(List<Bracket> brackets) {}

  /**
   * The base a {@code PERCENTAGE_CEILING} leg's percentage is computed on: the taxable-earnings
   * gross (the pre-existing, default behaviour) or the person's BASE PAY only — needed so BPJS
   * contributions can be based on wage rather than a commission-laden taxable gross.
   */
  public enum BaseKind {
    TAXABLE_GROSS,
    BASE_PAY;

    /**
     * Parses a case-insensitive base-kind string, rejecting an unknown value.
     *
     * @throws IllegalArgumentException if {@code raw} is not a known base kind
     */
    public static BaseKind from(String raw) {
      try {
        return BaseKind.valueOf(raw.strip().toUpperCase(Locale.ROOT));
      } catch (IllegalArgumentException e) {
        throw new IllegalArgumentException(
            "PERCENTAGE_CEILING base_kind '" + raw + "' is not TAXABLE_GROSS or BASE_PAY", e);
      }
    }
  }

  /**
   * Parsed {@code PERCENTAGE_CEILING} params. {@code baseKind}/{@code employerAddsToTaxBase} are
   * ADDITIVE (Track P phase P1, the BPJS tax-treatment matrix): {@code baseKind} selects what the
   * percentage is computed on; {@code employerAddsToTaxBase} marks an EMPLOYER leg as a taxable
   * fringe benefit to the employee (e.g. BPJS-Kesehatan-ER) — it raises the income-tax base ONLY,
   * never {@code grossEarnings}/net/cash.
   */
  public record CeilingParams(
      long ceilingMinor,
      int employeeBp,
      int employerBp,
      boolean reducesTaxBase,
      BaseKind baseKind,
      boolean employerAddsToTaxBase) {}

  /** Parsed {@code RELIEF_TABLE} params: PTKP relief per status + annualization months. */
  public record ReliefParams(Map<String, Long> ptkpByStatus, int annualizationMonths) {}

  /**
   * One TER effective-rate band: {@code rateBp} applies to the WHOLE gross when gross &lt;=
   * upToMinor.
   */
  public record TerBand(long upToMinor, int rateBp) {}

  /** One TER category's ascending band table (PMK 168/2023 Lampiran, category A/B/C). */
  public record TerCategoryTable(List<TerBand> bands) {

    /**
     * The {@code rate_bp} of the FIRST band (ascending) whose {@code up_to_minor} is {@code >=}
     * {@code grossMinor} — the PMK 168/2023 effective-rate-on-whole-gross lookup. Never a marginal
     * walk: the returned rate applies to the ENTIRE gross, not just the span within the band.
     *
     * @throws IllegalStateException if {@code grossMinor} exceeds every band's {@code up_to_minor}
     *     (cannot happen for a table whose top band was validated at load time to the unbounded
     *     sentinel — defence in depth, mirroring {@code walkBrackets}'s run-time guard)
     */
    public int rateBpFor(long grossMinor) {
      for (TerBand band : bands) {
        if (grossMinor <= band.upToMinor()) {
          return band.rateBp();
        }
      }
      throw new IllegalStateException(
          "gross "
              + grossMinor
              + " exceeds every TER band's up_to_minor; the top band must be the unbounded"
              + " sentinel "
              + UNBOUNDED_TOP_CAP_MINOR
              + " — fix the band table");
    }
  }

  /**
   * Parsed {@code TER_TABLE} params (PMK 168/2023): the PTKP-status to category (A/B/C) map, each
   * category's band table, and the no-NPWP surcharge (UU PPh Art 21(5a), typically 12000 bp =
   * 120%).
   */
  public record TerTableParams(
      Map<PtkpStatus, String> ptkpCategory,
      Map<String, TerCategoryTable> categories,
      int noNpwpSurchargeBp) {

    /**
     * The TER band table for a PTKP status, resolved via {@code ptkp_category}.
     *
     * @throws IllegalStateException if the status has no mapping (cannot happen for a table parsed
     *     by {@link StatutoryParams#terTable(String)}, which validates every {@link PtkpStatus} is
     *     mapped at load time — defence in depth)
     */
    public TerCategoryTable categoryFor(PtkpStatus status) {
      String category = ptkpCategory.get(status);
      if (category == null) {
        throw new IllegalStateException("TER_TABLE has no ptkp_category mapping for " + status);
      }
      TerCategoryTable table = categories.get(category);
      if (table == null) {
        throw new IllegalStateException(
            "TER_TABLE ptkp_category maps "
                + status
                + " to category '"
                + category
                + "' but categories has no such table");
      }
      return table;
    }
  }

  /**
   * Parsed {@code ANNUAL_PROGRESSIVE} params: the UU HPP 7/2021 Art-17 brackets plus the PMK
   * 250/2008 biaya jabatan (occupational cost) rate + annual cap, and the no-NPWP surcharge.
   */
  public record AnnualProgressiveParams(
      List<Bracket> brackets,
      int occupationalCostBp,
      long occupationalCostCapAnnualMinor,
      int noNpwpSurchargeBp) {}

  /**
   * One overtime multiplier tier: {@code hours} is the tier's hour count, or {@code null} for "and
   * beyond".
   */
  public record HourlyTier(Integer hours, int multiplierBp) {}

  /**
   * Parsed {@code HOURLY_RATE_TABLE} params (PP 35/2021): the monthly-wage divisor and named tiers
   * (e.g. {@code weekday}, {@code rest_day}) of hour-count -&gt; multiplier bands. Dormant in Track
   * P phase P1 — parsed/validated here, consumed only from phase P7.
   */
  public record HourlyRateTableParams(int monthlyDivisor, Map<String, List<HourlyTier>> tiers) {}

  /**
   * Parses progressive-bracket params, sorting brackets ascending by floor (deterministic walk).
   */
  public static ProgressiveParams progressive(String json) {
    JsonNode root = read(json);
    JsonNode brackets = root.get("brackets");
    if (brackets == null || !brackets.isArray() || brackets.isEmpty()) {
      throw new IllegalArgumentException(
          "PROGRESSIVE_BRACKET params require a non-empty brackets[]");
    }
    List<Bracket> parsed = new ArrayList<>();
    for (JsonNode b : brackets) {
      parsed.add(
          new Bracket(
              b.get("floor_minor").asLong(),
              b.get("cap_minor").asLong(),
              b.get("rate_bp").asInt()));
    }
    parsed.sort((a, c) -> Long.compare(a.floorMinor(), c.floorMinor()));
    // Load-time single-source guard (#34): the TOP (highest-floor) bracket's cap MUST be the agreed
    // unbounded sentinel so the top marginal rate covers all income above its floor. A finite top
    // cap is rejected HERE — when the rule is loaded for a run — rather than silently leaving top
    // income untaxed at compute time (walkBrackets keeps a defence-in-depth net, but a
    // misconfigured
    // OFFICIAL table now fails the moment it is resolved, not only for a high-enough earner).
    requireUnboundedTopCap(parsed, "PROGRESSIVE_BRACKET");
    return new ProgressiveParams(List.copyOf(parsed));
  }

  /**
   * Parses percentage-with-ceiling params. {@code base_kind}/{@code employer_adds_to_tax_base} are
   * additive.
   */
  public static CeilingParams ceiling(String json) {
    JsonNode root = read(json);
    BaseKind baseKind =
        root.has("base_kind")
            ? BaseKind.from(root.get("base_kind").asText())
            : BaseKind.TAXABLE_GROSS;
    boolean employerAddsToTaxBase =
        root.has("employer_adds_to_tax_base") && root.get("employer_adds_to_tax_base").asBoolean();
    return new CeilingParams(
        root.get("ceiling_minor").asLong(),
        root.get("employee_bp").asInt(),
        root.get("employer_bp").asInt(),
        root.has("reduces_tax_base") && root.get("reduces_tax_base").asBoolean(),
        baseKind,
        employerAddsToTaxBase);
  }

  /** Parses relief-table (PTKP) params. */
  public static ReliefParams relief(String json) {
    JsonNode root = read(json);
    JsonNode ptkp = root.get("ptkp");
    if (ptkp == null || !ptkp.isObject()) {
      throw new IllegalArgumentException("RELIEF_TABLE params require a ptkp object");
    }
    Map<String, Long> table = new LinkedHashMap<>();
    ptkp.fields().forEachRemaining(e -> table.put(e.getKey(), e.getValue().asLong()));
    int months = root.has("annualization_months") ? root.get("annualization_months").asInt() : 12;
    return new ReliefParams(Map.copyOf(table), months);
  }

  /**
   * Parses PMK 168/2023 TER-table params: the {@code ptkp_category} map (validated to cover EVERY
   * {@link PtkpStatus} — an unmapped status would silently resolve no TER rate at run time) and
   * each referenced category's band table (validated ascending, in-range, unbounded-top — never
   * silently re-sorted: an out-of-order band table is a data error, rejected loudly).
   */
  public static TerTableParams terTable(String json) {
    JsonNode root = read(json);

    JsonNode ptkpCategoryNode = root.get("ptkp_category");
    if (ptkpCategoryNode == null || !ptkpCategoryNode.isObject()) {
      throw new IllegalArgumentException("TER_TABLE params require a ptkp_category object");
    }
    Map<PtkpStatus, String> ptkpCategory = new LinkedHashMap<>();
    ptkpCategoryNode
        .fields()
        .forEachRemaining(
            e -> ptkpCategory.put(PtkpStatus.from(e.getKey()), e.getValue().asText()));
    for (PtkpStatus status : PtkpStatus.values()) {
      if (!ptkpCategory.containsKey(status)) {
        throw new IllegalArgumentException(
            "TER_TABLE ptkp_category is missing a mapping for " + status);
      }
    }

    JsonNode categoriesNode = root.get("categories");
    if (categoriesNode == null || !categoriesNode.isObject()) {
      throw new IllegalArgumentException("TER_TABLE params require a categories object");
    }
    Map<String, TerCategoryTable> categories = new LinkedHashMap<>();
    categoriesNode
        .fields()
        .forEachRemaining(
            e -> categories.put(e.getKey(), terCategoryTable(e.getKey(), e.getValue())));

    for (Map.Entry<PtkpStatus, String> entry : ptkpCategory.entrySet()) {
      if (!categories.containsKey(entry.getValue())) {
        throw new IllegalArgumentException(
            "TER_TABLE ptkp_category maps "
                + entry.getKey()
                + " to category '"
                + entry.getValue()
                + "' but categories has no such table");
      }
    }

    int surchargeBp = requireSurchargeMultiplier(root, "TER_TABLE");
    return new TerTableParams(Map.copyOf(ptkpCategory), Map.copyOf(categories), surchargeBp);
  }

  /**
   * The no-NPWP surcharge is the FULL multiplier in basis points (12000 = ×1.20, UU PPh Art
   * 21(5a)), not the +20% increment — and it is REQUIRED with a hard floor of 10000: a defaulted or
   * sub-10000 value would make a missing NPWP REDUCE tax (an omitted field would zero it, and in
   * the December branch refund the whole year's withholding) — the exact opposite of the
   * conservative posture the spec mandates (P1 review C1). Upper bound 20000 (×2.0) rejects a
   * fat-fingered order of magnitude.
   */
  private static int requireSurchargeMultiplier(JsonNode root, String family) {
    if (!root.has("no_npwp_surcharge_bp")) {
      throw new IllegalArgumentException(
          family
              + " params require no_npwp_surcharge_bp (the FULL multiplier, e.g. 12000 = x1.20"
              + " per UU PPh Art 21(5a)) — omitting it would zero a no-NPWP employee's tax");
    }
    int surchargeBp = root.get("no_npwp_surcharge_bp").asInt();
    if (surchargeBp < 10_000 || surchargeBp > 20_000) {
      throw new IllegalArgumentException(
          family
              + " no_npwp_surcharge_bp is "
              + surchargeBp
              + " but must be within [10000, 20000]: it is the FULL multiplier (12000 = x1.20);"
              + " below 10000 a missing NPWP would REDUCE tax");
    }
    return surchargeBp;
  }

  private static TerCategoryTable terCategoryTable(String categoryKey, JsonNode categoryNode) {
    JsonNode bandsNode = categoryNode.get("bands");
    if (bandsNode == null || !bandsNode.isArray() || bandsNode.isEmpty()) {
      throw new IllegalArgumentException(
          "TER_TABLE category '" + categoryKey + "' requires a non-empty bands[]");
    }
    List<TerBand> bands = new ArrayList<>();
    long previousUpTo = Long.MIN_VALUE;
    for (JsonNode b : bandsNode) {
      long upTo = b.get("up_to_minor").asLong();
      int rateBp = b.get("rate_bp").asInt();
      requireInBasisPointRange(rateBp, "TER_TABLE category '" + categoryKey + "' band rate_bp");
      // Bands must arrive STRICTLY ascending — this is REJECTED, never silently re-sorted (unlike
      // PROGRESSIVE_BRACKET): an out-of-order TER table is a transcription error in the PMK
      // 168/2023 Lampiran that must be fixed in the data, not masked by the loader.
      if (upTo <= previousUpTo) {
        throw new IllegalArgumentException(
            "TER_TABLE category '"
                + categoryKey
                + "' bands must be strictly ascending by up_to_minor (got "
                + upTo
                + " after "
                + previousUpTo
                + ")");
      }
      previousUpTo = upTo;
      bands.add(new TerBand(upTo, rateBp));
    }
    long topUpTo = bands.get(bands.size() - 1).upToMinor();
    if (topUpTo != UNBOUNDED_TOP_CAP_MINOR) {
      throw new IllegalArgumentException(
          "TER_TABLE category '"
              + categoryKey
              + "' top band up_to_minor is "
              + topUpTo
              + " but must be the agreed unbounded sentinel "
              + UNBOUNDED_TOP_CAP_MINOR
              + "; a finite top band would leave gross above it with no rate — fix the band"
              + " table");
    }
    return new TerCategoryTable(List.copyOf(bands));
  }

  /**
   * Parses UU HPP 7/2021 Art-17 annual-progressive params (the December true-up), mirroring {@link
   * #progressive(String)}'s bracket shape/validation plus the PMK 250/2008 biaya jabatan
   * (occupational cost) fields and the no-NPWP surcharge.
   */
  public static AnnualProgressiveParams annualProgressive(String json) {
    JsonNode root = read(json);
    JsonNode brackets = root.get("brackets");
    if (brackets == null || !brackets.isArray() || brackets.isEmpty()) {
      throw new IllegalArgumentException(
          "ANNUAL_PROGRESSIVE params require a non-empty brackets[]");
    }
    List<Bracket> parsed = new ArrayList<>();
    for (JsonNode b : brackets) {
      parsed.add(
          new Bracket(
              b.get("floor_minor").asLong(),
              b.get("cap_minor").asLong(),
              b.get("rate_bp").asInt()));
    }
    parsed.sort((a, c) -> Long.compare(a.floorMinor(), c.floorMinor()));
    requireUnboundedTopCap(parsed, "ANNUAL_PROGRESSIVE");

    if (!root.has("occupational_cost_bp")) {
      throw new IllegalArgumentException("ANNUAL_PROGRESSIVE params require occupational_cost_bp");
    }
    if (!root.has("occupational_cost_cap_annual_minor")) {
      throw new IllegalArgumentException(
          "ANNUAL_PROGRESSIVE params require occupational_cost_cap_annual_minor");
    }
    int occupationalCostBp = root.get("occupational_cost_bp").asInt();
    requireInBasisPointRange(occupationalCostBp, "ANNUAL_PROGRESSIVE occupational_cost_bp");
    int surchargeBp = requireSurchargeMultiplier(root, "ANNUAL_PROGRESSIVE");

    return new AnnualProgressiveParams(
        List.copyOf(parsed),
        occupationalCostBp,
        root.get("occupational_cost_cap_annual_minor").asLong(),
        surchargeBp);
  }

  /**
   * Parses PP 35/2021 overtime hourly-rate-table params: {@code monthly_divisor} (e.g. 173) and
   * named {@code tiers} of hour-count -&gt; multiplier bands. Parse/validate ONLY — {@code
   * HOURLY_RATE_TABLE} is dormant until Track P phase P7 consumes it.
   */
  public static HourlyRateTableParams hourlyRateTable(String json) {
    JsonNode root = read(json);
    if (!root.has("monthly_divisor")) {
      throw new IllegalArgumentException("HOURLY_RATE_TABLE params require monthly_divisor");
    }
    int monthlyDivisor = root.get("monthly_divisor").asInt();
    if (monthlyDivisor <= 0) {
      throw new IllegalArgumentException(
          "HOURLY_RATE_TABLE monthly_divisor must be strictly positive: " + monthlyDivisor);
    }

    JsonNode tiersNode = root.get("tiers");
    if (tiersNode == null || !tiersNode.isObject() || !tiersNode.fieldNames().hasNext()) {
      throw new IllegalArgumentException(
          "HOURLY_RATE_TABLE params require a non-empty tiers object");
    }
    Map<String, List<HourlyTier>> tiers = new LinkedHashMap<>();
    tiersNode
        .fields()
        .forEachRemaining(e -> tiers.put(e.getKey(), hourlyTierList(e.getKey(), e.getValue())));
    return new HourlyRateTableParams(monthlyDivisor, Map.copyOf(tiers));
  }

  private static List<HourlyTier> hourlyTierList(String tierKey, JsonNode tierArray) {
    if (tierArray == null || !tierArray.isArray() || tierArray.isEmpty()) {
      throw new IllegalArgumentException(
          "HOURLY_RATE_TABLE tier '" + tierKey + "' requires a non-empty array");
    }
    List<HourlyTier> parsed = new ArrayList<>();
    int lastIndex = tierArray.size() - 1;
    for (int i = 0; i <= lastIndex; i++) {
      JsonNode t = tierArray.get(i);
      JsonNode hoursNode = t.get("hours");
      Integer hours = (hoursNode == null || hoursNode.isNull()) ? null : hoursNode.asInt();
      if (hours != null && hours <= 0) {
        throw new IllegalArgumentException(
            "HOURLY_RATE_TABLE tier '" + tierKey + "' hours must be positive or null: " + hours);
      }
      if (hours == null && i != lastIndex) {
        throw new IllegalArgumentException(
            "HOURLY_RATE_TABLE tier '"
                + tierKey
                + "' has a null (open-ended) hours entry before the last position — only the LAST"
                + " tier may be open-ended");
      }
      int multiplierBp = t.get("multiplier_bp").asInt();
      if (multiplierBp < 0) {
        throw new IllegalArgumentException(
            "HOURLY_RATE_TABLE tier '"
                + tierKey
                + "' multiplier_bp must be non-negative: "
                + multiplierBp);
      }
      parsed.add(new HourlyTier(hours, multiplierBp));
    }
    return List.copyOf(parsed);
  }

  /**
   * Shared load-time guard (#34, mirrored for {@code ANNUAL_PROGRESSIVE}): the highest-floor
   * bracket's cap must be the agreed unbounded sentinel, else income above it would be silently
   * untaxed.
   */
  private static void requireUnboundedTopCap(List<Bracket> sortedBrackets, String tableLabel) {
    long topCap = sortedBrackets.get(sortedBrackets.size() - 1).capMinor();
    if (topCap != UNBOUNDED_TOP_CAP_MINOR) {
      throw new IllegalArgumentException(
          tableLabel
              + " top bracket cap_minor is "
              + topCap
              + " but must be the agreed unbounded sentinel "
              + UNBOUNDED_TOP_CAP_MINOR
              + "; a finite top cap would leave income above it untaxed — fix the bracket table");
    }
  }

  private static void requireInBasisPointRange(int rateBp, String label) {
    if (rateBp < 0 || rateBp > MAX_BASIS_POINTS) {
      throw new IllegalArgumentException(
          label
              + " "
              + rateBp
              + " is out of the valid basis-point range [0, "
              + MAX_BASIS_POINTS
              + "]");
    }
  }

  private static JsonNode read(String json) {
    try {
      return MAPPER.readTree(json);
    } catch (Exception e) {
      throw new IllegalArgumentException("Invalid statutory params JSON", e);
    }
  }
}
