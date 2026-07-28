package id.co.nativeapp.employee;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import id.co.nativeapp.employee.org.dto.OrgUnitProjectedEvent;
import id.co.nativeapp.employee.org.service.OrgProjectionService;
import id.co.nativeapp.employee.payroll.service.CompensationWriter;
import id.co.nativeapp.money.Money;
import id.co.nativeapp.tenant.TenantContext;
import java.util.Currency;
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
 * HTTP-boundary coverage for {@code GET /api/v1/employees} — the HR list the console's org-unit hub
 * renders. One row per (employee × CURRENT assignment in scope); the unscoped list LEFT-JOINs so
 * employees without a current assignment still appear (null assignment fields). Runs over real
 * RLS-enforcing PostgreSQL with the dev tenant filter, exactly as the gateway will.
 *
 * <p>PII (rule 6): the list is a read path over a projection that never selects NIK / bank account
 * / base pay — the raw body must not even contain those KEYS, let alone values.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "native.dev-tenant-filter.enabled=true")
class EmployeeListEndpointTest extends PostgresRlsTestBase {

  private static final String TENANT_A = "11111111-1111-1111-1111-111111111111";
  private static final String TENANT_B = "22222222-2222-2222-2222-222222222222";
  private static final String ACTOR = "hr-admin@example.co.id";
  private static final UUID LEGAL_EMPLOYER =
      UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa1");

  private final ObjectMapper json = new ObjectMapper();

  @Autowired private MockMvc mvc;
  @Autowired private OrgProjectionService orgProjectionService;
  @Autowired private CompensationWriter compensationWriter;

  @Test
  void listsOneRowPerCurrentAssignmentInScopeAndNeverLeaksPii() throws Exception {
    UUID bu = seedOrgUnit(TENANT_A, "business_unit");
    UUID outlet = seedOrgUnit(TENANT_A, "outlet");
    UUID otherOutlet = seedOrgUnit(TENANT_A, "outlet");

    UUID e1 = createEmployee(TENANT_A, "Alpha Chef", "3201000000000001", "1111222233330001");
    UUID e2 = createEmployee(TENANT_A, "Bravo Admin", "3201000000000002", "1111222233330002");
    UUID e3 = createEmployee(TENANT_A, "Charlie Floater", "3201000000000003", "1111222233330003");
    UUID e4 = createEmployee(TENANT_A, "Delta Past", "3201000000000004", "1111222233330004");
    createEmployee(TENANT_B, "Foreign Zed", "3201000000000005", "1111222233330005");

    addAssignment(TENANT_A, e1, outlet, "chef", "2026-01-01", null);
    addAssignment(TENANT_A, e2, bu, "admin", "2026-01-01", null);
    addAssignment(TENANT_A, e4, otherOutlet, "waiter", "2026-01-01", "2026-01-31"); // ended

    // BU + child outlet scope → the two currently-assigned-in-scope employees only.
    String scoped =
        mvc.perform(
                get("/api/v1/employees")
                    .param("orgUnitIds", bu + "," + outlet)
                    .header("X-Company-Id", TENANT_A)
                    .header("X-Actor", ACTOR))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(2))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(scoped).contains("Alpha Chef").contains("Bravo Admin");
    assertThat(scoped).doesNotContain("Charlie Floater").doesNotContain("Delta Past");
    assertThat(scoped).contains("\"role\":\"chef\"").contains(outlet.toString());
    assertThat(scoped).contains("\"hasCompensation\":false");
    // PII: neither the values nor the KEYS may appear on the list read path (rule 6).
    assertThat(scoped)
        .doesNotContain("3201000000000001")
        .doesNotContain("1111222233330001")
        .doesNotContain("nik")
        .doesNotContain("bankAccount")
        .doesNotContain("basePay");

