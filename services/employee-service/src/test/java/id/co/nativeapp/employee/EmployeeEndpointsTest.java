package id.co.nativeapp.employee;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import id.co.nativeapp.employee.org.dto.OrgUnitProjectedEvent;
import id.co.nativeapp.employee.org.service.OrgProjectionService;
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
 * End-to-end HTTP-boundary test over the full context and a real RLS PostgreSQL: create employee →
 * add contract → add assignment (enforcing the invariant) → GET with assignments. It drives the
 * stack through MockMvc with the {@code dev} tenant filter binding the tenant from {@code
 * X-Company-Id}/{@code X-Actor} headers (enabled by the test property), exactly as the gateway
 * will.
 *
 * <p>Proves the response masks PII (rule 6 — the body never carries the raw NIK / bank account) and
 * that a concurrent assignment under a different legal employer is rejected with a {@code 409}
 * ProblemDetail.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "native.dev-tenant-filter.enabled=true")
class EmployeeEndpointsTest extends PostgresRlsTestBase {

  private static final String TENANT_A = "11111111-1111-1111-1111-111111111111";
  private static final String ACTOR_A = "hr-admin-a@example.co.id";
  private static final UUID LEGAL_EMPLOYER_1 =
      UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa1");
  private static final UUID LEGAL_EMPLOYER_2 =
      UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa2");

  private final ObjectMapper json = new ObjectMapper();

  @Autowired private MockMvc mvc;
  @Autowired private OrgProjectionService orgProjectionService;

  @Test
  void createEmployeeAddContractAddAssignmentThenGetWithAssignmentsMaskingPii() throws Exception {
    // Create an employee — PII supplied in the body, masked in the response (rule 6).
    String createBody =
        json.writeValueAsString(
            Map.of(
                "fullName", "Budi Santoso",
                "ptkpStatus", "K1",
                "nik", "3201234567890123",
                "bankAccount", "1234567890123456"));
    String createResponse =
        mvc.perform(
                post("/api/v1/employees")
                    .header("X-Company-Id", TENANT_A)
                    .header("X-Actor", ACTOR_A)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(createBody))
            .andExpect(status().isCreated())
            .andExpect(header().exists("Location"))
            .andExpect(jsonPath("$.maskedNik").value("***REDACTED***"))
            .andExpect(jsonPath("$.maskedBankAccount").value("****3456"))
            // The raw PII must NOT appear in the response body.
            .andReturn()
            .getResponse()
            .getContentAsString();
    org.assertj.core.api.Assertions.assertThat(createResponse)
        .doesNotContain("3201234567890123")
        .doesNotContain("1234567890123456");
    UUID employeeId = UUID.fromString(json.readTree(createResponse).get("id").asText());

    // Add an employment contract.
    String contractBody =
        json.writeValueAsString(
            Map.of(
                "employmentType", "PERMANENT",
                "legalEmployerId", LEGAL_EMPLOYER_1.toString(),
                "effectiveFrom", "2026-06-01"));
    mvc.perform(
            post("/api/v1/employees/" + employeeId + "/contracts")
                .header("X-Company-Id", TENANT_A)
                .header("X-Actor", ACTOR_A)
                .contentType(MediaType.APPLICATION_JSON)
                .content(contractBody))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.employmentType").value("PERMANENT"));

    // Seed two org units under the same legal employer, and one under a different one.
    UUID orgUnitA = seedOrgUnit(LEGAL_EMPLOYER_1);
    UUID orgUnitB = seedOrgUnit(LEGAL_EMPLOYER_1);
    UUID orgUnitOther = seedOrgUnit(LEGAL_EMPLOYER_2);

    // Add a first assignment.
    addAssignment(employeeId, orgUnitA, "2026-06-01", "2026-12-31").andExpect(status().isCreated());
    // A concurrent assignment under the SAME legal employer is allowed.
    addAssignment(employeeId, orgUnitB, "2026-06-01", "2026-12-31").andExpect(status().isCreated());

    // A concurrent assignment under a DIFFERENT legal employer → 409 ProblemDetail.
    addAssignment(employeeId, orgUnitOther, "2026-06-01", "2026-12-31")
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.title").value("Conflicting legal employer"));

    // PATCH update (partial) — change the status; response stays masked.
    String updateBody = json.writeValueAsString(Map.of("status", "INACTIVE"));
    mvc.perform(
            patch("/api/v1/employees/" + employeeId)
                .header("X-Company-Id", TENANT_A)
                .header("X-Actor", ACTOR_A)
                .contentType(MediaType.APPLICATION_JSON)
                .content(updateBody))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("INACTIVE"))
        .andExpect(jsonPath("$.maskedNik").value("***REDACTED***"));

