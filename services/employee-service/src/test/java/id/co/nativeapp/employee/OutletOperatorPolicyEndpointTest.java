package id.co.nativeapp.employee;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
 * HTTP-boundary coverage for the ADR 0049 per-outlet operator-PIN policy: {@code GET
 * /api/v1/operators/policy?businessId=} (POS_ROLES) and {@code PUT
 * /api/v1/employees/outlet-pin-policy/{businessId}} (owner/manager, DASHBOARD_ROLES) — the safe
 * default when no row exists, the upsert round-trip, and RLS tenant isolation.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "native.dev-tenant-filter.enabled=true")
class OutletOperatorPolicyEndpointTest extends PostgresRlsTestBase {

  private static final String TENANT_A = "11111111-1111-1111-1111-111111111111";
  private static final String TENANT_B = "22222222-2222-2222-2222-222222222222";
  private static final String ACTOR = "hr-admin@example.co.id";

  private final ObjectMapper json = new ObjectMapper();

  @Autowired private MockMvc mvc;
  @Autowired private OrgProjectionService orgProjectionService;

  @Test
  void anOutletWithNoPolicyRowDefaultsToRequirePinTrue() throws Exception {
    UUID outletId = seedOutlet();

    mvc.perform(
            get("/api/v1/operators/policy")
                .param("businessId", outletId.toString())
                .header("X-Company-Id", TENANT_A)
                .header("X-Actor", ACTOR))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.requirePin").value(true));
  }

  @Test
  void puttingFalseThenTrueUpsertsAndGetReflectsEachChange() throws Exception {
    UUID outletId = seedOutlet();

    setPolicy(outletId, false);
    mvc.perform(
            get("/api/v1/operators/policy")
                .param("businessId", outletId.toString())
                .header("X-Company-Id", TENANT_A)
                .header("X-Actor", ACTOR))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.requirePin").value(false));

    // A second PUT on the SAME outlet updates the existing row (upsert), not a duplicate.
    setPolicy(outletId, true);
    mvc.perform(
            get("/api/v1/operators/policy")
                .param("businessId", outletId.toString())
                .header("X-Company-Id", TENANT_A)
                .header("X-Actor", ACTOR))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.requirePin").value(true));
  }

  @Test
  void aMissingOrMalformedBusinessIdIs400() throws Exception {
    mvc.perform(
            get("/api/v1/operators/policy")
                .header("X-Company-Id", TENANT_A)
                .header("X-Actor", ACTOR))
        .andExpect(status().isBadRequest());

    mvc.perform(
            get("/api/v1/operators/policy")
                .param("businessId", "not-a-uuid")
                .header("X-Company-Id", TENANT_A)
                .header("X-Actor", ACTOR))
        .andExpect(status().isBadRequest());
  }

  @Test
  void tenantAsPolicyIsInvisibleAndUnsettableByTenantB() throws Exception {
    UUID outletId = seedOutlet();
    setPolicy(outletId, false);

    // Tenant B cannot see tenant A's policy — RLS makes the row invisible, so the GET falls back
    // to the safe default rather than leaking tenant A's setting.
    mvc.perform(
            get("/api/v1/operators/policy")
                .param("businessId", outletId.toString())
                .header("X-Company-Id", TENANT_B)
                .header("X-Actor", ACTOR))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.requirePin").value(true));

    // Tenant B "setting" the same businessId creates ITS OWN row (RLS-scoped insert) rather than
    // touching tenant A's — tenant A's policy must remain false afterward.
    mvc.perform(
            put("/api/v1/employees/outlet-pin-policy/" + outletId)
                .header("X-Company-Id", TENANT_B)
                .header("X-Actor", ACTOR)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("requirePin", true))))
        .andExpect(status().isNoContent());

    mvc.perform(
            get("/api/v1/operators/policy")
                .param("businessId", outletId.toString())
                .header("X-Company-Id", TENANT_A)
                .header("X-Actor", ACTOR))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.requirePin").value(false));
  }

  @Test
  void aMissingRequirePinBodyFieldIs400() throws Exception {
    UUID outletId = seedOutlet();

    mvc.perform(
            put("/api/v1/employees/outlet-pin-policy/" + outletId)
                .header("X-Company-Id", TENANT_A)
                .header("X-Actor", ACTOR)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isBadRequest());
  }

  // ---- helpers --------------------------------------------------------------------------------

  private void setPolicy(UUID businessId, boolean requirePin) throws Exception {
    mvc.perform(
            put("/api/v1/employees/outlet-pin-policy/" + businessId)
                .header("X-Company-Id", TENANT_A)
                .header("X-Actor", ACTOR)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("requirePin", requirePin))))
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