    // Single-outlet scope → only the outlet's assignment row.
    mvc.perform(
            get("/api/v1/employees")
                .param("orgUnitIds", outlet.toString())
                .header("X-Company-Id", TENANT_A)
                .header("X-Actor", ACTOR))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].employeeId").value(e1.toString()))
        .andExpect(jsonPath("$[0].orgUnitId").value(outlet.toString()));

    // Unscoped → every tenant-A employee; the unassigned + past-assignment ones with null
    // assignment fields.
    String unscoped =
        mvc.perform(
                get("/api/v1/employees").header("X-Company-Id", TENANT_A).header("X-Actor", ACTOR))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(4))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(unscoped).contains("Charlie Floater").contains("Delta Past");
    assertThat(unscoped).doesNotContain("Foreign Zed");

    // Name search.
    mvc.perform(
            get("/api/v1/employees")
                .param("q", "alp")
                .header("X-Company-Id", TENANT_A)
                .header("X-Actor", ACTOR))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].fullName").value("Alpha Chef"));

    // Status filter.
    mvc.perform(
            patch("/api/v1/employees/" + e3)
                .header("X-Company-Id", TENANT_A)
                .header("X-Actor", ACTOR)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("status", "INACTIVE"))))
        .andExpect(status().isOk());
    mvc.perform(
            get("/api/v1/employees")
                .param("status", "INACTIVE")
                .header("X-Company-Id", TENANT_A)
                .header("X-Actor", ACTOR))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].employeeId").value(e3.toString()));

    // hasCompensation flips once a covering package exists — but no amount ever appears.
    UUID contractId = addContract(TENANT_A, e1);
    TenantContext.runAs(
        TENANT_A,
        ACTOR,
        () ->
            compensationWriter.createPackage(
                e1,
                contractId,
                Money.ofMinor(5_000_000_00L, Currency.getInstance("IDR")),
                java.time.LocalDate.of(2026, 1, 1),
                java.time.LocalDate.of(9999, 12, 31)));
    String withComp =
        mvc.perform(
                get("/api/v1/employees")
                    .param("orgUnitIds", outlet.toString())
                    .header("X-Company-Id", TENANT_A)
                    .header("X-Actor", ACTOR))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].hasCompensation").value(true))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(withComp).doesNotContain("500000000").doesNotContain("5000000");

    // RLS isolation: tenant B sees only its own employee.
    mvc.perform(get("/api/v1/employees").header("X-Company-Id", TENANT_B).header("X-Actor", ACTOR))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].fullName").value("Foreign Zed"));
  }

  @Test
  void rejectsAnUnknownStatusAndAnOversizedOrgUnitIdsList() throws Exception {
    mvc.perform(
            get("/api/v1/employees")
                .param("status", "FIRED")
                .header("X-Company-Id", TENANT_A)
                .header("X-Actor", ACTOR))
        .andExpect(status().isBadRequest());

    StringBuilder ids = new StringBuilder();
    for (int i = 0; i < 201; i++) {
      if (i > 0) {
        ids.append(',');
      }
      ids.append(UUID.randomUUID());
    }
    mvc.perform(
            get("/api/v1/employees")
                .param("orgUnitIds", ids.toString())
                .header("X-Company-Id", TENANT_A)
                .header("X-Actor", ACTOR))
        .andExpect(status().isBadRequest());
  }

  // ---- helpers --------------------------------------------------------------------------------

  private UUID createEmployee(String tenant, String name, String nik, String bank)
      throws Exception {
    String body =
        json.writeValueAsString(
            Map.of("fullName", name, "ptkpStatus", "TK0", "nik", nik, "bankAccount", bank));
    String response =
        mvc.perform(
                post("/api/v1/employees")
                    .header("X-Company-Id", tenant)
                    .header("X-Actor", ACTOR)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return UUID.fromString(json.readTree(response).get("id").asText());
  }

  private void addAssignment(
      String tenant, UUID employeeId, UUID orgUnitId, String role, String from, String to)
      throws Exception {
    var payload = new java.util.HashMap<String, String>();
    payload.put("orgUnitId", orgUnitId.toString());
    payload.put("role", role);
    payload.put("effectiveFrom", from);
    if (to != null) {
      payload.put("effectiveTo", to);
    }
    mvc.perform(
            post("/api/v1/employees/" + employeeId + "/assignments")
                .header("X-Company-Id", tenant)
                .header("X-Actor", ACTOR)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(payload)))
        .andExpect(status().isCreated());
  }

  private UUID addContract(String tenant, UUID employeeId) throws Exception {
    String body =
        json.writeValueAsString(
            Map.of(
                "employmentType", "PERMANENT",
                "legalEmployerId", LEGAL_EMPLOYER.toString(),
                "effectiveFrom", "2026-01-01"));
    String response =
        mvc.perform(
                post("/api/v1/employees/" + employeeId + "/contracts")
                    .header("X-Company-Id", tenant)
                    .header("X-Actor", ACTOR)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return UUID.fromString(json.readTree(response).get("id").asText());
  }

  private UUID seedOrgUnit(String tenant, String type) {
    UUID orgUnitId = UUID.randomUUID();
    orgProjectionService.apply(
        new OrgUnitProjectedEvent(
            UUID.randomUUID(), orgUnitId, tenant, LEGAL_EMPLOYER, type, true));
    return orgUnitId;
  }
}
