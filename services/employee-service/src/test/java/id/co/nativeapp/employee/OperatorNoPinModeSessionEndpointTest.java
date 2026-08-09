package id.co.nativeapp.employee;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import id.co.nativeapp.employee.org.dto.OrgUnitProjectedEvent;
import id.co.nativeapp.employee.org.service.OrgProjectionService;
import id.co.nativeapp.security.OperatorTokenCodec;
import id.co.nativeapp.security.OperatorTokenPayload;
import java.util.HashMap;
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
 * HTTP-boundary coverage for {@code POST /api/v1/operators/session} at a NO-PIN outlet (ADR 0049
 * per-outlet operator-PIN policy, {@code outlet_operator_policy.require_pin = false}) — the
 * conditional-mint branch added to {@code OperatorSessionWriter}: no PIN load, no lockout, no
 * Argon2 verify, but the SAME assignment/link/role checks and a byte-identical token shape as the
 * PIN-required flow in {@link OperatorEndpointTest}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "native.dev-tenant-filter.enabled=true")
class OperatorNoPinModeSessionEndpointTest extends PostgresRlsTestBase {

  private static final String TENANT_A = "11111111-1111-1111-1111-111111111111";
  private static final String ACTOR = "hr-admin@example.co.id";
  private static final byte[] TEST_SIGNING_KEY =
      java.util.Base64.getDecoder().decode("bmF0aXZlLW9wdG9rLXRlc3Qta2V5LTAxMjM0NTY3ODk=");

  private final ObjectMapper json = new ObjectMapper();

  @Autowired private MockMvc mvc;
  @Autowired private OrgProjectionService orgProjectionService;

  @Test
  void anAssignedAndLinkedEmployeeMintsATokenWithNoPinAtAll() throws Exception {
    UUID outletId = seedOutlet();
    setRequirePin(outletId, false);
    String userSub = UUID.randomUUID().toString();
    UUID employeeId = createEmployee("Rina Marlina", "3202000000009201", "2222333344449201");
    linkLogin(employeeId, userSub);
    addAssignment(employeeId, outletId, "cashier");
    // Deliberately NO operator_pin row ever set for this employee.

    String response =
        mvc.perform(
                post("/api/v1/operators/session")
                    .header("X-Company-Id", TENANT_A)
                    .header("X-Actor", ACTOR)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(sessionBodyNoPin(outletId, employeeId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.displayName").value("Rina Marlina"))
            .andExpect(jsonPath("$.role").value("cashier"))
            .andExpect(jsonPath("$.operatorSession").isNotEmpty())
            .andReturn()
            .getResponse()
            .getContentAsString();

    String token = json.readTree(response).get("operatorSession").asText();
    assertThat(OperatorTokenCodec.verifySignature(token, TEST_SIGNING_KEY)).isTrue();
    OperatorTokenPayload payload = OperatorTokenCodec.decodePayloadUnverified(token);
    assertThat(payload.operatorUserId()).isEqualTo(userSub);
    assertThat(payload.operatorEmployeeId()).isEqualTo(employeeId);
    assertThat(payload.role()).isEqualTo("cashier");
  }

  @Test
  void aSuppliedPinIsIgnoredAtANoPinOutletAndStillMints() throws Exception {
    UUID outletId = seedOutlet();
    setRequirePin(outletId, false);
    UUID employeeId = createEmployee("Agus Setiawan", "3202000000009202", "2222333344449202");
    linkLogin(employeeId, UUID.randomUUID().toString());
    addAssignment(employeeId, outletId, "cashier");

    // A garbage / wrong-format pin value is supplied but must be completely ignored — still 200.
    mvc.perform(
            post("/api/v1/operators/session")
                .header("X-Company-Id", TENANT_A)
                .header("X-Actor", ACTOR)
                .contentType(MediaType.APPLICATION_JSON)
                .content(sessionBodyWithPin(outletId, employeeId, "not-a-real-pin")))
        .andExpect(status().isOk());
  }

  @Test
  void notAssignedIsStill403AtANoPinOutlet() throws Exception {
    UUID outletId = seedOutlet();
    UUID otherOutletId = seedOutlet();
    setRequirePin(outletId, false);
    UUID employeeId = createEmployee("Tono Wijaya", "3202000000009203", "2222333344449203");
    linkLogin(employeeId, UUID.randomUUID().toString());
    addAssignment(employeeId, otherOutletId, "cashier"); // assigned elsewhere, NOT outletId

    mvc.perform(
            post("/api/v1/operators/session")
                .header("X-Company-Id", TENANT_A)
                .header("X-Actor", ACTOR)
                .contentType(MediaType.APPLICATION_JSON)
                .content(sessionBodyNoPin(outletId, employeeId)))
        .andExpect(status().isForbidden());
  }

  @Test
  void assignedButUnlinkedIsStill409AtANoPinOutlet() throws Exception {
    UUID outletId = seedOutlet();
    setRequirePin(outletId, false);
    UUID employeeId = createEmployee("Wati Suryani", "3202000000009204", "2222333344449204");
    addAssignment(employeeId, outletId, "manager");
    // Deliberately no login-link.

    mvc.perform(
            post("/api/v1/operators/session")
                .header("X-Company-Id", TENANT_A)
                .header("X-Actor", ACTOR)
                .contentType(MediaType.APPLICATION_JSON)
                .content(sessionBodyNoPin(outletId, employeeId)))
        .andExpect(status().isConflict());
  }

  @Test
  void aMissingPinIsAcceptedWhenTheOutletDoesNotRequireOne() throws Exception {
    UUID outletId = seedOutlet();
    setRequirePin(outletId, false);
    UUID employeeId = createEmployee("Hendra Gunawan", "3202000000009205", "2222333344449205");
    linkLogin(employeeId, UUID.randomUUID().toString());
    addAssignment(employeeId, outletId, "cashier");

    // No "pin" field in the body at all.
    Map<String, String> body = new HashMap<>();
    body.put("businessId", outletId.toString());
    body.put("employeeId", employeeId.toString());
    mvc.perform(
            post("/api/v1/operators/session")
                .header("X-Company-Id", TENANT_A)
                .header("X-Actor", ACTOR)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)))
        .andExpect(status().isOk());
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

  private void linkLogin(UUID employeeId, String userSub) throws Exception {
    mvc.perform(
            post("/api/v1/employees/" + employeeId + "/login-link")
                .header("X-Company-Id", TENANT_A)
                .header("X-Actor", ACTOR)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("userId", userSub))))
        .andExpect(status().isOk());
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

  private void setRequirePin(UUID businessId, boolean requirePin) throws Exception {
    mvc.perform(
            put("/api/v1/employees/outlet-pin-policy/" + businessId)
                .header("X-Company-Id", TENANT_A)
                .header("X-Actor", ACTOR)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("requirePin", requirePin))))
        .andExpect(status().isNoContent());
  }

  private String sessionBodyNoPin(UUID businessId, UUID employeeId) throws Exception {
    return json.writeValueAsString(
        Map.of("businessId", businessId.toString(), "employeeId", employeeId.toString()));
  }

  private String sessionBodyWithPin(UUID businessId, UUID employeeId, String pin) throws Exception {
    return json.writeValueAsString(
        Map.of(
            "businessId", businessId.toString(),
            "employeeId", employeeId.toString(),
            "pin", pin));
  }

  private UUID seedOutlet() {
    UUID orgUnitId = UUID.randomUUID();
    orgProjectionService.apply(
        new OrgUnitProjectedEvent(
            UUID.randomUUID(), orgUnitId, TENANT_A, UUID.randomUUID(), "OUTLET", true));
    return orgUnitId;
  }
}
