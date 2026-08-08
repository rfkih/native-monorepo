package id.co.nativeapp.employee;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import id.co.nativeapp.employee.org.dto.OrgUnitProjectedEvent;
import id.co.nativeapp.employee.org.service.OrgProjectionService;
import java.util.ArrayList;
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
 * HTTP-boundary coverage for {@code GET /api/v1/operators/roster?businessId=} (ADR 0049 P3b) — the
 * Business-app till's employee-pick PIN picker: assigned-to-this-outlet AND has-a-PIN, sorted by
 * name, and nothing beyond {@code {employeeId, displayName}} (rule 6).
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "native.dev-tenant-filter.enabled=true")
class OperatorRosterEndpointTest extends PostgresRlsTestBase {

  private static final String TENANT_A = "11111111-1111-1111-1111-111111111111";
  private static final String TENANT_B = "22222222-2222-2222-2222-222222222222";
  private static final String ACTOR = "hr-admin@example.co.id";

  private final ObjectMapper json = new ObjectMapper();

  @Autowired private MockMvc mvc;
  @Autowired private OrgProjectionService orgProjectionService;

  @Test
  void returnsAssignedEmployeesWithAPinSortedByNameAndOnlyTheTwoSafeFields() throws Exception {
    UUID outletId = seedOutlet();

    UUID zed = createEmployee("Zed Prakoso", "3202000000009101", "2222333344449101");
    addAssignment(zed, outletId, "cashier");
    setPin(zed, "1111");

    UUID budi = createEmployee("Budi Santoso", "3202000000009102", "2222333344449102");
    addAssignment(budi, outletId, "shift_lead");
    setPin(budi, "2222");

    String response =
        mvc.perform(
                get("/api/v1/operators/roster")
                    .param("businessId", outletId.toString())
                    .header("X-Company-Id", TENANT_A)
                    .header("X-Actor", ACTOR))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(2))
            // Sorted by displayName: "Budi Santoso" before "Zed Prakoso".
            .andExpect(jsonPath("$[0].displayName").value("Budi Santoso"))
            .andExpect(jsonPath("$[0].employeeId").value(budi.toString()))
            .andExpect(jsonPath("$[1].displayName").value("Zed Prakoso"))
            .andExpect(jsonPath("$[1].employeeId").value(zed.toString()))
            .andReturn()
            .getResponse()
            .getContentAsString();

