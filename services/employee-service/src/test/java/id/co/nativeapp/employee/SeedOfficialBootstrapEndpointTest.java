package id.co.nativeapp.employee;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import id.co.nativeapp.employee.assignment.dto.AddAssignmentCommand;
import id.co.nativeapp.employee.assignment.service.AssignmentService;
import id.co.nativeapp.employee.employee.dto.AddContractCommand;
import id.co.nativeapp.employee.employee.dto.CreateEmployeeCommand;
import id.co.nativeapp.employee.employee.service.EmployeeService;
import id.co.nativeapp.employee.org.dto.OrgUnitProjectedEvent;
import id.co.nativeapp.employee.org.service.OrgProjectionService;
import id.co.nativeapp.employee.payroll.dto.PayrollRunResponse;
import id.co.nativeapp.employee.payroll.dto.RunPayrollCommand;
import id.co.nativeapp.employee.payroll.service.CompensationWriter;
import id.co.nativeapp.employee.payroll.service.PayrollRunReader;
import id.co.nativeapp.employee.payroll.service.PayrollRunService;
import id.co.nativeapp.money.Money;
import id.co.nativeapp.tenant.TenantContext;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * HTTP-boundary + end-to-end proof for {@code POST /api/v1/payroll-setup/seed-official-bootstrap}
 * (ADR 0042 go-live default): on a FRESH tenant — zero {@code statutory_rule}/{@code pay_component}
 * rows, the exact state a brand-new company starts in — one call must land the tenant fully
 * OFFICIAL. Mirrors {@link PayrollSetupEndpointTest}'s HTTP style for the endpoint assertions and
 * {@link OfficialDatasetIllustrativeFlagEndToEndTest}'s pattern for the downstream payroll-run
 * proof: a REAL run computed after the bootstrap must not be flagged illustrative on either surface
 * a real consumer reads it from (the persisted column here; the outbox Avro payload is already
 * covered by the sibling test over the two-step seed-illustrative + seed-official path — both paths
 * end at the identical DB state via the identical writers, so proving one surface here is
 * sufficient to catch a bootstrap-specific regression).
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "native.dev-tenant-filter.enabled=true")
class SeedOfficialBootstrapEndpointTest extends PostgresRlsTestBase {

  private static final String TENANT_A = "11111111-1111-1111-1111-111111111111";
  private static final String TENANT_B = "22222222-2222-2222-2222-222222222222";
  private static final String ACTOR = "hr-admin@example.co.id";
  private static final UUID LEGAL_EMPLOYER =
      UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
  private static final String IDR = "IDR";
  private static final long BASE_MINOR = 20_000_000L;

  private final ObjectMapper json = new ObjectMapper();

  @Autowired private MockMvc mvc;
  @Autowired private EmployeeService employeeService;
  @Autowired private CompensationWriter compensationWriter;
  @Autowired private OrgProjectionService orgProjectionService;
  @Autowired private PayrollRunService payrollRunService;
  @Autowired private AssignmentService assignmentService;
  @Autowired private PayrollRunReader payrollRunReader;

  @Test
  void
      bootstrapOnAFreshTenantLandsFullyOfficialWithDefaultDatasetVersionAndAPostedRunIsNotFlaggedIllustrative()
          throws Exception {
    // Before: nothing seeded — a brand-new tenant.
    mvc.perform(
            get("/api/v1/payroll-setup").header("X-Company-Id", TENANT_A).header("X-Actor", ACTOR))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.seeded").value(false))
        .andExpect(jsonPath("$.provenance").isEmpty());

    // datasetVersion omitted — must default to ID-2026.1 (ADR 0042).
    String body =
        mvc.perform(
                post("/api/v1/payroll-setup/seed-official-bootstrap")
                    .header("X-Company-Id", TENANT_A)
                    .header("X-Actor", ACTOR)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(Map.of("baseCurrency", "IDR"))))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    JsonNode response = json.readTree(body);
    assertThat(response.get("datasetVersion").asText()).isEqualTo("ID-2026.1");
    // Same shape as the two-step seed-illustrative + seed-official activation over a fresh
    // catalog
    // (PayrollSetupEndpointTest#seedOfficialActivatesTheDatasetClosesTheIllustrativeOverlapRewiresPph21AndIsIdempotent):
    // 10 dataset rules inserted; 3 closed (BPJS_KESEHATAN + PTKP_RELIEF supersession, plus the
    // PPH21_PROGRESSIVE orphan sweep); 6 new + 1 rewired (PPH21 -> PPH21_TER) component.
    assertThat(response.get("rulesInserted").asInt()).isEqualTo(10);
    assertThat(response.get("rulesClosed").asInt()).isEqualTo(3);
    assertThat(response.get("componentsInserted").asInt()).isEqualTo(6);
    assertThat(response.get("componentsUpdated").asInt()).isEqualTo(1);

