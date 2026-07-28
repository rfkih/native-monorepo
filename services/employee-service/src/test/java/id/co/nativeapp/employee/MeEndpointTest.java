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
 * HTTP-boundary coverage for the employee self-service surface ({@code /api/v1/me/**}).
 *
 * <p>The caller is resolved EXCLUSIVELY from {@code X-Actor} (the JWT sub the gateway injects) via
 * the V7 employee↔user link — never from a request parameter, so a caller can only ever see their
 * OWN data. The payslip detail is the FIRST caller of the authorized (decrypted) read: an
 * employee's own amounts are real; NIK/bank stay masked even to themselves. An unlinked login gets
 * a 404 not-linked problem, and a run without the caller's lines is a 404 (anti-enumeration).
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "native.dev-tenant-filter.enabled=true")
class MeEndpointTest extends PostgresRlsTestBase {

  private static final String TENANT = "11111111-1111-1111-1111-111111111111";
  private static final String HR_ACTOR = "hr-admin@example.co.id";
  private static final UUID LEGAL_EMPLOYER =
      UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa1");
  private static final String PERIOD = "2026-07";

  private final ObjectMapper json = new ObjectMapper();

  @Autowired private MockMvc mvc;
  @Autowired private OrgProjectionService orgProjectionService;

  @Test
  void aLinkedEmployeeSeesTheirOwnProfilePayslipsAndRealAmountsOnly() throws Exception {
    seedIllustrative();
    UUID outlet = seedOrgUnit("outlet");
    String mySub = UUID.randomUUID().toString();
    String otherSub = UUID.randomUUID().toString();

    // Me: employee with salary 4,000,000.00 IDR; a colleague with a different salary.
    UUID me = createEmployee("Rina Kasir", "3206000000000001", "6666777788880001");
    UUID colleague = createEmployee("Tono Gudang", "3206000000000002", "6666777788880002");
    UUID myContract = addContract(me);
    UUID colleagueContract = addContract(colleague);
    addAssignment(me, outlet, "cashier");
    addAssignment(colleague, outlet, "waiter");
    addCompensation(me, myContract, 400000000L);
    addCompensation(colleague, colleagueContract, 900000000L);
    linkLogin(me, mySub);
    linkLogin(colleague, otherSub);

    UUID runId = runPayroll(List.of(me, colleague));

    // Profile — own data, PII still masked even to myself.
    String profile =
        mvc.perform(
                get("/api/v1/me/profile").header("X-Company-Id", TENANT).header("X-Actor", mySub))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.employeeId").value(me.toString()))
            .andExpect(jsonPath("$.fullName").value("Rina Kasir"))
            .andExpect(jsonPath("$.maskedNik").value("***REDACTED***"))
            .andExpect(jsonPath("$.assignments.length()").value(1))
            .andExpect(jsonPath("$.assignments[0].role").value("cashier"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(profile).doesNotContain("3206000000000001").doesNotContain("6666777788880001");

    // Payslip index — only MY runs, headers only (no amounts on the list).
    String slips =
        mvc.perform(
                get("/api/v1/me/payslips")
                    .param("period", PERIOD)
                    .header("X-Company-Id", TENANT)
                    .header("X-Actor", mySub))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].runId").value(runId.toString()))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(slips).doesNotContain("amountMinor");

    // Payslip detail — REAL amounts for MY lines; the colleague's figures never appear.
    String detail =
        mvc.perform(
                get("/api/v1/me/payslips/" + runId)
                    .header("X-Company-Id", TENANT)
                    .header("X-Actor", mySub))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.runId").value(runId.toString()))
            .andReturn()
            .getResponse()
            .getContentAsString();
    JsonNode detailNode = json.readTree(detail);
    assertThat(detailNode.get("grossMinor").asLong()).isEqualTo(400000000L);
    assertThat(detailNode.get("netMinor").asLong()).isGreaterThan(0L);
    assertThat(detailNode.get("netMinor").asLong()).isLessThan(400000000L);
    boolean sawBase = false;
    for (JsonNode line : detailNode.get("lines")) {
      if ("BASE".equals(line.get("componentKey").asText())) {
        sawBase = true;
        assertThat(line.get("amountMinor").asLong()).isEqualTo(400000000L);
      }
    }
    assertThat(sawBase).isTrue();
    // The colleague's base (900000000) must never leak into my view.
    assertThat(detail).doesNotContain("900000000");
  }

  @Test
  void unlinkedLoginsForeignRunsAndForeignTenantsFailClosed() throws Exception {
    seedIllustrative();
    UUID outlet = seedOrgUnit("outlet");
    String linkedSub = UUID.randomUUID().toString();
    UUID me = createEmployee("Budi Santoso", "3206000000000003", "6666777788880003");
    UUID contract = addContract(me);
    addAssignment(me, outlet, "chef");
    addCompensation(me, contract, 300000000L);
    linkLogin(me, linkedSub);
    UUID runId = runPayroll(List.of(me));

    // An unlinked login → 404 not-linked problem on every /me read.
    String unlinkedSub = UUID.randomUUID().toString();
    mvc.perform(
            get("/api/v1/me/profile").header("X-Company-Id", TENANT).header("X-Actor", unlinkedSub))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.type").value("https://errors.nativeapp.id/employee-not-linked"));
    mvc.perform(
            get("/api/v1/me/payslips/" + runId)
                .header("X-Company-Id", TENANT)
                .header("X-Actor", unlinkedSub))
        .andExpect(status().isNotFound());

    // A run that exists but carries none of MY lines → 404 (anti-enumeration).
    UUID other = createEmployee("Siti Rahma", "3206000000000004", "6666777788880004");
    UUID otherContract = addContract(other);
    addCompensation(other, otherContract, 200000000L);
    String otherSub = UUID.randomUUID().toString();
    linkLogin(other, otherSub);
    mvc.perform(
            get("/api/v1/me/payslips/" + runId)
                .header("X-Company-Id", TENANT)
                .header("X-Actor", otherSub))
        .andExpect(status().isNotFound());

    // A foreign tenant with the same sub sees nothing (RLS fail-closed).
    mvc.perform(
            get("/api/v1/me/profile")
                .header("X-Company-Id", "22222222-2222-2222-2222-222222222222")
                .header("X-Actor", linkedSub))
        .andExpect(status().isNotFound());
  }

  // ---- helpers --------------------------------------------------------------------------------

  private void seedIllustrative() throws Exception {
    mvc.perform(
            post("/api/v1/payroll-setup/seed-illustrative")
                .header("X-Company-Id", TENANT)
                .header("X-Actor", HR_ACTOR)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("baseCurrency", "IDR"))))
        .andExpect(status().isOk());
  }

  private UUID createEmployee(String name, String nik, String bank) throws Exception {
    String body =
        json.writeValueAsString(
            Map.of("fullName", name, "ptkpStatus", "TK0", "nik", nik, "bankAccount", bank));
    String response =
        mvc.perform(
                post("/api/v1/employees")
                    .header("X-Company-Id", TENANT)
                    .header("X-Actor", HR_ACTOR)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return UUID.fromString(json.readTree(response).get("id").asText());
  }

  private UUID addContract(UUID employeeId) throws Exception {
    String response =
        mvc.perform(
                post("/api/v1/employees/" + employeeId + "/contracts")
                    .header("X-Company-Id", TENANT)
                    .header("X-Actor", HR_ACTOR)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        json.writeValueAsString(
                            Map.of(
                                "employmentType", "PERMANENT",
                                "legalEmployerId", LEGAL_EMPLOYER.toString(),
                                "effectiveFrom", "2026-01-01"))))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return UUID.fromString(json.readTree(response).get("id").asText());
  }

  private void addAssignment(UUID employeeId, UUID orgUnitId, String role) throws Exception {
    mvc.perform(
            post("/api/v1/employees/" + employeeId + "/assignments")
                .header("X-Company-Id", TENANT)
                .header("X-Actor", HR_ACTOR)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        Map.of(
                            "orgUnitId",
                            orgUnitId.toString(),
                            "role",
                            role,
                            "effectiveFrom",
                            "2026-01-01"))))
        .andExpect(status().isCreated());
  }

  private void addCompensation(UUID employeeId, UUID contractId, long basePayMinor)
      throws Exception {
    mvc.perform(
            post("/api/v1/employees/" + employeeId + "/compensation")
                .header("X-Company-Id", TENANT)
                .header("X-Actor", HR_ACTOR)
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

  private void linkLogin(UUID employeeId, String sub) throws Exception {
    mvc.perform(
            post("/api/v1/employees/" + employeeId + "/login-link")
                .header("X-Company-Id", TENANT)
                .header("X-Actor", HR_ACTOR)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("userId", sub))))
        .andExpect(status().isOk());
  }

  private UUID runPayroll(List<UUID> employeeIds) throws Exception {
    String response =
        mvc.perform(
                post("/api/v1/payroll-runs")
                    .header("X-Company-Id", TENANT)
                    .header("X-Actor", HR_ACTOR)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        json.writeValueAsString(
                            Map.of(
                                "period",
                                PERIOD,
                                "employeeIds",
                                employeeIds.stream().map(UUID::toString).toList(),
                                "baseCurrency",
                                "IDR"))))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return UUID.fromString(json.readTree(response).get("id").asText());
  }

  private UUID seedOrgUnit(String type) {
    UUID orgUnitId = UUID.randomUUID();
    orgProjectionService.apply(
        new OrgUnitProjectedEvent(
            UUID.randomUUID(), orgUnitId, TENANT, LEGAL_EMPLOYER, type, true));
    return orgUnitId;
  }
}