    // Confirm ONLY {employeeId, displayName} — no role/status/PII field ever leaks onto this path.
    JsonNode rows = json.readTree(response);
    for (JsonNode row : rows) {
      List<String> fieldNames = new ArrayList<>();
      row.fieldNames().forEachRemaining(fieldNames::add);
      assertThat(fieldNames).containsExactlyInAnyOrder("employeeId", "displayName");
    }
  }

  @Test
  void excludesAnEmployeeAssignedButWithNoPinSet() throws Exception {
    UUID outletId = seedOutlet();

    UUID withPin = createEmployee("Siti Rahma", "3202000000009103", "2222333344449103");
    addAssignment(withPin, outletId, "cashier");
    setPin(withPin, "3333");

    UUID withoutPin = createEmployee("Andi Wijaya", "3202000000009104", "2222333344449104");
    addAssignment(withoutPin, outletId, "cashier");
    // Deliberately no PIN set for withoutPin.

    String response =
        mvc.perform(
                get("/api/v1/operators/roster")
                    .param("businessId", outletId.toString())
                    .header("X-Company-Id", TENANT_A)
                    .header("X-Actor", ACTOR))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].employeeId").value(withPin.toString()))
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(response).doesNotContain(withoutPin.toString());
  }

  @Test
  void excludesAnEmployeeWithAPinButNotAssignedToThisOutlet() throws Exception {
    UUID outletId = seedOutlet();
    UUID otherOutletId = seedOutlet();

    UUID assignedHere = createEmployee("Dewi Lestari", "3202000000009105", "2222333344449105");
    addAssignment(assignedHere, outletId, "cashier");
    setPin(assignedHere, "4444");

    UUID assignedElsewhere = createEmployee("Rudi Hartono", "3202000000009106", "2222333344449106");
    addAssignment(assignedElsewhere, otherOutletId, "cashier"); // NOT outletId
    setPin(assignedElsewhere, "5555");

    String response =
        mvc.perform(
                get("/api/v1/operators/roster")
                    .param("businessId", outletId.toString())
                    .header("X-Company-Id", TENANT_A)
                    .header("X-Actor", ACTOR))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].employeeId").value(assignedHere.toString()))
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(response).doesNotContain(assignedElsewhere.toString());
  }

  @Test
  void aDifferentOutletReturnsItsOwnRoster() throws Exception {
    UUID outletA = seedOutlet();
    UUID outletB = seedOutlet();

    UUID atA = createEmployee("Fitri Handayani", "3202000000009107", "2222333344449107");
    addAssignment(atA, outletA, "cashier");
    setPin(atA, "6666");

    UUID atB = createEmployee("Joko Susanto", "3202000000009108", "2222333344449108");
    addAssignment(atB, outletB, "cashier");
    setPin(atB, "7777");

    mvc.perform(
            get("/api/v1/operators/roster")
                .param("businessId", outletA.toString())
                .header("X-Company-Id", TENANT_A)
                .header("X-Actor", ACTOR))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].employeeId").value(atA.toString()));

    mvc.perform(
            get("/api/v1/operators/roster")
                .param("businessId", outletB.toString())
                .header("X-Company-Id", TENANT_A)
                .header("X-Actor", ACTOR))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].employeeId").value(atB.toString()));
  }

  @Test
  void aCrossTenantCallerSeesAnEmptyRosterUnderRls() throws Exception {
    UUID outletId = seedOutlet();
    UUID employeeId = createEmployee("Bagus Prakoso", "3202000000009109", "2222333344449109");
    addAssignment(employeeId, outletId, "cashier");
    setPin(employeeId, "8888");

    mvc.perform(
            get("/api/v1/operators/roster")
                .param("businessId", outletId.toString())
                .header("X-Company-Id", TENANT_B)
                .header("X-Actor", ACTOR))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0));
  }

  @Test
  void aMissingBusinessIdIs400() throws Exception {
    mvc.perform(
            get("/api/v1/operators/roster")
                .header("X-Company-Id", TENANT_A)
                .header("X-Actor", ACTOR))
        .andExpect(status().isBadRequest());

    // A blank businessId is likewise a validation 400, not a 500.
    mvc.perform(
            get("/api/v1/operators/roster")
                .param("businessId", "")
                .header("X-Company-Id", TENANT_A)
                .header("X-Actor", ACTOR))
        .andExpect(status().isBadRequest());
  }

  // ---- helpers --------------------------------------------------------------------------------

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

  private void addAssignment(UUID employeeId, UUID outletId, String role) throws Exception {
    mvc.perform(
            post("/api/v1/employees/" + employeeId + "/assignments")
                .header("X-Company-Id", TENANT_A)
                .header("X-Actor", ACTOR)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        Map.of(
                            "orgUnitId",
                            outletId.toString(),
                            "role",
                            role,
                            "effectiveFrom",
                            "2026-01-01"))))
        .andExpect(status().isCreated());
  }

  private void setPin(UUID employeeId, String pin) throws Exception {
    mvc.perform(
            put("/api/v1/employees/" + employeeId + "/operator-pin")
                .header("X-Company-Id", TENANT_A)
                .header("X-Actor", ACTOR)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("pin", pin))))
        .andExpect(status().isNoContent());
  }

  private UUID seedOutlet() {
    UUID orgUnitId = UUID.randomUUID();
    orgProjectionService.apply(
        new OrgUnitProjectedEvent(
            UUID.randomUUID(), orgUnitId, TENANT_A, UUID.randomUUID(), "OUTLET", true));
    return orgUnitId;
  }
}
