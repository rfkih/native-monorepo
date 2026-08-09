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
 * HTTP-boundary coverage for the ADR 0049 P1 operator PIN feature: {@code PUT
 * /api/v1/employees/{id}/operator-pin} (owner/manager set/reset) and {@code POST
 * /api/v1/operators/session} (PIN verify + mint). No vertical consumes the minted token yet (P2) —
 * this proves the mint side only: success, wrong PIN (uniform, no enumeration), lockout,
 * not-assigned, unlinked employee, and tenant isolation.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "native.dev-tenant-filter.enabled=true")
class OperatorEndpointTest extends PostgresRlsTestBase {

  private static final String TENANT_A = "11111111-1111-1111-1111-111111111111";
  private static final String TENANT_B = "22222222-2222-2222-2222-222222222222";
  private static final String ACTOR = "hr-admin@example.co.id";
  private static final byte[] TEST_SIGNING_KEY =
      java.util.Base64.getDecoder().decode("bmF0aXZlLW9wdG9rLXRlc3Qta2V5LTAxMjM0NTY3ODk=");

  private final ObjectMapper json = new ObjectMapper();

  @Autowired private MockMvc mvc;
  @Autowired private OrgProjectionService orgProjectionService;

  @Test
  void aCorrectPinMintsAVerifiableTokenCarryingTheOperatorsOwnLoginAndCurrentRole()
      throws Exception {
    UUID outletId = seedOutlet();
    String userSub = UUID.randomUUID().toString();
    UUID employeeId = createEmployee("Budi Santoso", "3202000000009001", "2222333344449001");
    linkLogin(employeeId, userSub);
    addAssignment(employeeId, outletId, "cashier");
    setPin(employeeId, "4321");

    String response =
        mvc.perform(
                post("/api/v1/operators/session")
                    .header("X-Company-Id", TENANT_A)
                    .header("X-Actor", ACTOR)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(sessionBody(outletId, employeeId, "4321")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.displayName").value("Budi Santoso"))
            .andExpect(jsonPath("$.role").value("cashier"))
            .andExpect(jsonPath("$.operatorSession").isNotEmpty())
            .andExpect(jsonPath("$.expiresAt").isNotEmpty())
            .andReturn()
            .getResponse()
            .getContentAsString();

    String token = json.readTree(response).get("operatorSession").asText();
    assertThat(OperatorTokenCodec.verifySignature(token, TEST_SIGNING_KEY)).isTrue();
    OperatorTokenPayload payload = OperatorTokenCodec.decodePayloadUnverified(token);
    assertThat(payload.companyId()).isEqualTo(TENANT_A);
    assertThat(payload.businessId()).isEqualTo(outletId);
    assertThat(payload.operatorUserId()).isEqualTo(userSub);
    assertThat(payload.operatorEmployeeId()).isEqualTo(employeeId);
    assertThat(payload.displayName()).isEqualTo("Budi Santoso");
    assertThat(payload.role()).isEqualTo("cashier");
    assertThat(payload.exp()).isGreaterThan(payload.iat());
  }

  @Test
  void aWrongPinIsRejectedUniformlyAndAnUnsetPinIsIndistinguishableFromAWrongOne()
      throws Exception {
    UUID outletId = seedOutlet();
    UUID employeeWithPin = createEmployee("Siti Rahma", "3202000000009002", "2222333344449002");
    linkLogin(employeeWithPin, UUID.randomUUID().toString());
    addAssignment(employeeWithPin, outletId, "cashier");
    setPin(employeeWithPin, "1111");

    UUID employeeWithoutPin = createEmployee("Andi Wijaya", "3202000000009003", "2222333344449003");
    linkLogin(employeeWithoutPin, UUID.randomUUID().toString());
    addAssignment(employeeWithoutPin, outletId, "cashier");
    // No PIN ever set for employeeWithoutPin.

    String wrongPinType =
        mvc.perform(
                post("/api/v1/operators/session")
                    .header("X-Company-Id", TENANT_A)
                    .header("X-Actor", ACTOR)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(sessionBody(outletId, employeeWithPin, "9999")))
            .andExpect(status().isUnauthorized())
            .andReturn()
            .getResponse()
            .getContentAsString();

    String noPinType =
        mvc.perform(
                post("/api/v1/operators/session")
                    .header("X-Company-Id", TENANT_A)
                    .header("X-Actor", ACTOR)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(sessionBody(outletId, employeeWithoutPin, "9999")))
            .andExpect(status().isUnauthorized())
            .andReturn()
            .getResponse()
            .getContentAsString();

    // Same problem `type` and `detail` text either way — no enumeration of who has a PIN set.
    assertThat(json.readTree(wrongPinType).get("type").asText())
        .isEqualTo(json.readTree(noPinType).get("type").asText());
    assertThat(json.readTree(wrongPinType).get("detail").asText())
        .isEqualTo(json.readTree(noPinType).get("detail").asText());
  }

  @Test
  void repeatedWrongPinsLockTheOperatorOutWith423() throws Exception {
    UUID outletId = seedOutlet();
    UUID employeeId = createEmployee("Dewi Lestari", "3202000000009004", "2222333344449004");
    linkLogin(employeeId, UUID.randomUUID().toString());
    addAssignment(employeeId, outletId, "cashier");
    setPin(employeeId, "5678");

    for (int i = 0; i < 5; i++) {
      mvc.perform(
              post("/api/v1/operators/session")
                  .header("X-Company-Id", TENANT_A)
                  .header("X-Actor", ACTOR)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(sessionBody(outletId, employeeId, "0000")))
          .andExpect(status().isUnauthorized());
    }

    // The 6th attempt — even with the CORRECT PIN — is locked out, not accepted.
    mvc.perform(
            post("/api/v1/operators/session")
                .header("X-Company-Id", TENANT_A)
                .header("X-Actor", ACTOR)
                .contentType(MediaType.APPLICATION_JSON)
                .content(sessionBody(outletId, employeeId, "5678")))
        .andExpect(status().isLocked());
  }

  @Test
  void anEmployeeNotAssignedToTheOutletIsRejectedWith403() throws Exception {
    UUID outletId = seedOutlet();
    UUID otherOutletId = seedOutlet();
    UUID employeeId = createEmployee("Rudi Hartono", "3202000000009005", "2222333344449005");
    linkLogin(employeeId, UUID.randomUUID().toString());
    addAssignment(employeeId, otherOutletId, "cashier"); // assigned elsewhere, NOT outletId
    setPin(employeeId, "2468");

    mvc.perform(
            post("/api/v1/operators/session")
                .header("X-Company-Id", TENANT_A)
                .header("X-Actor", ACTOR)
                .contentType(MediaType.APPLICATION_JSON)
                .content(sessionBody(outletId, employeeId, "2468")))
        .andExpect(status().isForbidden());
  }

  @Test
  void aWrongPinForANotAssignedEmployeeIs401NotForbiddenSoAssignmentStatusNeverLeaks()
      throws Exception {
    UUID outletId = seedOutlet();
    UUID otherOutletId = seedOutlet();
    UUID employeeId = createEmployee("Bagus Prakoso", "3202000000009008", "2222333344449008");
    linkLogin(employeeId, UUID.randomUUID().toString());
    addAssignment(employeeId, otherOutletId, "cashier"); // has a PIN, but NOT assigned to outletId
    setPin(employeeId, "1234");

    // A WRONG PIN must yield the uniform 401 — the 403 not-assigned answer is disclosed only to a
    // caller that already proved the correct PIN (security review M1: no enumeration of assignment
    // status or PIN existence before the PIN check).
    mvc.perform(
            post("/api/v1/operators/session")
                .header("X-Company-Id", TENANT_A)
                .header("X-Actor", ACTOR)
                .contentType(MediaType.APPLICATION_JSON)
                .content(sessionBody(outletId, employeeId, "9999")))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void aMissingPinAtAPinRequiredOutletIs401NotAValidation400() throws Exception {
    // ADR 0049 per-outlet policy: pin is now OPTIONAL at the DTO boundary (no @NotBlank), so a
    // missing pin at a PIN-required outlet (the default — no policy row) must be rejected via the
    // SAME uniform InvalidOperatorPinException 401 path as a wrong pin, not a distinct 400.
    UUID outletId = seedOutlet();
    UUID employeeId = createEmployee("Nita Prasetya", "3202000000009010", "2222333344449010");
    linkLogin(employeeId, UUID.randomUUID().toString());
    addAssignment(employeeId, outletId, "cashier");
    setPin(employeeId, "2580");

    String body =
        json.writeValueAsString(
            Map.of("businessId", outletId.toString(), "employeeId", employeeId.toString()));

    mvc.perform(
            post("/api/v1/operators/session")
                .header("X-Company-Id", TENANT_A)
                .header("X-Actor", ACTOR)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void anEmployeeWithNoLinkedLoginCannotBecomeAnOperator() throws Exception {
    UUID outletId = seedOutlet();
    UUID employeeId = createEmployee("Fitri Handayani", "3202000000009006", "2222333344449006");
    addAssignment(employeeId, outletId, "manager");
    setPin(employeeId, "1357");
    // Deliberately no login-link.

    mvc.perform(
            post("/api/v1/operators/session")
                .header("X-Company-Id", TENANT_A)
                .header("X-Actor", ACTOR)
                .contentType(MediaType.APPLICATION_JSON)
                .content(sessionBody(outletId, employeeId, "1357")))
        .andExpect(status().isConflict());
  }

  @Test
  void thePinIsScopedByTenantAndAMalformedPinIs400() throws Exception {
    UUID outletId = seedOutlet();
    UUID employeeId = createEmployee("Joko Susanto", "3202000000009007", "2222333344449007");
    linkLogin(employeeId, UUID.randomUUID().toString());
    addAssignment(employeeId, outletId, "cashier");
    setPin(employeeId, "9876");

    // A cross-tenant caller cannot even see the operator_pin row (RLS fail-closed) — same uniform
    // 401 as an unset PIN, not a 404/500 that would leak the row's existence.
    mvc.perform(
            post("/api/v1/operators/session")
                .header("X-Company-Id", TENANT_B)
                .header("X-Actor", ACTOR)
                .contentType(MediaType.APPLICATION_JSON)
                .content(sessionBody(outletId, employeeId, "9876")))
        .andExpect(status().isUnauthorized());

    // A non-numeric PIN on the set path is a validation 400.
    mvc.perform(
            put("/api/v1/employees/" + employeeId + "/operator-pin")
                .header("X-Company-Id", TENANT_A)
                .header("X-Actor", ACTOR)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("pin", "abcd"))))
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

  private void setPin(UUID employeeId, String pin) throws Exception {
    mvc.perform(
            put("/api/v1/employees/" + employeeId + "/operator-pin")
                .header("X-Company-Id", TENANT_A)
                .header("X-Actor", ACTOR)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("pin", pin))))
        .andExpect(status().isNoContent());
  }

  private String sessionBody(UUID businessId, UUID employeeId, String pin) throws Exception {
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
