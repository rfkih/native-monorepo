package id.co.nativeapp.employee.payroll.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Loads a canned, versioned statutory dataset (Track P phase P2, ADR 0031) from the classpath —
 * {@code statutory-datasets/<version>.json} — and validates every rule's {@code params} through the
 * matching {@link StatutoryParams} family parser AT LOAD TIME via {@link StatutoryParams#validate}:
 * the transcription-safety net. A malformed figure fails the moment the dataset loads (a build-time
 * test loads every shipped dataset), never the first time a payroll run resolves the rule.
 *
 * <p>Pure JDK + Jackson (no Spring, mirroring {@link StatutoryParams}'s existing precedent) —
 * {@code OfficialStatutorySeedWriter} is the only caller that persists what this class parses.
 *
 * <p>Dataset JSON shape: {@code {"dataset_version": "...", "rules": [{"rule_key", "rule_version",
 * "calc_type", "provenance", "source_note", "effective_from", "params": {...}}, ...], "components":
 * [{"component_key", "kind", "calc_type", "bearer", "gl_account", "taxable", "statutory_rule_key",
 * "display_order"}, ...]}}.
 */
public final class OfficialStatutoryDataset {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private OfficialStatutoryDataset() {
    // static loader
  }

  /**
   * One dataset rule row, already validated against its own calc-type's family parser.
   *
   * @param paramsJson the rule's {@code params} object, re-serialized as a JSON string (the exact
   *     shape {@link StatutoryRule#getParamsJson()} stores)
   */
  public record DatasetRule(
      String ruleKey,
      String ruleVersion,
      StatutoryCalcType calcType,
      RuleProvenance provenance,
      String sourceNote,
      LocalDate effectiveFrom,
      String paramsJson) {}

  /**
   * One dataset pay-component-catalog row to upsert (insert-if-absent, else align fields). {@code
   * runScope} (Track P Phase P8, ADR 0035) defaults to {@link RunScope#ALL} when the dataset JSON
   * omits the {@code run_scope} key — every pre-P8 dataset file (ID-2026.1, ID-2026.2) stays valid
   * unchanged.
   */
  public record DatasetComponent(
      String componentKey,
      PayComponentKind kind,
      CalcType calcType,
      PayComponentBearer bearer,
      String glAccount,
      boolean taxable,
      String statutoryRuleKey,
      int displayOrder,
      RunScope runScope) {}

  /** A fully-parsed, load-time-validated dataset. */
  public record Dataset(
      String datasetVersion, List<DatasetRule> rules, List<DatasetComponent> components) {}

  /**
   * Loads and validates the named dataset from the classpath, or {@link Optional#empty()} when no
   * such resource exists (an unknown {@code datasetVersion} — the caller maps this to a 404).
   *
   * @throws IllegalArgumentException if the resource exists but fails to parse, or any rule's
   *     {@code params} fail its family parser (a transcription error caught at load time)
   */
  public static Optional<Dataset> load(String datasetVersion) {
    String resourcePath = "/statutory-datasets/" + datasetVersion + ".json";
    try (InputStream in = OfficialStatutoryDataset.class.getResourceAsStream(resourcePath)) {
      if (in == null) {
        return Optional.empty();
      }
      JsonNode root = MAPPER.readTree(in);
      String rootVersion = root.get("dataset_version").asText();

      List<DatasetRule> rules = new ArrayList<>();
      for (JsonNode ruleNode : root.get("rules")) {
        rules.add(parseRule(ruleNode));
      }
      List<DatasetComponent> components = new ArrayList<>();
      for (JsonNode componentNode : root.get("components")) {
        components.add(parseComponent(componentNode));
      }
      return Optional.of(new Dataset(rootVersion, List.copyOf(rules), List.copyOf(components)));
    } catch (IOException e) {
      throw new IllegalArgumentException(
          "Failed to read statutory dataset '" + datasetVersion + "'", e);
    }
  }

  private static DatasetRule parseRule(JsonNode node) {
    StatutoryCalcType calcType =
        StatutoryCalcType.valueOf(node.get("calc_type").asText().toUpperCase(Locale.ROOT));
    RuleProvenance provenance =
        RuleProvenance.valueOf(node.get("provenance").asText().toUpperCase(Locale.ROOT));
    String paramsJson = node.get("params").toString();
    // Fail the moment the dataset loads if a rule's params do not parse through its OWN calc
    // family — the transcription-safety net (never wait for a run to discover a malformed figure).
    StatutoryParams.validate(calcType, paramsJson);
    return new DatasetRule(
        node.get("rule_key").asText(),
        node.get("rule_version").asText(),
        calcType,
        provenance,
        node.get("source_note").asText(),
        LocalDate.parse(node.get("effective_from").asText()),
        paramsJson);
  }

  private static DatasetComponent parseComponent(JsonNode node) {
    JsonNode ruleKeyNode = node.get("statutory_rule_key");
    String statutoryRuleKey =
        (ruleKeyNode == null || ruleKeyNode.isNull()) ? null : ruleKeyNode.asText();
    JsonNode runScopeNode = node.get("run_scope");
    RunScope runScope =
        (runScopeNode == null || runScopeNode.isNull())
            ? RunScope.ALL
            : RunScope.valueOf(runScopeNode.asText().toUpperCase(Locale.ROOT));
    return new DatasetComponent(
        node.get("component_key").asText(),
        PayComponentKind.valueOf(node.get("kind").asText().toUpperCase(Locale.ROOT)),
        CalcType.valueOf(node.get("calc_type").asText().toUpperCase(Locale.ROOT)),
        PayComponentBearer.valueOf(node.get("bearer").asText().toUpperCase(Locale.ROOT)),
        node.get("gl_account").asText(),
        node.get("taxable").asBoolean(),
        statutoryRuleKey,
        node.get("display_order").asInt(),
        runScope);
  }
}
