package id.co.nativeapp.employee;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import id.co.nativeapp.employee.org.dto.OrgUnitProjectedEvent;
import id.co.nativeapp.employee.org.service.OrgProjectionService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * HTTP-boundary coverage for {@code GET /api/v1/employees/org-units?ids=} — the local
 * org-read-model lookup the console uses to resolve an org unit's {@code legal_employer_id} before
 * creating an employment contract (rule 2: no synchronous call to org-service; this is the locally
 * cached read model).
 *
 * <p>Also pins the URL-space seam: the literal {@code /org-units} segment must win over the {@code
 * GET /api/v1/employees/{employeeId}} path variable (Spring's pattern ranking), so the lookup can
 * never be swallowed as a malformed employee id.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "native.dev-tenant-filter.enabled=true")
class OrgUnitLookupEndpointTest extends PostgresRlsTestBase {

  private static final String TENANT_A = "11111111-1111-1111-1111-111111111111";
  private static final String TENANT_B = "22222222-2222-2222-2222-222222222222";
  private static final String ACTOR = "hr-admin@example.co.id";
  private static final UUID LEGAL_EMPLOYER =
      UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa1");

  @Autowired private MockMvc mvc;
  @Autowired private OrgProjectionService orgProjectionService;

  @Test
  void returnsTheRequestedUnitsWithTheirLegalEmployerScopedByRls() throws Exception {
    UUID bu = seedOrgUnit(TENANT_A, "business_unit");
    UUID outlet = seedOrgUnit(TENANT_A, "outlet");
    UUID foreign = seedOrgUnit(TENANT_B, "outlet");

    // The literal path must not be swallowed by GET /{employeeId} (400 on a non-UUID id).
    String body =
        mvc.perform(
                get("/api/v1/employees/org-units")
                    .param("ids", bu + "," + outlet + "," + foreign)
                    .header("X-Company-Id", TENANT_A)
                    .header("X-Actor", ACTOR))
            .andExpect(status().isOk())
            // The foreign tenant's unit is invisible under RLS — silently absent, not an error.
            .andExpect(jsonPath("$.length()").value(2))
            .andReturn()
            .getResponse()
            .getContentAsString();
    org.assertj.core.api.Assertions.assertThat(body)
        .contains(bu.toString())
        .contains(outlet.toString())
        .doesNotContain(foreign.toString())
        .contains(LEGAL_EMPLOYER.toString())
        .contains("\"type\":\"outlet\"")
        .contains("\"type\":\"business_unit\"")
        .contains("\"active\":true");
  }

  @Test
  void rejectsAMissingOrOversizedIdsList() throws Exception {
    mvc.perform(
            get("/api/v1/employees/org-units")
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
            get("/api/v1/employees/org-units")
                .param("ids", ids.toString())
                .header("X-Company-Id", TENANT_A)
                .header("X-Actor", ACTOR))
        .andExpect(status().isBadRequest());
  }

  private UUID seedOrgUnit(String tenant, String type) {
    UUID orgUnitId = UUID.randomUUID();
    orgProjectionService.apply(
        new OrgUnitProjectedEvent(
            UUID.randomUUID(), orgUnitId, tenant, LEGAL_EMPLOYER, type, true));
    return orgUnitId;
  }
}
