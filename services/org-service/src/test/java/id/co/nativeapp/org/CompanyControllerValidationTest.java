package id.co.nativeapp.org;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import id.co.nativeapp.org.company.controller.CompanyController;
import id.co.nativeapp.org.company.domain.Company;
import id.co.nativeapp.org.company.domain.OrgUnit;
import id.co.nativeapp.org.company.dto.CreateCompanyCommand;
import id.co.nativeapp.org.company.dto.CreateCompanyResult;
import id.co.nativeapp.org.company.service.CompanyService;
import id.co.nativeapp.security.ApiExceptionHandler;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Edge input handling for {@code POST /api/v1/companies}, proven as a web slice — no DB.
 *
 * <p>Bean-validation failures on the {@code @Valid} body are mapped to an RFC 7807 {@link
 * org.springframework.http.ProblemDetail} {@code 400} ({@code application/problem+json}) by {@link
 * ApiExceptionHandler}, while a valid request still reaches the service. The assertions pin the
 * ProblemDetail shape: {@code status}, content-type, the stable {@code type} URI, {@code title},
 * and the machine-readable {@code errors[]} of {@code {field, message}}.
 *
 * <p><strong>Currency is derived from country, never chosen (ADR 0025).</strong> The controller
 * derives {@code baseCurrency} from {@code country} and re-derives it authoritatively, so any
 * {@code baseCurrency} in the body is ignored — the derivation tests below capture the command the
 * controller builds and assert the derived currency (ID → IDR, else USD), independent of the body.
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
            {"name":"Acme","defaultLanguage":"id","country":"Indonesia",
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
  void unknownCountryCodeIsMappedToAProblemDetail() throws Exception {
    // "ZZ" is well-formed (passes the @Pattern) but not a real ISO 3166-1 country, so the
    // controller's CountryDefaults.requireValidCountry throws before the service is touched; the
    // shared advice maps it to a 400 invalid-argument ProblemDetail (the same whitelist → 400 path
    // the old unknown-currency test covered, now moved to country as currency is derived).
    String body =
        """
            {"name":"Acme","defaultLanguage":"id","country":"ZZ",
             "firstBusiness":{"name":"Outlet 1","vertical":"restaurant"}}
            """;
    mockMvc
        .perform(post("/api/v1/companies").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
        .andExpect(jsonPath("$.type").value("https://errors.nativeapp.id/invalid-argument"))
        .andExpect(jsonPath("$.detail").value("unknown country code: ZZ"));
  }

  @Test
  void currencyIsDerivedFromCountryAndAnyBodyCurrencyIsIgnored() throws Exception {
    // country US with a bogus body currency "IDR" → the server derives USD and DISCARDS the body
    // value (an API caller cannot pick a currency, ADR 0025). The service is stubbed so the happy
    // path completes with a 201; we capture the command to prove the derivation.
    ArgumentCaptor<CreateCompanyCommand> captor = stubCreateAndCapture();
    String body =
        """
            {"name":"Acme","baseCurrency":"IDR","defaultLanguage":"en","country":"US",
             "firstBusiness":{"name":"Outlet 1","vertical":"restaurant"}}
            """;
    mockMvc
        .perform(post("/api/v1/companies").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isCreated());

    verify(companyService).createCompany(captor.capture());
    assertThat(captor.getValue().country()).isEqualTo("US");
    assertThat(captor.getValue().baseCurrency()).isEqualTo("USD");
  }

  @Test
  void omittedCountryDefaultsToIndonesiaAndDerivesIdrEvenWithNoBodyCurrency() throws Exception {
    // No country + no baseCurrency (an older client) is accepted now — currency is derived, not
    // required in the body: default country "ID" → IDR. No 400.
    ArgumentCaptor<CreateCompanyCommand> captor = stubCreateAndCapture();
    String body =
        """
            {"name":"Acme","defaultLanguage":"id",
             "firstBusiness":{"name":"Outlet 1","vertical":"restaurant"}}
            """;
    mockMvc
        .perform(post("/api/v1/companies").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isCreated());

    verify(companyService).createCompany(captor.capture());
    assertThat(captor.getValue().country()).isEqualTo("ID");
    assertThat(captor.getValue().baseCurrency()).isEqualTo("IDR");
  }

  @Test
  void defaultCountryStillIgnoresAnExplicitBodyCurrency() throws Exception {
    // Even on the default-country ("ID") path, a body baseCurrency is discarded: sending "USD"
    // with no country still derives IDR — proving the ignore-body rule is not limited to the
    // explicit-country case above.
    ArgumentCaptor<CreateCompanyCommand> captor = stubCreateAndCapture();
    String body =
        """
            {"name":"Acme","baseCurrency":"USD","defaultLanguage":"id",
             "firstBusiness":{"name":"Outlet 1","vertical":"restaurant"}}
            """;
    mockMvc
        .perform(post("/api/v1/companies").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isCreated());

    verify(companyService).createCompany(captor.capture());
    assertThat(captor.getValue().country()).isEqualTo("ID");
    assertThat(captor.getValue().baseCurrency()).isEqualTo("IDR");
  }

  /**
   * Stubs {@link CompanyService#createCompany} with a minimal non-null result (so the controller
   * can build its {@code 201} response) and returns a captor for the command it was called with.
   *
   * <p>These derivation tests exercise the non-JWT branch ({@code companyService.createCompany});
   * that is sufficient because the controller derives the currency BEFORE the JWT/non-JWT split, so
   * the {@code membershipService} path receives the identical derived command.
   */
  private ArgumentCaptor<CreateCompanyCommand> stubCreateAndCapture() {
    Company company = mock(Company.class);
    when(company.getId()).thenReturn(UUID.randomUUID());
    when(company.getLegalEmployerId()).thenReturn(UUID.randomUUID());
    OrgUnit firstBusiness = mock(OrgUnit.class);
    when(firstBusiness.getId()).thenReturn(UUID.randomUUID());
    when(companyService.createCompany(any()))
        .thenReturn(new CreateCompanyResult(company, firstBusiness));
    return ArgumentCaptor.forClass(CreateCompanyCommand.class);
  }
}
