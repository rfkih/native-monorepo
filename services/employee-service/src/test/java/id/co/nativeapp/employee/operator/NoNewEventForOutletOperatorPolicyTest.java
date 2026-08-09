package id.co.nativeapp.employee.operator;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A structural regression guard for the ADR 0049 per-outlet operator-PIN policy: the "rule-2"
 * design decision was that the policy lives ENTIRELY inside employee-service — no new Avro event
 * schema, no new {@code docs/EVENT-CATALOG.md} entry, no cross-service contract at all. This is a
 * plain-JUnit filesystem check (no Spring context) so it fails loudly if a future change silently
 * reintroduces an event for this feature without the deliberate catalog + schema + contract-test
 * process CLAUDE.md rule 7 requires.
 */
class NoNewEventForOutletOperatorPolicyTest {

  private static final String MARKER = "OutletOperatorPolicy";

  @Test
  void noAvroSchemaMentionsTheOutletOperatorPolicyFeature() throws IOException {
    File avroDir = findFromRepoRoot("libs/contracts/src/main/resources/avro");
    File[] schemas = avroDir.listFiles((dir, name) -> name.endsWith(".avsc"));
    assertThat(schemas).isNotNull();
    for (File schema : schemas) {
      String content = Files.readString(schema.toPath());
      assertThat(content)
          .as(
              "no Avro schema (%s) should reference the outlet-operator-policy feature — ADR 0049"
                  + " Phase 1 decision: the policy stays inside employee-service, no event",
              schema.getName())
          .doesNotContain(MARKER);
    }
  }

  @Test
  void eventCatalogHasNoOutletOperatorPolicyEntry() throws IOException {
    File catalog = findFromRepoRoot("docs/EVENT-CATALOG.md");
    String content = Files.readString(catalog.toPath());
    assertThat(content)
        .as(
            "docs/EVENT-CATALOG.md should not carry an entry for the outlet-operator-policy"
                + " feature — ADR 0049 Phase 1 decision: no event/contract change")
        .doesNotContain(MARKER);
  }

  /**
   * Resolves a repo-relative path regardless of whether the test JVM's working directory is the
   * repo root or this module's own directory (Gradle's default per-module working directory).
   */
  private static File findFromRepoRoot(String relativePath) {
    for (String prefix : List.of("", "../../", "../", "../../../")) {
      File candidate = new File(prefix + relativePath);
      if (candidate.exists()) {
        return candidate;
      }
    }
    throw new IllegalStateException(
        "Could not locate " + relativePath + " from " + new File(".").getAbsolutePath());
  }
}
