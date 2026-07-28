package id.co.nativeapp.employee;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import id.co.nativeapp.employee.org.dto.OrgUnitProjectedEvent;
import id.co.nativeapp.employee.org.service.OrgProjectionService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * HTTP-boundary coverage for the payroll-run READ endpoints the console Payroll tab renders: the
 * per-period run list (newest {@code run_seq} first — a re-run is an additional posting), the
 * per-outlet labor-cost allocation summary (aggregated across employees — {@code employee_id} would
 * leak per-person salary, rule 6, so the response must not carry it), and the payslip index (one
 * row per employee, no amounts). Drives a REAL run twice through {@code POST /api/v1/payroll-runs}
 * over the seeded illustrative setup.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "native.dev-tenant-filter.enabled=true")
class PayrollRunReadEndpointsTest extends PostgresRlsTestBase {

  private static final String TENANT_A = "11111111-1111-1111-1111-111111111111";
  private static final String TENANT_B = "22222222-2222-2222-2222-222222222222";
  private static final String ACTOR = "hr-admin@example.co.id";
  private static final UUID LEGAL_EMPLOYER =
      UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa1");
  private static final String PERIOD = "2026-07";

  private final ObjectMapper json = new ObjectMapper();

  @Autowired private MockMvc mvc;
  @Autowired private OrgProjectionService orgProjectionService;

  @Test
  void listsRunsAggregatesAllocationsAndIndexesPayslipsWithoutPii() throws Exception {
    // Setup: illustrative catalog + one employee assigned to an outlet with compensation, one
    // employee with compensation but NO assignment (falls to the UNALLOCATED bucket).
    seedIllustrative();
    UUID outlet = seedOrgUnit(TENANT_A, "outlet");
    UUID assigned = createEmployee("Rina Kasir", "3204000000000001", "4444555566660001");
    UUID unassigned = createEmployee("Tono Gudang", "3204000000000002", "4444555566660002");
    UUID contractA = addContract(assigned);
    UUID contractB = addContract(unassigned);
    addAssignment(assigned, outlet, "cashier", "2026-01-01");
    addCompensation(assigned, contractA, 500000000L);
    addCompensation(unassigned, contractB, 300000000L);

    UUID run1 = runPayroll(List.of(assigned, unassigned));
    UUID run2 = runPayroll(List.of(assigned, unassigned)); // corrective re-run -> run_seq 2

    // Run list for the period — newest run_seq first, company totals only.
    mvc.perform(
            get("/api/v1/payroll-runs")
                .param("period", PERIOD)
                .header("X-Company-Id", TENANT_A)
                .header("X-Actor", ACTOR))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(2))
        .andExpect(jsonPath("$[0].id").value(run2.toString()))
        .andExpect(jsonPath("$[0].runSeq").value(2))
        .andExpect(jsonPath("$[1].id").value(run1.toString()))
        .andExpect(jsonPath("$[0].usesIllustrativeRules").value(true));

    // Allocation summary — aggregated per (outlet, gl_account); NO employee_id key anywhere;
    // the unassigned employee's employer cost lands in the flagged UNALLOCATED bucket.
    String allocations =
        mvc.perform(
                get("/api/v1/payroll-runs/" + run1 + "/allocations")
                    .header("X-Company-Id", TENANT_A)
                    .header("X-Actor", ACTOR))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(allocations).doesNotContain("employeeId").doesNotContain("employee_id");
    assertThat(allocations).contains(outlet.toString());
    JsonNode rows = json.readTree(allocations);
    assertThat(rows.size()).isGreaterThanOrEqualTo(2);
    boolean sawUnallocated = false;
    for (JsonNode row : rows) {
      if (row.get("unallocated").asBoolean()) {
        sawUnallocated = true;
        assertThat(row.get("glAccount").asText()).isEqualTo("9999-UNALLOCATED-LABOR");
      }
      assertThat(row.get("amountMinor").asLong()).isGreaterThan(0L);
      assertThat(row.get("currency").asText()).isEqualTo("IDR");
    }
    assertThat(sawUnallocated).isTrue();

