package id.co.nativeapp.employee;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.employee.payroll.service.IllustrativeStatutorySeedWriter;
import id.co.nativeapp.tenant.TenantContext;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Seeder/banner DRIFT GUARD: the V3 migration banner ({@code
 * V3__seed_illustrative_statutory_rules.sql}) is the DOCUMENTED source of truth for the
 * illustrative statutory figures, and {@link IllustrativeStatutorySeedWriter} is the code that
 * actually inserts them. This asserts the two cannot drift apart — every documented figure appears
 * verbatim in BOTH the seeded {@code params_json} (read back from the real DB after the seeder
 * runs) and the migration banner.
 */
@SpringBootTest
class IllustrativeSeedBannerDriftTest extends PostgresRlsTestBase {

  private static final String TENANT_A = "11111111-1111-1111-1111-111111111111";
  private static final String ACTOR_A = "hr-admin-a@example.co.id";

  private static final Path V3_MIGRATION =
      Path.of(
          "src",
          "main",
          "resources",
          "db",
          "migration",
          "V3__seed_illustrative_statutory_rules.sql");

  /**
   * The documented illustrative figures (the numbers in the V3 banner). Each must appear verbatim
   * in both the seeded params_json and the (comma-normalized) banner text. Keyed by rule_key.
   */
  private static final Map<String, List<String>> DOCUMENTED_FIGURES =
      Map.of(
          "BPJS_KESEHATAN", List.of("10000000", "100", "400"),
          "PPH21_PROGRESSIVE", List.of("50000000", "1000", "1500"),
          "PTKP_RELIEF", List.of("54000000", "58500000", "63000000", "67500000", "72000000", "12"));

  @Autowired private IllustrativeStatutorySeedWriter seeder;

  @Test
  void seededParamsJsonMatchesTheDocumentedV3BannerFigures() throws Exception {
    // The banner documents amounts with thousands separators (10,000,000); the seeded params_json
    // stores raw minor units (10000000). Normalize the banner by stripping commas so the same digit
    // sequence is comparable against both.
    String banner = Files.readString(V3_MIGRATION, StandardCharsets.UTF_8).replace(",", "");

    TenantContext.runAs(TENANT_A, ACTOR_A, () -> seeder.seed("IDR"));

    // Read the seeded params back over the admin/BYPASSRLS connection (no tenant GUC needed) so RLS
    // is not the variable — this asserts the figures the SEEDER actually inserted.
    Map<String, String> seededByKey = readSeededParamsAsAdmin();

    DOCUMENTED_FIGURES.forEach(
        (ruleKey, figures) -> {
          String seededJson = seededByKey.get(ruleKey);
          assertThat(seededJson).as("seeded params for " + ruleKey).isNotNull();
          for (String figure : figures) {
            assertThat(seededJson)
                .as("figure %s present in seeded %s params_json", figure, ruleKey)
                .contains(figure);
            assertThat(banner)
                .as("figure %s present in the V3 banner for %s", figure, ruleKey)
                .contains(figure);
          }
        });

    // The version label is the single source of the illustrative stamp; it must be in the banner.
    assertThat(banner).contains(IllustrativeStatutorySeedWriter.ILLUSTRATIVE_VERSION);
  }

  private Map<String, String> readSeededParamsAsAdmin() throws Exception {
    Map<String, String> byKey = new HashMap<>();
    try (Connection admin =
            java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Statement st = admin.createStatement();
        ResultSet rs =
            st.executeQuery(
                "SELECT rule_key, params_json FROM statutory_rule WHERE company_id = '"
                    + TENANT_A
                    + "'")) {
      while (rs.next()) {
        byKey.put(rs.getString("rule_key"), rs.getString("params_json"));
      }
    }
    return byKey;
  }
}
