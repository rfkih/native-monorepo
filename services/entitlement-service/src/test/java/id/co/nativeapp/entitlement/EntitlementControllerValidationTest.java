package id.co.nativeapp.entitlement;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import id.co.nativeapp.entitlement.config.EntitlementNotFoundAdvice;
import id.co.nativeapp.entitlement.entitlement.EntitlementController;
import id.co.nativeapp.entitlement.entitlement.EntitlementNotFoundException;
import id.co.nativeapp.entitlement.entitlement.EntitlementService;
import id.co.nativeapp.entitlement.entitlement.UnknownModuleException;
import id.co.nativeapp.security.ApiExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Edge input + fault handling for the entitlement endpoints, proven as a web slice — no DB.
 *
 * <p>Pins the RFC 7807 {@link org.springframework.http.ProblemDetail} contract: a malformed module
 * key → {@code 400 validation-failed} (shared {@link ApiExceptionHandler}); an
 * unknown-but-well-formed module → {@code 400 invalid-argument} (an {@link UnknownModuleException}
 * extends {@code IllegalArgumentException}); and a revoke of a never-granted module → {@code 404
 * entitlement-not-found} (the service-specific {@link EntitlementNotFoundAdvice}).
 */
@WebMvcTest(EntitlementController.class)
@Import({ApiExceptionHandler.class, EntitlementNotFoundAdvice.class})
class EntitlementControllerValidationTest {

  private static final String PROBLEM_JSON = "application/problem+json";

  @Autowired private MockMvc mockMvc;

  @MockitoBean private EntitlementService entitlementService;

  @Test
  void aBlankModuleKeyIsRejectedWithAValidationProblemDetail() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/entitlements")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"moduleKey\":\"\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
        .andExpect(jsonPath("$.status").value(400))
        .andExpect(jsonPath("$.type").value("https://errors.nativeapp.id/validation-failed"))
        .andExpect(jsonPath("$.errors[0].field").value("moduleKey"));
  }

  @Test
  void aMalformedModuleKeyIsRejectedWithAValidationProblemDetail() throws Exception {
    // Uppercase + spaces violate the @Pattern (lowercase a-z, 0-9, '-').
    mockMvc
        .perform(
            post("/api/v1/entitlements")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"moduleKey\":\"Not A Module\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.type").value("https://errors.nativeapp.id/validation-failed"))
        .andExpect(jsonPath("$.errors[0].field").value("moduleKey"));
  }

  @Test
  void anUnknownModuleIsMappedToAnInvalidArgumentProblemDetail() throws Exception {
    when(entitlementService.grant("widget")).thenThrow(new UnknownModuleException("widget"));
    mockMvc
        .perform(
            post("/api/v1/entitlements")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"moduleKey\":\"widget\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
        .andExpect(jsonPath("$.type").value("https://errors.nativeapp.id/invalid-argument"));
  }

  @Test
  void revokingANeverGrantedModuleIsMappedToA404ProblemDetail() throws Exception {
    when(entitlementService.revoke("finance"))
        .thenThrow(new EntitlementNotFoundException("finance"));
    mockMvc
        .perform(delete("/api/v1/entitlements/finance"))
        .andExpect(status().isNotFound())
        .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
        .andExpect(jsonPath("$.status").value(404))
        .andExpect(jsonPath("$.type").value("https://errors.nativeapp.id/entitlement-not-found"));
  }
}