    // Payslip index — one row per employee, a line count, NO amounts.
    String index =
        mvc.perform(
                get("/api/v1/payroll-runs/" + run1 + "/payslips")
                    .header("X-Company-Id", TENANT_A)
                    .header("X-Actor", ACTOR))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(2))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(index).contains("Rina Kasir").contains("Tono Gudang");
    assertThat(index)
        .doesNotContain("amountMinor")
        .doesNotContain("500000000")
        .doesNotContain("300000000");
    JsonNode indexRows = json.readTree(index);
    for (JsonNode row : indexRows) {
      assertThat(row.get("lineCount").asInt()).isGreaterThan(0);
      assertThat(row.get("illustrative").asBoolean()).isTrue();
    }

    // Unknown run -> 404 on both reads; RLS: tenant B sees an empty period list.
    mvc.perform(
            get("/api/v1/payroll-runs/" + UUID.randomUUID() + "/allocations")
                .header("X-Company-Id", TENANT_A)
                .header("X-Actor", ACTOR))
        .andExpect(status().isNotFound());
    mvc.perform(
            get("/api/v1/payroll-runs/" + run1 + "/allocations")
                .header("X-Company-Id", TENANT_B)
                .header("X-Actor", ACTOR))
        .andExpect(status().isNotFound());
    mvc.perform(
            get("/api/v1/payroll-runs")
                .param("period", PERIOD)
                .header("X-Company-Id", TENANT_B)
                .header("X-Actor", ACTOR))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0));
  }

  @Test
  void theRunListRequiresAWellFormedPeriod() throws Exception {
    mvc.perform(
            get("/api/v1/payroll-runs").header("X-Company-Id", TENANT_A).header("X-Actor", ACTOR))
        .andExpect(status().isBadRequest());
    mvc.perform(
            get("/api/v1/payroll-runs")
                .param("period", "July 2026")
                .header("X-Company-Id", TENANT_A)
                .header("X-Actor", ACTOR))
        .andExpect(status().isBadRequest());
  }

  // ---- helpers --------------------------------------------------------------------------------

  private void seedIllustrative() throws Exception {
    mvc.perform(
            post("/api/v1/payroll-setup/seed-illustrative")
                .header("X-Company-Id", TENANT_A)
                .header("X-Actor", ACTOR)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("baseCurrency", "IDR"))))
        .andExpect(status().isOk());
  }

  private UUID runPayroll(List<UUID> employeeIds) throws Exception {
    String body =
        json.writeValueAsString(
            Map.of(
                "period",
                PERIOD,
                "employeeIds",
                employeeIds.stream().map(UUID::toString).toList(),
                "baseCurrency",
                "IDR"));
    String response =
        mvc.perform(
                post("/api/v1/payroll-runs")
                    .header("X-Company-Id", TENANT_A)
                    .header("X-Actor", ACTOR)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return UUID.fromString(json.readTree(response).get("id").asText());
  }

  private UUID createEmployee(String name, String nik, String bank) throws Exception {
    String body =
        json.writeValueAsString(
            Map.of("fullName", name, "ptkpStatus", "TK0", "nik", nik, "bankAccount", bank));
    String response =
        mvc.perform(
                post("/api/v1/employees")
                    .header("X-Company-Id", TENANT_A)
                    .header("X-Actor", ACTOR)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return UUID.fromString(json.readTree(response).get("id").asText());
  }

  private UUID addContract(UUID employeeId) throws Exception {
    String body =
        json.writeValueAsString(
            Map.of(
                "employmentType", "PERMANENT",
                "legalEmployerId", LEGAL_EMPLOYER.toString(),
                "effectiveFrom", "2026-01-01"));
    String response =
        mvc.perform(
                post("/api/v1/employees/" + employeeId + "/contracts")
                    .header("X-Company-Id", TENANT_A)
                    .header("X-Actor", ACTOR)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return UUID.fromString(json.readTree(response).get("id").asText());
  }

  private void addAssignment(UUID employeeId, UUID orgUnitId, String role, String from)
      throws Exception {
    mvc.perform(
            post("/api/v1/employees/" + employeeId + "/assignments")
                .header("X-Company-Id", TENANT_A)
                .header("X-Actor", ACTOR)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        Map.of(
                            "orgUnitId", orgUnitId.toString(),
                            "role", role,
                            "effectiveFrom", from))))
        .andExpect(status().isCreated());
  }

  private void addCompensation(UUID employeeId, UUID contractId, long basePayMinor)
      throws Exception {
    mvc.perform(
            post("/api/v1/employees/" + employeeId + "/compensation")
                .header("X-Company-Id", TENANT_A)
                .header("X-Actor", ACTOR)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        Map.of(
                            "employmentContractId",
                            contractId.toString(),
                            "basePayMinor",
                            basePayMinor,
                            "currency",
                            "IDR",
                            "effectiveFrom",
                            "2026-01-01"))))
        .andExpect(status().isCreated());
  }

  private UUID seedOrgUnit(String tenant, String type) {
    UUID orgUnitId = UUID.randomUUID();
    orgProjectionService.apply(
        new OrgUnitProjectedEvent(
            UUID.randomUUID(), orgUnitId, tenant, LEGAL_EMPLOYER, type, true));
    return orgUnitId;
  }
}
