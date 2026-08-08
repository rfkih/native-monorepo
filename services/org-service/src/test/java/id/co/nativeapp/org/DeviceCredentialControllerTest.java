package id.co.nativeapp.org;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import id.co.nativeapp.org.devicecredential.config.DeviceCredentialExceptionAdvice;
import id.co.nativeapp.org.devicecredential.controller.DeviceCredentialController;
import id.co.nativeapp.org.devicecredential.dto.DeviceCredentialResponse;
import id.co.nativeapp.org.devicecredential.service.DeviceCredentialAlreadyExistsException;
import id.co.nativeapp.org.devicecredential.service.DeviceCredentialNotFoundException;
import id.co.nativeapp.org.devicecredential.service.DeviceCredentialService;
import id.co.nativeapp.org.devicecredential.service.DeviceCredentialTargetNotOutletException;
import id.co.nativeapp.org.user.config.UserExceptionAdvice;
import id.co.nativeapp.org.user.service.OrgUnitNotFoundException;
import id.co.nativeapp.security.ApiExceptionHandler;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Web-slice coverage for the device-credential endpoints ({@code
 * /api/v1/org-units/{outletId}/device-credential}, ADR 0049 P3a):
 *
 * <ul>
 *   <li>{@code POST .../device-credential} — 201 + {username, password}; 400 non-outlet target; 404
 *       unknown/cross-tenant outlet; 409 already exists.
 *   <li>{@code POST .../device-credential/reset} — 200 + new password; 404 none exists.
 *   <li>{@code GET .../device-credential} — 200 + stored {username, password}; 404 none exists.
 *   <li>{@code DELETE .../device-credential} — 204; 404 none exists.
 * </ul>
 *
 * <p>No DB, no Keycloak. Service is mocked. Mirrors {@link OutletControllerTest}'s style.
 */
@WebMvcTest(DeviceCredentialController.class)
@Import({
  ApiExceptionHandler.class,
  DeviceCredentialExceptionAdvice.class,
  UserExceptionAdvice.class
})
class DeviceCredentialControllerTest {

  private static final UUID OUTLET_ID = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");

  @Autowired private MockMvc mockMvc;

  @MockitoBean private DeviceCredentialService service;

  // -----------------------------------------------------------------------
  // POST .../device-credential (create)
  // -----------------------------------------------------------------------

  @Test
  void createReturns201WithUsernameAndPasswordOnce() throws Exception {
    when(service.create(OUTLET_ID))
        .thenReturn(new DeviceCredentialResponse(OUTLET_ID, "till." + OUTLET_ID, "S3cretPass!"));

    mockMvc
        .perform(post("/api/v1/org-units/{outletId}/device-credential", OUTLET_ID))
        .andExpect(status().isCreated())
        .andExpect(
            header().string("Location", "/api/v1/org-units/" + OUTLET_ID + "/device-credential"))
        .andExpect(jsonPath("$.outletId").value(OUTLET_ID.toString()))
        .andExpect(jsonPath("$.username").value("till." + OUTLET_ID))
        .andExpect(jsonPath("$.password").value("S3cretPass!"));
  }

  @Test
  void createReturns400WhenTargetIsNotAnOutlet() throws Exception {
    when(service.create(OUTLET_ID))
        .thenThrow(new DeviceCredentialTargetNotOutletException(OUTLET_ID, "BUSINESS_UNIT"));

    mockMvc
        .perform(post("/api/v1/org-units/{outletId}/device-credential", OUTLET_ID))
        .andExpect(status().isBadRequest())
        .andExpect(
            jsonPath("$.type")
                .value("https://errors.nativeapp.id/device-credential-target-not-outlet"));
  }

  @Test
  void createReturns404WhenOutletUnknownOrCrossTenant() throws Exception {
    when(service.create(OUTLET_ID)).thenThrow(new OrgUnitNotFoundException(OUTLET_ID));

    mockMvc
        .perform(post("/api/v1/org-units/{outletId}/device-credential", OUTLET_ID))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.type").value("https://errors.nativeapp.id/org-unit-not-found"));
  }

  @Test
  void createReturns409WhenAlreadyExists() throws Exception {
    when(service.create(OUTLET_ID))
        .thenThrow(new DeviceCredentialAlreadyExistsException(OUTLET_ID));

    mockMvc
        .perform(post("/api/v1/org-units/{outletId}/device-credential", OUTLET_ID))
        .andExpect(status().isConflict())
        .andExpect(
            jsonPath("$.type")
                .value("https://errors.nativeapp.id/device-credential-already-exists"));
  }

  // -----------------------------------------------------------------------
  // POST .../device-credential/reset
  // -----------------------------------------------------------------------

  @Test
  void resetReturns200WithNewPassword() throws Exception {
    when(service.reset(OUTLET_ID))
        .thenReturn(new DeviceCredentialResponse(OUTLET_ID, "till." + OUTLET_ID, "N3wPassword!"));

    mockMvc
        .perform(post("/api/v1/org-units/{outletId}/device-credential/reset", OUTLET_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.password").value("N3wPassword!"));
  }

  @Test
  void resetReturns404WhenNoneExists() throws Exception {
    when(service.reset(OUTLET_ID)).thenThrow(new DeviceCredentialNotFoundException(OUTLET_ID));

    mockMvc
        .perform(post("/api/v1/org-units/{outletId}/device-credential/reset", OUTLET_ID))
        .andExpect(status().isNotFound())
        .andExpect(
            jsonPath("$.type").value("https://errors.nativeapp.id/device-credential-not-found"));
  }

  // -----------------------------------------------------------------------
  // GET .../device-credential (reveal)
  // -----------------------------------------------------------------------

  @Test
  void revealReturns200WithStoredUsernameAndPassword() throws Exception {
    when(service.reveal(OUTLET_ID))
        .thenReturn(new DeviceCredentialResponse(OUTLET_ID, "till." + OUTLET_ID, "St0redPass!"));

    mockMvc
        .perform(get("/api/v1/org-units/{outletId}/device-credential", OUTLET_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.username").value("till." + OUTLET_ID))
        .andExpect(jsonPath("$.password").value("St0redPass!"));
  }

  @Test
  void revealReturns404WhenNoneExists() throws Exception {
    when(service.reveal(OUTLET_ID)).thenThrow(new DeviceCredentialNotFoundException(OUTLET_ID));

    mockMvc
        .perform(get("/api/v1/org-units/{outletId}/device-credential", OUTLET_ID))
        .andExpect(status().isNotFound());
  }

  // -----------------------------------------------------------------------
  // DELETE .../device-credential
  // -----------------------------------------------------------------------

  @Test
  void deleteReturns204() throws Exception {
    mockMvc
        .perform(delete("/api/v1/org-units/{outletId}/device-credential", OUTLET_ID))
        .andExpect(status().isNoContent());
  }

  @Test
  void deleteReturns404WhenNoneExists() throws Exception {
    org.mockito.Mockito.doThrow(new DeviceCredentialNotFoundException(OUTLET_ID))
        .when(service)
        .delete(any());

    mockMvc
        .perform(delete("/api/v1/org-units/{outletId}/device-credential", OUTLET_ID))
        .andExpect(status().isNotFound());
  }
}