    // GET the employee with its assignments — 2 assignments, PII masked, no raw PII in the body.
    String getResponse =
        mvc.perform(
                get("/api/v1/employees/" + employeeId)
                    .header("X-Company-Id", TENANT_A)
                    .header("X-Actor", ACTOR_A))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.employee.maskedBankAccount").value("****3456"))
            .andExpect(jsonPath("$.assignments.length()").value(2))
            .andReturn()
            .getResponse()
            .getContentAsString();
    org.assertj.core.api.Assertions.assertThat(getResponse)
        .doesNotContain("3201234567890123")
        .doesNotContain("1234567890123456");
  }

  @Test
  void addingAnAssignmentForAnUnknownEmployeeReturns404() throws Exception {
    // An org unit exists, but the employee id is unknown to this tenant (never created).
    UUID orgUnit = seedOrgUnit(LEGAL_EMPLOYER_1);
    UUID unknownEmployeeId = UUID.randomUUID();

    addAssignment(unknownEmployeeId, orgUnit, "2026-06-01", "2026-12-31")
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.title").value("Employee not found"));
  }

  @Test
  void addingAnAssignmentWithAReportingToThatIsNotAnInTenantEmployeeReturns400() throws Exception {
    // Create a real employee, but reference a non-existent manager via reporting_to.
    String createBody =
        json.writeValueAsString(
            Map.of(
                "fullName", "Siti Rahma",
                "ptkpStatus", "TK0",
                "nik", "3209999999999999",
                "bankAccount", "9876543210987654"));
    String createResponse =
        mvc.perform(
                post("/api/v1/employees")
                    .header("X-Company-Id", TENANT_A)
                    .header("X-Actor", ACTOR_A)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(createBody))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    UUID employeeId = UUID.fromString(json.readTree(createResponse).get("id").asText());

    UUID orgUnit = seedOrgUnit(LEGAL_EMPLOYER_1);
    UUID unknownManager = UUID.randomUUID();

    String body =
        json.writeValueAsString(
            Map.of(
                "orgUnitId", orgUnit.toString(),
                "reportingTo", unknownManager.toString(),
                "role", "cashier",
                "effectiveFrom", "2026-06-01",
                "effectiveTo", "2026-12-31"));
    mvc.perform(
            post("/api/v1/employees/" + employeeId + "/assignments")
                .header("X-Company-Id", TENANT_A)
                .header("X-Actor", ACTOR_A)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isBadRequest());
  }

  private org.springframework.test.web.servlet.ResultActions addAssignment(
      UUID employeeId, UUID orgUnitId, String from, String to) throws Exception {
    String body =
        json.writeValueAsString(
            Map.of(
                "orgUnitId",
                orgUnitId.toString(),
                "role",
                "cashier",
                "effectiveFrom",
                from,
                "effectiveTo",
                to));
    return mvc.perform(
        post("/api/v1/employees/" + employeeId + "/assignments")
            .header("X-Company-Id", TENANT_A)
            .header("X-Actor", ACTOR_A)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body));
  }

  private UUID seedOrgUnit(UUID legalEmployerId) {
    UUID orgUnitId = UUID.randomUUID();
    orgProjectionService.apply(
        new OrgUnitProjectedEvent(
            UUID.randomUUID(), orgUnitId, TENANT_A, legalEmployerId, "OUTLET", true));
    return orgUnitId;
  }
}
