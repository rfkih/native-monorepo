package id.co.nativeapp.employee;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import id.co.nativeapp.employee.assignment.messaging.AssignmentChangedSchema;
import id.co.nativeapp.employee.org.dto.OrgUnitProjectedEvent;
import id.co.nativeapp.employee.org.service.OrgProjectionService;
import id.co.nativeapp.events.AvroSerde;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.avro.generic.GenericRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * HTTP-boundary coverage for {@code PATCH /api/v1/employees/{eid}/assignments/{aid}} — ending an
 * open assignment. The end is effective-dated (sets {@code effective_to}, never deletes), emits a
 * second {@code AssignmentChanged} for the same aggregate (consumers upsert by {@code
 * assignment_id}), frees the same-legal-employer overlap window, and fails closed: 409 for a
 * non-open assignment, 400 for an end before the start, 404 for another employee's (or another
 * tenant's — invisible under RLS) assignment.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "native.dev-tenant-filter.enabled=true")
class AssignmentEndEndpointTest extends PostgresRlsTestBase {

  private static final String TENANT_A = "11111111-1111-1111-1111-111111111111";
  private static final String ACTOR = "hr-admin@example.co.id";
  private static final UUID LEGAL_EMPLOYER_1 =
      UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa1");
  private static final UUID LEGAL_EMPLOYER_2 =
      UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa2");

  private final ObjectMapper json = new ObjectMapper();

  @Autowired private MockMvc mvc;
  @Autowired private OrgProjectionService orgProjectionService;
  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void endingAnOpenAssignmentSetsEffectiveToEmitsTheEventAndFreesTheOverlapWindow()
      throws Exception {
    UUID employeeId = createEmployee("Budi Santoso", "3202000000000001", "2222333344440001");
    UUID orgUnit1 = seedOrgUnit(LEGAL_EMPLOYER_1);
    UUID orgUnit2 = seedOrgUnit(LEGAL_EMPLOYER_2);

    UUID assignmentId = addOpenAssignment(employeeId, orgUnit1, "2026-01-01");

    // While the open assignment runs, a concurrent one under ANOTHER legal employer is a 409.
    mvc.perform(
            post("/api/v1/employees/" + employeeId + "/assignments")
                .header("X-Company-Id", TENANT_A)
                .header("X-Actor", ACTOR)
                .contentType(MediaType.APPLICATION_JSON)
                .content(assignmentBody(orgUnit2, "2026-08-01", null)))
        .andExpect(status().isConflict());

    // End the assignment.
    mvc.perform(
            patch("/api/v1/employees/" + employeeId + "/assignments/" + assignmentId)
                .header("X-Company-Id", TENANT_A)
                .header("X-Actor", ACTOR)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("endOn", "2026-07-31"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(assignmentId.toString()))
        .andExpect(jsonPath("$.effectiveTo").value("2026-07-31"));

    // TWO AssignmentChanged events for the aggregate (create + end); the newest carries the end.
    List<Map<String, Object>> rows =
        jdbcTemplate.queryForList(
            "SELECT payload FROM outbox WHERE event_type = 'AssignmentChanged'"
                + " AND aggregate_id = ? ORDER BY occurred_at, id",
            assignmentId.toString());
    assertThat(rows).hasSize(2);
    GenericRecord endEvent =
        AvroSerde.deserialize(
            (byte[]) rows.get(1).get("payload"), AssignmentChangedSchema.schema());
    assertThat(endEvent.get("effective_to"))
        .isEqualTo((int) LocalDate.of(2026, 7, 31).toEpochDay());
    assertThat(endEvent.get("assignment_id").toString()).isEqualTo(assignmentId.toString());

    // The overlap window is freed: the other-legal-employer assignment from Aug 1 now succeeds.
    mvc.perform(
            post("/api/v1/employees/" + employeeId + "/assignments")
                .header("X-Company-Id", TENANT_A)
                .header("X-Actor", ACTOR)
                .contentType(MediaType.APPLICATION_JSON)
                .content(assignmentBody(orgUnit2, "2026-08-01", null)))
        .andExpect(status().isCreated());

    // Ending an already-ended assignment → 409.
    mvc.perform(
            patch("/api/v1/employees/" + employeeId + "/assignments/" + assignmentId)
                .header("X-Company-Id", TENANT_A)
                .header("X-Actor", ACTOR)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("endOn", "2026-08-15"))))
        .andExpect(status().isConflict());
  }

  @Test
  void endingBeforeTheStartReturns400AndAForeignAssignmentReturns404() throws Exception {
    UUID employeeId = createEmployee("Siti Rahma", "3202000000000002", "2222333344440002");
    UUID otherEmployeeId = createEmployee("Andi Wijaya", "3202000000000003", "2222333344440003");
    UUID orgUnit = seedOrgUnit(LEGAL_EMPLOYER_1);
    UUID assignmentId = addOpenAssignment(employeeId, orgUnit, "2026-06-01");

    // endOn before effectiveFrom → 400.
    mvc.perform(
            patch("/api/v1/employees/" + employeeId + "/assignments/" + assignmentId)
                .header("X-Company-Id", TENANT_A)
                .header("X-Actor", ACTOR)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("endOn", "2026-05-31"))))
        .andExpect(status().isBadRequest());

    // The assignment exists but belongs to a DIFFERENT employee → 404 (path mismatch).
    mvc.perform(
            patch("/api/v1/employees/" + otherEmployeeId + "/assignments/" + assignmentId)
                .header("X-Company-Id", TENANT_A)
                .header("X-Actor", ACTOR)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("endOn", "2026-07-01"))))
        .andExpect(status().isNotFound());

    // A cross-tenant caller cannot see the assignment at all → 404 (RLS fail-closed).
    mvc.perform(
            patch("/api/v1/employees/" + employeeId + "/assignments/" + assignmentId)
                .header("X-Company-Id", "33333333-3333-3333-3333-333333333333")
                .header("X-Actor", ACTOR)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("endOn", "2026-07-01"))))
        .andExpect(status().isNotFound());
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

  private String assignmentBody(UUID orgUnitId, String from, String to) throws Exception {
    var payload = new java.util.HashMap<String, String>();
    payload.put("orgUnitId", orgUnitId.toString());
    payload.put("role", "cashier");
    payload.put("effectiveFrom", from);
    if (to != null) {
      payload.put("effectiveTo", to);
    }
    return json.writeValueAsString(payload);
  }

  private UUID addOpenAssignment(UUID employeeId, UUID orgUnitId, String from) throws Exception {
    String response =
        mvc.perform(
                post("/api/v1/employees/" + employeeId + "/assignments")
                    .header("X-Company-Id", TENANT_A)
                    .header("X-Actor", ACTOR)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(assignmentBody(orgUnitId, from, null)))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return UUID.fromString(json.readTree(response).get("id").asText());
  }

  private UUID seedOrgUnit(UUID legalEmployerId) {
    UUID orgUnitId = UUID.randomUUID();
    orgProjectionService.apply(
        new OrgUnitProjectedEvent(
            UUID.randomUUID(), orgUnitId, TENANT_A, legalEmployerId, "OUTLET", true));
    return orgUnitId;
  }
}