    // Status: seeded, OFFICIAL — never MIXED, never ILLUSTRATIVE_PLACEHOLDER.
    mvc.perform(
            get("/api/v1/payroll-setup").header("X-Company-Id", TENANT_A).header("X-Actor", ACTOR))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.seeded").value(true))
        .andExpect(jsonPath("$.provenance").value("OFFICIAL"));

    // The orphan illustrative PPH21_PROGRESSIVE rule is on file (never deleted) but deactivated —
    // it must not resolve.
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
    boolean pph21ProgressiveStillActive =
        StreamSupport.stream(rules.spliterator(), false)
            .anyMatch(
                r ->
                    r.get("ruleKey").asText().equals("PPH21_PROGRESSIVE")
                        && r.get("active").asBoolean());
    assertThat(pph21ProgressiveStillActive)
        .as("PPH21_PROGRESSIVE must be deactivated by the orphan sweep, not left resolvable")
        .isFalse();

    // Idempotent: a second bootstrap call over the same defaults is a no-op.
    mvc.perform(
            post("/api/v1/payroll-setup/seed-official-bootstrap")
                .header("X-Company-Id", TENANT_A)
                .header("X-Actor", ACTOR)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("baseCurrency", "IDR"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rulesInserted").value(0))
        .andExpect(jsonPath("$.rulesClosed").value(0))
        .andExpect(jsonPath("$.componentsInserted").value(0))
        .andExpect(jsonPath("$.componentsUpdated").value(0));

    // RLS: tenant B still sees nothing.
    mvc.perform(
            get("/api/v1/payroll-setup").header("X-Company-Id", TENANT_B).header("X-Actor", ACTOR))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.seeded").value(false));

    // A REAL payroll run computed after the bootstrap must not be flagged illustrative — the C1
    // proof (OfficialDatasetIllustrativeFlagEndToEndTest), mirrored here for the bootstrap path.
    UUID runId =
        TenantContext.callAs(
            TENANT_A,
            ACTOR,
            () -> {
              UUID outlet = UUID.randomUUID();
              orgProjectionService.apply(
                  new OrgUnitProjectedEvent(
                      UUID.randomUUID(), outlet, TENANT_A, LEGAL_EMPLOYER, "OUTLET", true));

              UUID employeeId =
                  employeeService
                      .create(
                          new CreateEmployeeCommand(
                              "Wulan", "TK0", "3209555555555555", "6666777788889999"))
                      .getId();
              var contract =
                  employeeService.addContract(
                      new AddContractCommand(
                          employeeId,
                          "PERMANENT",
                          LEGAL_EMPLOYER,
                          LocalDate.of(2026, 1, 1),
                          LocalDate.of(9999, 12, 31)));
              compensationWriter.createPackage(
                  employeeId,
                  contract.getId(),
                  Money.ofMinor(BASE_MINOR, IDR),
                  LocalDate.of(2026, 1, 1),
                  LocalDate.of(9999, 12, 31));
              assignmentService.add(
                  new AddAssignmentCommand(
                      employeeId,
                      outlet,
                      null,
                      "cashier",
                      LocalDate.of(2026, 1, 1),
                      LocalDate.of(9999, 12, 31)));

              return payrollRunService
                  .calculateAndPost(
                      new RunPayrollCommand("2026-06", List.of(employeeId), List.of()), IDR)
                  .getId();
            });

    PayrollRunResponse run =
        TenantContext.callAs(TENANT_A, ACTOR, () -> payrollRunReader.findRun(runId).orElseThrow());
    assertThat(run.status()).isEqualTo("POSTED");
    assertThat(run.usesIllustrativeRules()).isFalse();
  }

  @Test
  void bootstrapWithoutABaseCurrencyIsRejected() throws Exception {
    mvc.perform(
            post("/api/v1/payroll-setup/seed-official-bootstrap")
                .header("X-Company-Id", TENANT_A)
                .header("X-Actor", ACTOR)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void bootstrapWithAnUnknownDatasetVersionIs404AndRollsBackTheWholeTransactionSeedingNothing()
      throws Exception {
    mvc.perform(
            post("/api/v1/payroll-setup/seed-official-bootstrap")
                .header("X-Company-Id", TENANT_A)
                .header("X-Actor", ACTOR)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        Map.of("baseCurrency", "IDR", "datasetVersion", "ID-9999.9"))))
        .andExpect(status().isNotFound());

    // One transaction: the illustrative-catalog bootstrap half of the call must roll back too, not
    // leave the tenant half-seeded.
    mvc.perform(
            get("/api/v1/payroll-setup").header("X-Company-Id", TENANT_A).header("X-Actor", ACTOR))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.seeded").value(false));
  }
}
