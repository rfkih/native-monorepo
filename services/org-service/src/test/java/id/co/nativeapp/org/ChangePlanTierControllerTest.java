package id.co.nativeapp.org;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import id.co.nativeapp.org.company.config.PlanTierAdvice;
import id.co.nativeapp.org.company.controller.CompanyController;
import id.co.nativeapp.org.company.dto.CompanyResponse;
import id.co.nativeapp.org.company.service.CompanyService;
import id.co.nativeapp.org.company.service.InvalidPlanTierException;
import id.co.nativeapp.org.company.service.PlanTierForbiddenException;
import id.co.nativeapp.security.ApiExceptionHandler;
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
 * Web-slice (RFC-7807 contract) coverage for {@code PUT /api/v1/companies/current/plan-tier} on
 * {@link CompanyController} (ADR 0044) — proves the HTTP mapping {@link PlanTierAdvice} owns, with
 * {@link CompanyService} mocked (mirrors {@link GetCurrentCompanyControllerTest}'s style):
 *
 * <ul>
 *   <li>{@link InvalidPlanTierException} → {@code 422} {@code application/problem+json}, type
 *       {@code invalid-plan-tier};
 *   <li>{@link PlanTierForbiddenException} → {@code 403}, type {@code plan-tier-forbidden};
 *   <li>a successful flip → {@code 200} with the updated {@link CompanyResponse} body.
 * </ul>
 *
 * <p>The actual business logic (the real owner-only role check + whitelist validation against a
 * bound RLS tenant) is covered by {@link PlanTierAcceptanceTest} — this class only pins the
 * controller/advice wiring, no DB.
 */
@WebMvcTest(CompanyController.class)
@Import({PlanTierAdvice.class, ApiExceptionHandler.class})
class ChangePlanTierControllerTest {

  private static final String PROBLEM_JSON = "application/problem+json";
  private static final String PLAN_TIER_URL = "/api/v1/companies/current/plan-tier";
  private static final UUID COMPANY_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
  private static final UUID LEGAL_EMPLOYER_ID =
      UUID.fromString("11111111-1111-1111-1111-111111111111");
  private static final UUID FIRST_BUSINESS_ID =
      UUID.fromString("22222222-2222-2222-2222-222222222222");
  private static final String ACTOR = "owner@example.co.id";

  @Autowired private MockMvc mockMvc;

  @MockitoBean private CompanyService companyService;
  // The controller also wires the membership orchestrator (ADR 0021) — mocked out here, unused.
  @MockitoBean private id.co.nativeapp.org.user.service.CompanyMembershipService membershipService;

  @Test
  void aNonWhitelistedTierValueReturns422AsProblemDetail() throws Exception {
    when(companyService.changePlanTier("BOGUS")).thenThrow(new InvalidPlanTierException("BOGUS"));

    TenantContext.callAs(
        COMPANY_ID.toString(),
        ACTOR,
        () ->
            mockMvc
                .perform(
                    put(PLAN_TIER_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"planTier\":\"BOGUS\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(422))
                .andExpect(
                    jsonPath("$.type").value("https://errors.nativeapp.id/invalid-plan-tier")));
  }

  @Test
  void aNonOwnerCallerReturns403AsProblemDetail() throws Exception {
    when(companyService.changePlanTier("FREE")).thenThrow(new PlanTierForbiddenException());

    TenantContext.callAs(
        COMPANY_ID.toString(),
        ACTOR,
        () ->
            mockMvc
                .perform(
                    put(PLAN_TIER_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"planTier\":\"FREE\"}"))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(
                    jsonPath("$.type").value("https://errors.nativeapp.id/plan-tier-forbidden")));
  }

  @Test
  void anOwnerFlippingTheTierReturns200WithTheUpdatedCompany() throws Exception {
    CompanyResponse updated =
        new CompanyResponse(
            COMPANY_ID, "Acme Corp", "IDR", "id", LEGAL_EMPLOYER_ID, FIRST_BUSINESS_ID, "FREE");
    when(companyService.changePlanTier("FREE")).thenReturn(updated);

    TenantContext.callAs(
        COMPANY_ID.toString(),
        ACTOR,
        () ->
            mockMvc
                .perform(
                    put(PLAN_TIER_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"planTier\":\"FREE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(COMPANY_ID.toString()))
                .andExpect(jsonPath("$.planTier").value("FREE")));
  }

  @Test
  void aBlankPlanTierBodyFieldReturns400AsProblemDetail() throws Exception {
    TenantContext.callAs(
        COMPANY_ID.toString(),
        ACTOR,
        () ->
            mockMvc
                .perform(
                    put(PLAN_TIER_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"planTier\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(
                    jsonPath("$.type").value("https://errors.nativeapp.id/validation-failed")));
  }
}
