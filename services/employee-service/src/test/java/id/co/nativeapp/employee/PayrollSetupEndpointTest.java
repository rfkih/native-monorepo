package id.co.nativeapp.employee;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * HTTP-boundary coverage for {@code /api/v1/payroll-setup} — the console's one-click payroll
 * bootstrap PLUS the statutory-rule administration surface (Track P phase P2, ADR 0031). GET
 * reports whether the tenant's pay-component catalog + statutory rules exist and their provenance;
 * {@code seed-illustrative} seeds the ILLUSTRATIVE PLACEHOLDER set (delegating to the existing
 * idempotent {@code IllustrativeStatutorySeedWriter} — the V3 banner and {@code
 * IllustrativeSeedBannerDriftTest} stay untouched); {@code /rules}, {@code /rules/{ruleKey}},
 * {@code seed-official}, and the {@code PATCH} override are the new P2 endpoints. RLS scopes every
 * one of them: one tenant's seed/rules are invisible to another.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "native.dev-tenant-filter.enabled=true")
class PayrollSetupEndpointTest extends PostgresRlsTestBase {

  private static final String TENANT_A = "11111111-1111-1111-1111-111111111111";
  private static final String TENANT_B = "22222222-2222-2222-2222-222222222222";
  private static final String ACTOR = "hr-admin@example.co.id";

  private final ObjectMapper json = new ObjectMapper();

  @Autowired private MockMvc mvc;

  @Test
  void seedIllustrativeIsIdempotentAndRlsScoped() throws Exception {
    // Before: nothing seeded.
    mvc.perform(
            get("/api/v1/payroll-setup").header("X-Company-Id", TENANT_A).header("X-Actor", ACTOR))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.seeded").value(false))
        .andExpect(jsonPath("$.componentCount").value(0))
        .andExpect(jsonPath("$.provenance").isEmpty())
        .andExpect(jsonPath("$.illustrativeVersion").isEmpty());

    // Seed.
    mvc.perform(
            post("/api/v1/payroll-setup/seed-illustrative")
                .header("X-Company-Id", TENANT_A)
                .header("X-Actor", ACTOR)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("baseCurrency", "IDR"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.seeded").value(true))
        .andExpect(jsonPath("$.componentCount").value(6))
        .andExpect(jsonPath("$.provenance").value("ILLUSTRATIVE_PLACEHOLDER"))
        .andExpect(jsonPath("$.illustrativeVersion").value("ILLUSTRATIVE-2026.1"));

    // Idempotent: a second POST changes nothing.
    mvc.perform(
            post("/api/v1/payroll-setup/seed-illustrative")
                .header("X-Company-Id", TENANT_A)
                .header("X-Actor", ACTOR)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("baseCurrency", "IDR"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.componentCount").value(6));

    // RLS: tenant B still sees nothing.
    mvc.perform(
            get("/api/v1/payroll-setup").header("X-Company-Id", TENANT_B).header("X-Actor", ACTOR))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.seeded").value(false));
  }

  @Test
  void seedWithoutABaseCurrencyIsRejected() throws Exception {
    mvc.perform(
            post("/api/v1/payroll-setup/seed-illustrative")
                .header("X-Company-Id", TENANT_A)
                .header("X-Actor", ACTOR)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isBadRequest());
  }

  // ---------------------------------------------------------------------------------------------
  // Track P phase P2 (ADR 0031) — OFFICIAL dataset activation + statutory-rule administration
  // ---------------------------------------------------------------------------------------------

  @Test
  void seedOfficialWithAnUnknownDatasetVersionIs404EvenWithoutAnyPriorSeed() throws Exception {
    mvc.perform(
            post("/api/v1/payroll-setup/seed-official")
                .header("X-Company-Id", TENANT_A)
                .header("X-Actor", ACTOR)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("datasetVersion", "ID-9999.9"))))
        .andExpect(status().isNotFound());
  }

  @Test
  void seedOfficialBeforeAnyIllustrativeSeedIs409() throws Exception {
    // A KNOWN dataset version, but the tenant has no statutory rule on file yet to inherit a
    // currency from — the console never reaches this state (Setup only renders once seeded), but a
    // direct API caller gets a clean 409, not a 500.
    mvc.perform(
            post("/api/v1/payroll-setup/seed-official")
                .header("X-Company-Id", TENANT_A)
                .header("X-Actor", ACTOR)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("datasetVersion", "ID-2026.1"))))
        .andExpect(status().isConflict());
  }

  @Test
  void seedOfficialActivatesTheDatasetClosesTheIllustrativeOverlapRewiresPph21AndIsIdempotent()
      throws Exception {
    seedIllustrative(TENANT_A);

    String firstBody =
        mvc.perform(
                post("/api/v1/payroll-setup/seed-official")
                    .header("X-Company-Id", TENANT_A)
                    .header("X-Actor", ACTOR)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(Map.of("datasetVersion", "ID-2026.1"))))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    JsonNode first = json.readTree(firstBody);
    assertThat(first.get("rulesInserted").asInt()).isEqualTo(10);
    // The illustrative BPJS_KESEHATAN and PTKP_RELIEF rows share a rule_key with an official row
    // dated the SAME day (2026-01-01) — StatutoryRule#supersede deactivates them (never a delete).
    assertThat(first.get("rulesClosed").asInt()).isEqualTo(2);
    assertThat(first.get("rulesSkipped").asInt()).isZero();
    // JHT_EE/ER, JP_EE/ER, JKK_ER, JKM_ER are new; PPh21 REWIRES (statutory_rule_key changes) —
    // an update, not an insert.
    assertThat(first.get("componentsInserted").asInt()).isEqualTo(6);
    assertThat(first.get("componentsUpdated").asInt()).isEqualTo(1);

