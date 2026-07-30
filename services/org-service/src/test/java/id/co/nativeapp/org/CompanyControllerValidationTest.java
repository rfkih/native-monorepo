package id.co.nativeapp.org;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import id.co.nativeapp.org.company.controller.CompanyController;
import id.co.nativeapp.org.company.service.CompanyService;
import id.co.nativeapp.security.ApiExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Edge input handling for {@code POST /api/v1/companies}, proven as a web slice — no DB.
 *
 * <p>Bean-validation failures on the {@code @Valid} body and a domain {@link
 * IllegalArgumentException} (e.g. an unknown ISO-4217 base currency) are mapped to an RFC 7807
 * {@link org.springframework.http.ProblemDetail} {@code 400} ({@code application/problem+json}) by
 * {@link ApiExceptionHandler}, while a valid request still reaches the service. The assertions pin
 * the ProblemDetail shape: {@code status}, content-type, the stable {@code type} URI, {@code
 * title}, and the machine-readable {@code errors[]} of {@code {field, message}}.
 */
@WebMvcTest(CompanyController.class)
@Import(ApiExceptionHandler.class)
class CompanyControllerValidationTest {

  private static final String PROBLEM_JSON = "application/problem+json";

  @Autowired private MockMvc mockMvc;

  @MockitoBean private CompanyService companyService;
  // The controller now also wires the membership orchestrator (ADR 0021) — mocked out here.
  @MockitoBean private id.co.nativeapp.org.user.service.CompanyMembershipService membershipService;

  @Test
  void blankBaseCurrencyIsRejectedWithAProblemDetail() throws Exception {
    String body =
        """
            {"name":"Acme","baseCurrency":"","defaultLanguage":"id",
             "firstBusiness":{"name":"Outlet 1","vertical":"restaurant"}}
            """;
    mockMvc
        .perform(post("/api/v1/companies").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
        .andExpect(jsonPath("$.status").value(400))
        .andExpect(jsonPath("$.title").value("Validation failed"))
        .andExpect(jsonPath("$.type").value("https://errors.nativeapp.id/validation-failed"))
        .andExpect(jsonPath("$.errors").isArray())
        .andExpect(jsonPath("$.errors[0].field").value("baseCurrency"))
        .andExpect(jsonPath("$.errors[0].message").exists());
  }

  @Test
  void missingFirstBusinessIsRejectedWithAProblemDetail() throws Exception {
    String body =
        """
            {"name":"Acme","baseCurrency":"IDR","defaultLanguage":"id"}
            """;
    mockMvc
        .perform(post("/api/v1/companies").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
        .andExpect(jsonPath("$.type").value("https://errors.nativeapp.id/validation-failed"))
        .andExpect(jsonPath("$.errors[0].field").value("firstBusiness"));
  }

  @Test
  void malformedCountryIsRejectedWithAProblemDetail() throws Exception {
    // country is OPTIONAL on this in-app path (ADR 0025) — but when present it must be a bare
    // ISO 3166-1 alpha-2 code, not a country name.
    String body =
        """
            {"name":"Acme","baseCurrency":"IDR","defaultLanguage":"id","country":"Indonesia",
             "firstBusiness":{"name":"Outlet 1","vertical":"restaurant"}}
            """;
    mockMvc
        .perform(post("/api/v1/companies").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
        .andExpect(jsonPath("$.type").value("https://errors.nativeapp.id/validation-failed"))
        .andExpect(jsonPath("$.errors[0].field").value("country"));
  }

  @Test
  void unknownBaseCurrencyCodeIsMappedToAProblemDetail() throws Exception {
    // Passes bean validation (non-blank), but the Company aggregate rejects "ZZZ"
    // with an IllegalArgumentException that the advice maps to a 400 ProblemDetail.
    when(companyService.createCompany(any()))
        .thenThrow(new IllegalArgumentException("No currency for code ZZZ"));
    String body =
        """
            {"name":"Acme","baseCurrency":"ZZZ","defaultLanguage":"id",
             "firstBusiness":{"name":"Outlet 1","vertical":"restaurant"}}
            """;
    mockMvc
        .perform(post("/api/v1/companies").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
        .andExpect(jsonPath("$.type").value("https://errors.nativeapp.id/invalid-argument"))
        .andExpect(jsonPath("$.detail").value("No currency for code ZZZ"));
  }
}
