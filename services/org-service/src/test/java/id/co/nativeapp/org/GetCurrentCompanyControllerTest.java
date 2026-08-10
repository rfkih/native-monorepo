package id.co.nativeapp.org;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import id.co.nativeapp.org.company.controller.CompanyController;
import id.co.nativeapp.org.company.dto.CompanyResponse;
import id.co.nativeapp.org.company.service.CompanyService;
import id.co.nativeapp.org.config.TenantAccessDeniedAdvice;
import id.co.nativeapp.security.ApiExceptionHandler;
import id.co.nativeapp.tenant.TenantContext;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Web-slice coverage for {@code GET /api/v1/companies/current} on {@link CompanyController}.
 *
 * <p>Proves three outcomes — no DB required:
 *
 * <ol>
 *   <li>A bound tenant with an existing company receives {@code 200} with a {@link CompanyResponse}
 *       body whose fields match the service stub.
 *   <li>A bound tenant for which no company exists receives {@code 404} ({@code
 *       application/problem+json}, {@code type = "not-found"}) via {@link
 *       TenantAccessDeniedAdvice}.
 *   <li>The endpoint never accepts a {@code companyId} from the client — the company id in the
 *       response body comes from the service (which reads the bound {@link TenantContext}), and no
 *       path or query parameter is accepted.
 * </ol>
 *
 * <p>The request is invoked inside a {@code TenantContext.callAs(...)} scope, standing in for the
 * request-edge tenant binding the gateway / {@code DevTenantFilter} performs in production.
 */
@WebMvcTest(CompanyController.class)
@Import({TenantAccessDeniedAdvice.class, ApiExceptionHandler.class})
class GetCurrentCompanyControllerTest {

  private static final String PROBLEM_JSON = "application/problem+json";
  private static final UUID COMPANY_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
  private static final UUID LEGAL_EMPLOYER_ID =
      UUID.fromString("11111111-1111-1111-1111-111111111111");
  private static final UUID FIRST_BUSINESS_ID =
      UUID.fromString("22222222-2222-2222-2222-222222222222");
  private static final String ACTOR = "owner@example.co.id";

  @Autowired private MockMvc mockMvc;

  @MockitoBean private CompanyService companyService;
  // The controller now also wires the membership orchestrator (ADR 0021) — mocked out here.
  @MockitoBean private id.co.nativeapp.org.user.service.CompanyMembershipService membershipService;

  @Test
  void withABoundTenantAndAnExistingCompanyReturns200WithCompanyBody() throws Exception {
    CompanyResponse stubResponse =
        new CompanyResponse(
            COMPANY_ID,
            "Acme Corp",
            "IDR",
            "id",
            LEGAL_EMPLOYER_ID,
            FIRST_BUSINESS_ID,
            "FULL",
            "acme07");
    when(companyService.getCurrentCompany()).thenReturn(stubResponse);

    TenantContext.callAs(
        COMPANY_ID.toString(),
        ACTOR,
        () ->
            mockMvc
                .perform(get("/api/v1/companies/current"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(COMPANY_ID.toString()))
                .andExpect(jsonPath("$.name").value("Acme Corp"))
                .andExpect(jsonPath("$.baseCurrency").value("IDR"))
                .andExpect(jsonPath("$.defaultLanguage").value("id"))
                .andExpect(jsonPath("$.legalEmployerId").value(LEGAL_EMPLOYER_ID.toString()))
                .andExpect(jsonPath("$.firstBusinessId").value(FIRST_BUSINESS_ID.toString()))
                .andExpect(jsonPath("$.planTier").value("FULL")));
  }

  @Test
  void withABoundTenantButNoCompanyReturns404AsProblemDetail() throws Exception {
    when(companyService.getCurrentCompany())
        .thenThrow(new NoSuchElementException("No company found for the current tenant"));

    TenantContext.callAs(
        COMPANY_ID.toString(),
        ACTOR,
        () ->
            mockMvc
                .perform(get("/api/v1/companies/current"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.type").value("https://errors.nativeapp.id/not-found"))
                .andExpect(jsonPath("$.title").value("Not Found"))
                .andExpect(jsonPath("$.detail").value("No company found for the current tenant")));
  }
}
