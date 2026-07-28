package id.co.nativeapp.org;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import id.co.nativeapp.org.company.controller.OrgUnitController;
import id.co.nativeapp.org.company.dto.OrgUnitListResponse;
import id.co.nativeapp.org.company.service.OrgUnitService;
import id.co.nativeapp.security.ApiExceptionHandler;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Web-slice coverage for {@code GET /api/v1/org-units}: 200 with the org-unit list, and 200 with an
 * empty list when no units exist. No DB. Mirrors the StatementsControllerTest style.
 *
 * <p>The service now returns {@link OrgUnitListResponse} directly (projection-to-DTO mapping in the
 * service layer — CODE-STRUCTURE §3.3), so the mock returns the DTO directly.
 */
@WebMvcTest(OrgUnitController.class)
@Import(ApiExceptionHandler.class)
class OrgUnitListControllerTest {

  private static final UUID UNIT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
  private static final UUID PARENT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

  @Autowired private MockMvc mockMvc;

  @MockitoBean private OrgUnitService orgUnitService;

  @Test
  void listOrgUnitsReturns200WithFlatList() throws Exception {
    OrgUnitListResponse stub =
        new OrgUnitListResponse(UNIT_ID, "North Outlet", "OUTLET", null, PARENT_ID, true);

    when(orgUnitService.findAllForCurrentTenant()).thenReturn(List.of(stub));

    mockMvc
        .perform(get("/api/v1/org-units"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].id").value(UNIT_ID.toString()))
        .andExpect(jsonPath("$[0].name").value("North Outlet"))
        .andExpect(jsonPath("$[0].type").value("OUTLET"))
        .andExpect(jsonPath("$[0].parentId").value(PARENT_ID.toString()))
        .andExpect(jsonPath("$[0].active").value(true));
  }

  @Test
  void listOrgUnitsReturns200WithEmptyListWhenNoUnitsExist() throws Exception {
    when(orgUnitService.findAllForCurrentTenant()).thenReturn(List.of());

    mockMvc
        .perform(get("/api/v1/org-units"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$").isEmpty());
  }
}
