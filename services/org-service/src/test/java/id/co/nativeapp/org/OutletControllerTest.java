package id.co.nativeapp.org;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import id.co.nativeapp.org.company.controller.OutletController;
import id.co.nativeapp.org.company.dto.OutletResponse;
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
 * Web-slice coverage for {@code GET /api/v1/outlets}: 200 with active OUTLET-type units ordered by
 * name; 200 with an empty list when no active outlets exist. No DB. Mirrors the
 * OrgUnitListControllerTest style.
 *
 * <p>The service returns {@link OutletResponse} directly (projection-to-DTO mapping in the service
 * layer — CODE-STRUCTURE §3.3), so the mock returns the DTO directly.
 */
@WebMvcTest(OutletController.class)
@Import(ApiExceptionHandler.class)
class OutletControllerTest {

  private static final UUID OUTLET_A_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
  private static final UUID OUTLET_B_ID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

  @Autowired private MockMvc mockMvc;

  @MockitoBean private OrgUnitService orgUnitService;

  @Test
  void listActiveOutletsReturns200WithActiveOutletsSortedByName() throws Exception {
    // Stub returns two outlets already sorted by name (the service/repo owns ordering).
    when(orgUnitService.listActiveOutlets())
        .thenReturn(
            List.of(
                new OutletResponse(OUTLET_A_ID, "Alpha Outlet", "restaurant"),
                new OutletResponse(OUTLET_B_ID, "Beta Outlet", "carwash")));

    mockMvc
        .perform(get("/api/v1/outlets"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(2))
        .andExpect(jsonPath("$[0].id").value(OUTLET_A_ID.toString()))
        .andExpect(jsonPath("$[0].name").value("Alpha Outlet"))
        .andExpect(jsonPath("$[0].vertical").value("restaurant"))
        .andExpect(jsonPath("$[1].id").value(OUTLET_B_ID.toString()))
        .andExpect(jsonPath("$[1].name").value("Beta Outlet"))
        .andExpect(jsonPath("$[1].vertical").value("carwash"));
  }

  @Test
  void listActiveOutletsReturns200WithEmptyListWhenNoActiveOutletsExist() throws Exception {
    when(orgUnitService.listActiveOutlets()).thenReturn(List.of());

    mockMvc
        .perform(get("/api/v1/outlets"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$").isEmpty());
  }

  @Test
  void listActiveOutletsDoesNotIncludeTypeOrParentIdFields() throws Exception {
    // The contract is {id, name, vertical} only — no type, parentId, or active in the response.
    when(orgUnitService.listActiveOutlets())
        .thenReturn(List.of(new OutletResponse(OUTLET_A_ID, "Alpha Outlet", "restaurant")));

    mockMvc
        .perform(get("/api/v1/outlets"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].type").doesNotExist())
        .andExpect(jsonPath("$[0].parentId").doesNotExist())
        .andExpect(jsonPath("$[0].active").doesNotExist());
  }

  @Test
  void listActiveOutletsSerializesNullVerticalAsNull() throws Exception {
    // A pre-V6 anomaly (parent BU without a vertical) must not break the picker — the field
    // serializes as JSON null and the console fails open to restaurant.
    when(orgUnitService.listActiveOutlets())
        .thenReturn(List.of(new OutletResponse(OUTLET_A_ID, "Alpha Outlet", null)));

    mockMvc
        .perform(get("/api/v1/outlets"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].vertical").value((Object) null));
  }
}
