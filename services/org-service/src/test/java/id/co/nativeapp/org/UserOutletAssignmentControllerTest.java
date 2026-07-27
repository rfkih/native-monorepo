package id.co.nativeapp.org;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import id.co.nativeapp.org.user.controller.UserOutletAssignmentController;
import id.co.nativeapp.org.user.dto.UserOutletAssignmentResponse;
import id.co.nativeapp.org.user.service.InvalidOutletAssignmentException;
import id.co.nativeapp.org.user.service.UserNotFoundException;
import id.co.nativeapp.org.user.service.UserOutletAssignmentService;
import id.co.nativeapp.security.ApiExceptionHandler;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Web-slice coverage for the user ↔ outlet assignment endpoints:
 *
 * <ul>
 *   <li>{@code GET /api/v1/users/me/outlets} — 200 + caller's assignments; uses
 *       {@link UserOutletAssignmentService#listOutletsForMe()}.
 *   <li>{@code GET /api/v1/users/{userId}/outlets} — 200 + assignments; 404 on unknown user.
 *   <li>{@code PUT /api/v1/users/{userId}/outlets} — 200 + resulting set; 400 on invalid outlet;
 *       404 on unknown user.
 * </ul>
 *
 * <p>No DB. Service is mocked. Mirrors the {@link OutletControllerTest} style.
 */
@WebMvcTest(UserOutletAssignmentController.class)
@Import({ApiExceptionHandler.class, id.co.nativeapp.org.user.config.UserExceptionAdvice.class})
class UserOutletAssignmentControllerTest {

  private static final UUID OUTLET_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
  private static final UUID OUTLET_ID_2 = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
  private static final String USER_ID = "kc-sub-user-controller-test";

  @Autowired private MockMvc mockMvc;

  @MockitoBean private UserOutletAssignmentService service;

  // -----------------------------------------------------------------------
  // GET /me/outlets
  // -----------------------------------------------------------------------

  @Test
  void getMyOutletsReturns200WithAssignments() throws Exception {
    when(service.listOutletsForMe())
        .thenReturn(List.of(new UserOutletAssignmentResponse(OUTLET_ID, "Alpha Outlet")));

    mockMvc
        .perform(get("/api/v1/users/me/outlets"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].orgUnitId").value(OUTLET_ID.toString()))
        .andExpect(jsonPath("$[0].name").value("Alpha Outlet"));
  }

  @Test
  void getMyOutletsReturns200WithEmptyListWhenUnassigned() throws Exception {
    when(service.listOutletsForMe()).thenReturn(List.of());

    mockMvc
        .perform(get("/api/v1/users/me/outlets"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$").isEmpty());
  }

  // -----------------------------------------------------------------------
  // GET /users/{userId}/outlets
  // -----------------------------------------------------------------------

  @Test
  void getUserOutletsReturns200WithAssignments() throws Exception {
    when(service.listOutletsForUser(USER_ID))
        .thenReturn(
            List.of(
                new UserOutletAssignmentResponse(OUTLET_ID, "Alpha Outlet"),
                new UserOutletAssignmentResponse(OUTLET_ID_2, "Beta Outlet")));

    mockMvc
        .perform(get("/api/v1/users/{userId}/outlets", USER_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(2))
        .andExpect(jsonPath("$[0].orgUnitId").value(OUTLET_ID.toString()))
        .andExpect(jsonPath("$[1].orgUnitId").value(OUTLET_ID_2.toString()));
  }

  @Test
  void getUserOutletsReturns404WhenUserNotFound() throws Exception {
    when(service.listOutletsForUser(USER_ID)).thenThrow(new UserNotFoundException(USER_ID));

    mockMvc
        .perform(get("/api/v1/users/{userId}/outlets", USER_ID))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.type").value("https://errors.nativeapp.id/user-not-found"));
  }

  // -----------------------------------------------------------------------
  // PUT /users/{userId}/outlets
  // -----------------------------------------------------------------------

  @Test
  void putOutletsReturns200WithResultingList() throws Exception {
    when(service.replaceOutletsForUser(
            Mockito.eq(USER_ID), Mockito.anyList()))
        .thenReturn(
            List.of(
                new UserOutletAssignmentResponse(OUTLET_ID, "Alpha Outlet"),
                new UserOutletAssignmentResponse(OUTLET_ID_2, "Beta Outlet")));

    mockMvc
        .perform(
            put("/api/v1/users/{userId}/outlets", USER_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"orgUnitIds": ["%s", "%s"]}
                    """.formatted(OUTLET_ID, OUTLET_ID_2)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(2));
  }

  @Test
  void putOutletsWithEmptyListReturns200() throws Exception {
    when(service.replaceOutletsForUser(Mockito.eq(USER_ID), Mockito.anyList()))
        .thenReturn(List.of());

    mockMvc
        .perform(
            put("/api/v1/users/{userId}/outlets", USER_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"orgUnitIds\": []}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$").isEmpty());
  }

  @Test
  void putOutletsReturns400WhenNonOutletSubmitted() throws Exception {
    when(service.replaceOutletsForUser(Mockito.eq(USER_ID), Mockito.anyList()))
        .thenThrow(
            new InvalidOutletAssignmentException(OUTLET_ID, "org unit is not of type OUTLET"));

    mockMvc
        .perform(
            put("/api/v1/users/{userId}/outlets", USER_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"orgUnitIds": ["%s"]}
                    """.formatted(OUTLET_ID)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.type").value("https://errors.nativeapp.id/invalid-outlet-assignment"));
  }

  @Test
  void putOutletsReturns404WhenUserNotFound() throws Exception {
    when(service.replaceOutletsForUser(Mockito.eq(USER_ID), Mockito.anyList()))
        .thenThrow(new UserNotFoundException(USER_ID));

    mockMvc
        .perform(
            put("/api/v1/users/{userId}/outlets", USER_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"orgUnitIds": ["%s"]}
                    """.formatted(OUTLET_ID)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.type").value("https://errors.nativeapp.id/user-not-found"));
  }

  @Test
  void putOutletsWithNullBodyReturns400() throws Exception {
    mockMvc
        .perform(
            put("/api/v1/users/{userId}/outlets", USER_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isBadRequest());
  }
}
