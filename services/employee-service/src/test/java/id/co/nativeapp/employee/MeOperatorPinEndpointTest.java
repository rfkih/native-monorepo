package id.co.nativeapp.employee;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
 * HTTP-boundary coverage for {@code PUT /api/v1/me/operator-pin} (ADR 0049 P2) — the employee's own
 * self-service set-or-change of their operator PIN (also forgot-PIN, since no current PIN is
 * required). The caller is resolved STRICTLY from {@code X-Actor} (the JWT sub) via the employee
 * login-link — never a body/path id — so this can only ever touch the caller's OWN PIN.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "native.dev-tenant-filter.enabled=true")
class MeOperatorPinEndpointTest extends PostgresRlsTestBase {

  private static final String TENANT_A = "11111111-1111-1111-1111-111111111111";
  private static final String TENANT_B = "22222222-2222-2222-2222-222222222222";
  private static final String HR_ACTOR = "hr-admin@example.co.id";

  private final ObjectMapper json = new ObjectMapper();

  @Autowired private MockMvc mvc;
  @Autowired private OrgProjectionService orgProjectionService;

  @Test
  void theCallerSetsTheirOwnPinAndCanThenSignInWithIt() throws Exception {
    UUID outletId = seedOutlet();
    String mySub = UUID.randomUUID().toString();
    UUID me = createEmployee("Sarah Amelia", "3202000000009401", "2222333344449401");
    linkLogin(me, mySub);
    addAssignment(me, outletId, "cashier");

    mvc.perform(
            put("/api/v1/me/operator-pin")
                .header("X-Company-Id", TENANT_A)
                .header("X-Actor", mySub)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("newPin", "6543"))))
        .andExpect(status().isNoContent());

    mvc.perform(
            post("/api/v1/operators/session")
                .header("X-Company-Id", TENANT_A)
                .header("X-Actor", HR_ACTOR)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        Map.of(
                            "businessId", outletId.toString(),
                            "employeeId", me.toString(),
                            "pin", "6543"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.displayName").value("Sarah Amelia"));
  }

  @Test
  void aSecondCallChangesThePin() throws Exception {
    UUID outletId = seedOutlet();
    String mySub = UUID.randomUUID().toString();
    UUID me = createEmployee("Rangga Wibawa", "3202000000009402", "2222333344449402");
    linkLogin(me, mySub);
    addAssignment(me, outletId, "cashier");

    setMyPin(mySub, "1111");
    setMyPin(mySub, "2222"); // change (forgot-PIN path: no current PIN required)

    // The OLD pin no longer works.
    mvc.perform(
            post("/api/v1/operators/session")
                .header("X-Company-Id", TENANT_A)
                .header("X-Actor", HR_ACTOR)
                .contentType(MediaType.APPLICATION_JSON)
                .content(sessionBody(outletId, me, "1111")))
        .andExpect(status().isUnauthorized());

    // The NEW pin works.
    mvc.perform(
            post("/api/v1/operators/session")
                .header("X-Company-Id", TENANT_A)
                .header("X-Actor", HR_ACTOR)
                .contentType(MediaType.APPLICATION_JSON)
                .content(sessionBody(outletId, me, "2222")))
        .andExpect(status().isOk());
  }

  @Test
  void theCallerCanOnlyEverChangeTheirOwnPinNeverAColleagues() throws Exception {
    UUID outletId = seedOutlet();
    String mySub = UUID.randomUUID().toString();
    UUID me = createEmployee("Wulan Sari", "3202000000009403", "2222333344449403");
    linkLogin(me, mySub);
    addAssignment(me, outletId, "cashier");

    String colleagueSub = UUID.randomUUID().toString();
    UUID colleague = createEmployee("Fajar Nugraha", "3202000000009404", "2222333344449404");
    linkLogin(colleague, colleagueSub);
    addAssignment(colleague, outletId, "cashier");

    // The request body carries no employeeId at all — the caller can ONLY set their own pin,
    // resolved strictly from X-Actor.
    setMyPin(mySub, "3333");

    // My PIN mints for ME.
    mvc.perform(
            post("/api/v1/operators/session")
                .header("X-Company-Id", TENANT_A)
                .header("X-Actor", HR_ACTOR)
                .contentType(MediaType.APPLICATION_JSON)
                .content(sessionBody(outletId, me, "3333")))
        .andExpect(status().isOk());

    // The colleague was NOT touched — they still have no PIN at all (uniform 401, not 200).
    mvc.perform(
            post("/api/v1/operators/session")
                .header("X-Company-Id", TENANT_A)
                .header("X-Actor", HR_ACTOR)
                .contentType(MediaType.APPLICATION_JSON)
                .content(sessionBody(outletId, colleague, "3333")))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void anUnlinkedCallerGetsA404() throws Exception {
    String unlinkedSub = UUID.randomUUID().toString();
    mvc.perform(
            put("/api/v1/me/operator-pin")
                .header("X-Company-Id", TENANT_A)
                .header("X-Actor", unlinkedSub)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("newPin", "4444"))))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.type").value("https://errors.nativeapp.id/employee-not-linked"));
  }

  @Test
  void aForeignTenantWithTheSameSubIsIsolatedUnderRls() throws Exception {
    UUID outletId = seedOutlet();
    String mySub = UUID.randomUUID().toString();
    UUID me = createEmployee("Putri Anjani", "3202000000009405", "2222333344449405");
    linkLogin(me, mySub);
    addAssignment(me, outletId, "cashier");

    // Tenant B has never heard of this sub — RLS-scoped lookup finds nothing, so it 404s exactly
    // like a genuinely unlinked login (fail-closed, no cross-tenant leak).
    mvc.perform(
            put("/api/v1/me/operator-pin")
                .header("X-Company-Id", TENANT_B)
                .header("X-Actor", mySub)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("newPin", "5555"))))
        .andExpect(status().isNotFound());
  }

  @Test
  void aMalformedPinIs400() throws Exception {
    String mySub = UUID.randomUUID().toString();
    UUID me = createEmployee("Bagas Setiawan", "3202000000009406", "2222333344449406");
    linkLogin(me, mySub);

    mvc.perform(
            put("/api/v1/me/operator-pin")
                .header("X-Company-Id", TENANT_A)
                .header("X-Actor", mySub)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("newPin", "12"))))
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
                    .header("X-Actor", HR_ACTOR)
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
                .header("X-Actor", HR_ACTOR)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("userId", userSub))))
        .andExpect(status().isOk());
  }

  private void addAssignment(UUID employeeId, UUID outletId, String role) throws Exception {
    mvc.perform(
            post("/api/v1/employees/" + employeeId + "/assignments")
                .header("X-Company-Id", TENANT_A)
                .header("X-Actor", HR_ACTOR)
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

  private void setMyPin(String actorSub, String pin) throws Exception {
    mvc.perform(
            put("/api/v1/me/operator-pin")
                .header("X-Company-Id", TENANT_A)
                .header("X-Actor", actorSub)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("newPin", pin))))
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