    // The rules table now carries every historical row: 3 illustrative + 10 official = 13.
    String rulesBody =
        mvc.perform(
                get("/api/v1/payroll-setup/rules")
                    .header("X-Company-Id", TENANT_A)
                    .header("X-Actor", ACTOR))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    JsonNode rules = json.readTree(rulesBody);
    assertThat(rules).hasSize(13);

    // Exactly one row per rule_key is ACTIVE (the "resolution returns exactly one rule per key"
    // invariant PayrollRunWriter.resolveStatutoryRules relies on).
    Map<String, Integer> activeCountByKey = new java.util.HashMap<>();
    rules.forEach(
        r -> {
          if (r.get("active").asBoolean()) {
            activeCountByKey.merge(r.get("ruleKey").asText(), 1, Integer::sum);
          }
        });
    assertThat(activeCountByKey).allSatisfy((key, count) -> assertThat(count).isEqualTo(1));

    // The official BPJS_KESEHATAN row is now the one that resolves (OFFICIAL, not the illustrative
    // placeholder).
    mvc.perform(
            get("/api/v1/payroll-setup/rules/BPJS_KESEHATAN")
                .header("X-Company-Id", TENANT_A)
                .header("X-Actor", ACTOR))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.provenance").value("OFFICIAL"))
        .andExpect(jsonPath("$.ruleVersion").value("ID-2026.1"));

    // PPh21 is rewired to PPH21_TER — OFFICIAL (the PMK 168/2023 Lampiran band tables were
    // transcribed + cross-verified 2026-08-02, ADR 0031); the whole dataset is now OFFICIAL.
    mvc.perform(
            get("/api/v1/payroll-setup/rules/PPH21_TER")
                .header("X-Company-Id", TENANT_A)
                .header("X-Actor", ACTOR))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.provenance").value("OFFICIAL"));

