package id.co.nativeapp.org;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import id.co.nativeapp.org.user.config.UserExceptionAdvice;
import id.co.nativeapp.org.user.controller.OrgUnitUsersController;
import id.co.nativeapp.org.user.dto.OrgUnitUserResponse;
import id.co.nativeapp.org.user.service.InvalidUnitUsersTargetException;
import id.co.nativeapp.org.user.service.OrgUnitNotFoundException;
import id.co.nativeapp.org.user.service.OrgUnitUsersReader;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Web-slice coverage for {@code GET /api/v1/org-units/{orgUnitId}/users}: 200 with rows (or an
 * empty list), 404 unknown-unit ProblemDetail, 400 team-target ProblemDetail. The {@link
 * OrgUnitUsersReader} is mocked — no DB.
 */
@WebMvcTest(OrgUnitUsersController.class)
@Import(UserExceptionAdvice.class)
class OrgUnitUsersControllerTest {

  private static final UUID UNIT = UUID.fromString("33333333-3333-3333-3333-333333333333");
  private static final UUID OUTLET = UUID.fromString("44444444-4444-4444-4444-444444444444");

  @Autowired private MockMvc mockMvc;

  @MockitoBean private OrgUnitUsersReader orgUnitUsersReader;

  @Test
  void returnsAssignmentRowsForAValidUnit() throws Exception {
    when(orgUnitUsersReader.usersForUnit(UNIT))
        .thenReturn(List.of(new OrgUnitUserResponse("kc-sub-1", OUTLET, "Main Outlet")));

    mockMvc
        .perform(get("/api/v1/org-units/" + UNIT + "/users"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].userId").value("kc-sub-1"))
        .andExpect(jsonPath("$[0].orgUnitId").value(OUTLET.toString()))
        .andExpect(jsonPath("$[0].outletName").value("Main Outlet"));
  }

  @Test
  void returnsAnEmptyListForAnUnassignedUnit() throws Exception {
    when(orgUnitUsersReader.usersForUnit(UNIT)).thenReturn(List.of());

    mockMvc
        .perform(get("/api/v1/org-units/" + UNIT + "/users"))
        .andExpect(status().isOk())
        .andExpect(content().json("[]"));
  }

  @Test
  void mapsAnUnknownUnitToA404ProblemDetail() throws Exception {
    when(orgUnitUsersReader.usersForUnit(UNIT)).thenThrow(new OrgUnitNotFoundException(UNIT));

    mockMvc
        .perform(get("/api/v1/org-units/" + UNIT + "/users"))
        .andExpect(status().isNotFound())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.type").value("https://errors.nativeapp.id/org-unit-not-found"));
  }

  @Test
  void mapsATeamTargetToA400ProblemDetail() throws Exception {
    when(orgUnitUsersReader.usersForUnit(UNIT))
        .thenThrow(new InvalidUnitUsersTargetException(UNIT));

    mockMvc
        .perform(get("/api/v1/org-units/" + UNIT + "/users"))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(
            jsonPath("$.type").value("https://errors.nativeapp.id/invalid-unit-users-target"));
  }
}
