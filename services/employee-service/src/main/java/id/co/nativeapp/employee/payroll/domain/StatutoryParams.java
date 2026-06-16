package id.co.nativeapp.employee.payroll.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
 *       "reduces_tax_base":bool}}.
 *   <li>{@code RELIEF_TABLE}: {@code {"ptkp":{"TK0":L,"K1":L,...},"annualization_months":I}}.
 * </ul>
 */
public final class StatutoryParams {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /**
   * The agreed "effectively unbounded" sentinel for a PROGRESSIVE table's TOP bracket {@code
   * cap_minor} (#34). A progressive schedule's highest bracket must extend to +infinity so the top
   * marginal rate applies to ALL income above its floor; a FINITE top cap would silently leave
   * income above it UNTAXED. We model +infinity as this large-but-finite constant (well above any
   * realistic annualized IDR/USD minor-unit income, yet inside {@code long}/{@code jsonb} range)
   * and REJECT any progressive table whose top cap is not exactly this value at load time — turning
   * the existing run-time {@code walkBrackets} safety net into a fail-fast load-time invariant.
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

  /** Parsed {@code PERCENTAGE_CEILING} params. */
  public record CeilingParams(
      long ceilingMinor, int employeeBp, int employerBp, boolean reducesTaxBase) {}

  /** Parsed {@code RELIEF_TABLE} params: PTKP relief per status + annualization months. */
  public record ReliefParams(Map<String, Long> ptkpByStatus, int annualizationMonths) {}

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
    long topCap = parsed.get(parsed.size() - 1).capMinor();
    if (topCap != UNBOUNDED_TOP_CAP_MINOR) {
      throw new IllegalArgumentException(
          "PROGRESSIVE_BRACKET top bracket cap_minor is "
              + topCap
              + " but must be the agreed unbounded sentinel "
              + UNBOUNDED_TOP_CAP_MINOR
              + "; a finite top cap would leave income above it untaxed — fix the bracket table");
    }
    return new ProgressiveParams(List.copyOf(parsed));
  }

  /** Parses percentage-with-ceiling params. */
  public static CeilingParams ceiling(String json) {
    JsonNode root = read(json);
    return new CeilingParams(
        root.get("ceiling_minor").asLong(),
        root.get("employee_bp").asInt(),
        root.get("employer_bp").asInt(),
        root.has("reduces_tax_base") && root.get("reduces_tax_base").asBoolean());
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

  private static JsonNode read(String json) {
    try {
      return MAPPER.readTree(json);
    } catch (Exception e) {
      throw new IllegalArgumentException("Invalid statutory params JSON", e);
    }
  }
}