    // Idempotent: a second POST over the SAME version is a byte-identical no-op.
    mvc.perform(
            post("/api/v1/payroll-setup/seed-official")
                .header("X-Company-Id", TENANT_A)
                .header("X-Actor", ACTOR)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("datasetVersion", "ID-2026.1"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rulesInserted").value(0))
        .andExpect(jsonPath("$.rulesClosed").value(0))
        .andExpect(jsonPath("$.rulesSkipped").value(10))
        .andExpect(jsonPath("$.componentsInserted").value(0))
        .andExpect(jsonPath("$.componentsUpdated").value(0));

    // RLS: tenant B sees no rules at all.
    mvc.perform(
            get("/api/v1/payroll-setup/rules")
                .header("X-Company-Id", TENANT_B)
                .header("X-Actor", ACTOR))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0));
  }

  @Test
  void getRuleDetailForAnUnseededRuleKeyIs404() throws Exception {
    seedIllustrative(TENANT_A);
    mvc.perform(
            get("/api/v1/payroll-setup/rules/NO_SUCH_RULE")
                .header("X-Company-Id", TENANT_A)
                .header("X-Actor", ACTOR))
        .andExpect(status().isNotFound());
  }

  @Test
  void overrideCreatesANewEffectiveDatedRowForAPeriodicReVerificationEgBpjsJpsMarchCeiling()
      throws Exception {
    // Every rule in ID-2026.1 ships OFFICIAL (PPH21_TER's PMK 168/2023 bands were transcribed +
    // cross-verified 2026-08-02, ADR 0031), so the override path is now demonstrated on the
    // STANDING re-verification the ADR's checklist calls out: BPJS_JP's ceiling is adjusted every
    // March by BPJS Ketenagakerjaan announcement — this is OFFICIAL-to-OFFICIAL, a correction, not
    // an illustrative-to-official flip.
    seedIllustrative(TENANT_A);
    seedOfficial(TENANT_A);

    String overrideBody =
        json.writeValueAsString(
            Map.of(
                "paramsJson",
                ADJUSTED_BPJS_JP_PARAMS_JSON,
                "effectiveFrom",
                "2026-03-01",
                "sourceNote",
                "BPJS Ketenagakerjaan March 2026 announcement — JP ceiling adjusted to"
                    + " 10,800,000; verified by J. Doe",
                "provenance",
                "OFFICIAL"));

    mvc.perform(
            patch("/api/v1/payroll-setup/rules/BPJS_JP")
                .header("X-Company-Id", TENANT_A)
                .header("X-Actor", ACTOR)
                .contentType(MediaType.APPLICATION_JSON)
                .content(overrideBody))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.ruleKey").value("BPJS_JP"))
        .andExpect(jsonPath("$.provenance").value("OFFICIAL"))
        .andExpect(jsonPath("$.ruleVersion").value("ID-2026.1-OVERRIDE-2026-03-01"))
        .andExpect(jsonPath("$.effectiveFrom").value("2026-03-01"));

    // The rule keeps resolving OFFICIAL, now at the adjusted ceiling.
    mvc.perform(
            get("/api/v1/payroll-setup/rules/BPJS_JP")
                .header("X-Company-Id", TENANT_A)
                .header("X-Actor", ACTOR))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.provenance").value("OFFICIAL"))
        .andExpect(jsonPath("$.paramsJson", org.hamcrest.Matchers.containsString("10800000")));

    // The PRIOR (2026-01-01) row is closed the day before, not deleted — two rows on file.
    String rulesBody =
        mvc.perform(
                get("/api/v1/payroll-setup/rules")
                    .header("X-Company-Id", TENANT_A)
                    .header("X-Actor", ACTOR))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    JsonNode rules = json.readTree(rulesBody);
    long jpRows =
        java.util.stream.StreamSupport.stream(rules.spliterator(), false)
            .filter(r -> r.get("ruleKey").asText().equals("BPJS_JP"))
            .count();
    assertThat(jpRows).isEqualTo(2);
  }

  @Test
  void overrideRejectsAnEffectiveFromNotAfterTheCurrentRowsEffectiveFrom() throws Exception {
    seedIllustrative(TENANT_A);
    seedOfficial(TENANT_A);

    String overrideBody =
        json.writeValueAsString(
            Map.of(
                "paramsJson", VERIFIED_TER_PARAMS_JSON,
                "effectiveFrom", "2026-01-01", // same as the current row's effectiveFrom
                "sourceNote", "attempted same-day override",
                "provenance", "OFFICIAL"));

    mvc.perform(
            patch("/api/v1/payroll-setup/rules/PPH21_TER")
                .header("X-Company-Id", TENANT_A)
                .header("X-Actor", ACTOR)
                .contentType(MediaType.APPLICATION_JSON)
                .content(overrideBody))
        .andExpect(status().isBadRequest());
  }

  @Test
  void overrideAnUnknownRuleKeyIs404() throws Exception {
    seedIllustrative(TENANT_A);
    seedOfficial(TENANT_A);

    String overrideBody =
        json.writeValueAsString(
            Map.of(
                "paramsJson", VERIFIED_TER_PARAMS_JSON,
                "effectiveFrom", "2026-02-01",
                "sourceNote", "n/a",
                "provenance", "OFFICIAL"));

    mvc.perform(
            patch("/api/v1/payroll-setup/rules/NO_SUCH_RULE")
                .header("X-Company-Id", TENANT_A)
                .header("X-Actor", ACTOR)
                .contentType(MediaType.APPLICATION_JSON)
                .content(overrideBody))
        .andExpect(status().isNotFound());
  }

  /** A minimal but VALID TER_TABLE params blob (unbounded sentinel top band, all 8 statuses). */
  private static final String VERIFIED_TER_PARAMS_JSON =
      "{\"ptkp_category\":{\"TK0\":\"A\",\"TK1\":\"A\",\"K0\":\"A\",\"TK2\":\"B\",\"TK3\":\"B\","
          + "\"K1\":\"B\",\"K2\":\"B\",\"K3\":\"C\"},"
          + "\"categories\":{"
          + "\"A\":{\"bands\":[{\"up_to_minor\":5400000,\"rate_bp\":0},"
          + "{\"up_to_minor\":999999999999,\"rate_bp\":3400}]},"
          + "\"B\":{\"bands\":[{\"up_to_minor\":6200000,\"rate_bp\":0},"
          + "{\"up_to_minor\":999999999999,\"rate_bp\":3400}]},"
          + "\"C\":{\"bands\":[{\"up_to_minor\":6600000,\"rate_bp\":0},"
          + "{\"up_to_minor\":999999999999,\"rate_bp\":3400}]}},"
          + "\"no_npwp_surcharge_bp\":12000}";

  /** BPJS_JP's PERCENTAGE_CEILING shape with a hypothetical March-adjusted ceiling. */
  private static final String ADJUSTED_BPJS_JP_PARAMS_JSON =
      "{\"ceiling_minor\":10800000,\"employee_bp\":100,\"employer_bp\":200,"
          + "\"reduces_tax_base\":true,\"employer_adds_to_tax_base\":false}";

  private void seedIllustrative(String tenant) throws Exception {
    mvc.perform(
            post("/api/v1/payroll-setup/seed-illustrative")
                .header("X-Company-Id", tenant)
                .header("X-Actor", ACTOR)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("baseCurrency", "IDR"))))
        .andExpect(status().isOk());
  }

  private void seedOfficial(String tenant) throws Exception {
    mvc.perform(
            post("/api/v1/payroll-setup/seed-official")
                .header("X-Company-Id", tenant)
                .header("X-Actor", ACTOR)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("datasetVersion", "ID-2026.1"))))
        .andExpect(status().isOk());
  }
}
