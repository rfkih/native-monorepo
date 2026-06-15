package id.co.nativeapp.org;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import id.co.nativeapp.org.company.controller.CompanyController;
import id.co.nativeapp.org.company.domain.OrgUnit;
import id.co.nativeapp.org.company.domain.OrgUnitType;
import id.co.nativeapp.org.company.service.CompanyService;
import id.co.nativeapp.org.config.TenantAccessDeniedAdvice;
import id.co.nativeapp.tenant.TenantContext;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Confused-deputy guard for {@code POST /api/v1/companies/{companyId}/businesses} (security FIX 2),
 * proven as a web slice.
 *
 * <p>The downstream writer stamps {@code company_id} from the bound {@link TenantContext}, never
 * from the path, so a path {@code companyId} that differs from the authenticated tenant would be
 * silently ignored. The controller now rejects that mismatch with a {@code 403} up front; a path id
 * that matches the bound tenant still proceeds to the service.
 *
 * <p>The request is invoked inside a {@code TenantContext.callAs(...)} scope (MockMvc runs on the
 * calling thread, so the scoped tenant is visible to the controller) — standing in for the
 * request-edge tenant binding the gateway/{@code DevTenantFilter} performs in production.
 */
@WebMvcTest(CompanyController.class)
@Import(TenantAccessDeniedAdvice.class)
class CreateBusinessTenantGuardTest {

  private static final UUID BOUND_TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
  private static final UUID OTHER_TENANT = UUID.fromString("22222222-2222-2222-2222-222222222222");
  private static final String ACTOR = "owner-a@example.co.id";

  @Autowired private MockMvc mockMvc;

  @MockitoBean private CompanyService companyService;

  @Test
  void addBusinessWithAPathCompanyIdDifferentFromTheBoundTenantIsRejectedWith403()
      throws Exception {
    String body =
        """
        {"name":"Outlet 1","type":"outlet"}
        """;

    // Bound to BOUND_TENANT, but the path addresses OTHER_TENANT -> confused deputy -> 403.
    TenantContext.callAs(
        BOUND_TENANT.toString(),
        ACTOR,
        () ->
            mockMvc
                .perform(
                    post("/api/v1/companies/" + OTHER_TENANT + "/businesses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(
                    jsonPath("$.type").value("https://errors.nativeapp.id/tenant-mismatch")));
  }

  @Test
  void addBusinessWithAPathCompanyIdMatchingTheBoundTenantSucceeds() throws Exception {
    OrgUnit created =
        new OrgUnit(
            "Outlet 1",
            OrgUnitType.BUSINESS_UNIT,
            null,
            null,
            BOUND_TENANT,
            java.time.LocalDate.of(2026, 1, 1));
    created.setCompanyId(BOUND_TENANT.toString());
    when(companyService.addBusiness(any())).thenReturn(created);

    String body =
        """
        {"name":"Outlet 1","type":"outlet"}
        """;

    // Bound to BOUND_TENANT and the path addresses the SAME tenant -> proceeds -> 201.
    TenantContext.callAs(
        BOUND_TENANT.toString(),
        ACTOR,
        () ->
            mockMvc
                .perform(
                    post("/api/v1/companies/" + BOUND_TENANT + "/businesses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Outlet 1")));
  }
}
