package id.co.nativeapp.org;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import id.co.nativeapp.org.company.controller.OrgUnitController;
import id.co.nativeapp.org.company.domain.OrgUnit;
import id.co.nativeapp.org.company.domain.OrgUnitType;
import id.co.nativeapp.org.company.domain.Vertical;
import id.co.nativeapp.org.company.service.OrgUnitService;
import id.co.nativeapp.security.ApiExceptionHandler;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Web-slice coverage for {@code /api/v1/org-units} — status codes + the RFC-7807 ProblemDetail
 * shape, no DB. A create returns {@code 201} + {@code Location}; a patch returns {@code 200}; a
 * domain rejection ({@link IllegalArgumentException}, e.g. an unknown type or an empty patch) is
 * mapped to a {@code 400} {@code application/problem+json} by the shared {@link
 * ApiExceptionHandler}.
 */
@WebMvcTest(OrgUnitController.class)
@Import(ApiExceptionHandler.class)
class OrgUnitControllerTest {

  private static final String PROBLEM_JSON = "application/problem+json";
  private static final UUID UNIT = UUID.fromString("22222222-2222-2222-2222-222222222222");
  private static final UUID COMPANY = UUID.fromString("11111111-1111-1111-1111-111111111111");

  @Autowired private MockMvc mockMvc;

  @MockitoBean private OrgUnitService orgUnitService;

  private OrgUnit businessUnit() {
    OrgUnit unit =
        new OrgUnit(
            "North Unit",
            OrgUnitType.BUSINESS_UNIT,
            Vertical.RESTAURANT,
            null,
            null,
            COMPANY,
            LocalDate.of(2026, 1, 1));
    unit.setCompanyId(COMPANY.toString());
    return unit;
  }

  @Test
  void creatingAnOrgUnitReturns201WithALocationHeader() throws Exception {
    OrgUnit created = businessUnit();
    when(orgUnitService.create(any())).thenReturn(created);
    String body =
        """
        {"name":"North Unit","type":"business_unit"}
        """;
    mockMvc
        .perform(post("/api/v1/org-units").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isCreated())
        .andExpect(header().string("Location", "/api/v1/org-units/" + created.getId()))
        .andExpect(jsonPath("$.name").value("North Unit"))
        .andExpect(jsonPath("$.type").value("BUSINESS_UNIT"));
  }

  @Test
  void anIllegalHierarchyIsMappedToA400ProblemDetail() throws Exception {
    when(orgUnitService.create(any()))
        .thenThrow(new IllegalArgumentException("A TEAM cannot be a child of a BUSINESS_UNIT"));
    String body =
        """
        {"name":"Team","type":"team","parentId":"11111111-1111-1111-1111-111111111111"}
        """;
    mockMvc
        .perform(post("/api/v1/org-units").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
        .andExpect(jsonPath("$.type").value("https://errors.nativeapp.id/invalid-argument"))
        .andExpect(jsonPath("$.detail").value("A TEAM cannot be a child of a BUSINESS_UNIT"));
  }

  @Test
  void aBlankNameIsRejectedWithAValidationProblemDetail() throws Exception {
    String body =
        """
        {"name":"","type":"outlet"}
        """;
    mockMvc
        .perform(post("/api/v1/org-units").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
        .andExpect(jsonPath("$.type").value("https://errors.nativeapp.id/validation-failed"))
        .andExpect(jsonPath("$.errors[0].field").value("name"));
  }

  @Test
  void patchingANodeReturns200() throws Exception {
    when(orgUnitService.patch(any())).thenReturn(businessUnit());
    String body =
        """
        {"name":"Renamed"}
        """;
    mockMvc
        .perform(
            patch("/api/v1/org-units/" + UNIT)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").exists());
  }

  @Test
  void anEmptyPatchIsRejectedWith400() throws Exception {
    String body = "{}";
    mockMvc
        .perform(
            patch("/api/v1/org-units/" + UNIT)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
        .andExpect(jsonPath("$.type").value("https://errors.nativeapp.id/invalid-argument"));
  }
}
