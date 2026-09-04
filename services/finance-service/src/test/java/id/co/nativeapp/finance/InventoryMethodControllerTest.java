package id.co.nativeapp.finance;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import id.co.nativeapp.finance.config.ConstraintViolationAdvice;
import id.co.nativeapp.finance.inventory.controller.InventoryMethodAdvice;
import id.co.nativeapp.finance.inventory.controller.InventoryMethodController;
import id.co.nativeapp.finance.inventory.domain.InventoryMethodAlreadyActiveException;
import id.co.nativeapp.finance.inventory.domain.InventoryMethodConfig;
import id.co.nativeapp.finance.inventory.domain.InventoryMethodSealedPeriodException;
import id.co.nativeapp.finance.inventory.dto.ActivationResult;
import id.co.nativeapp.finance.inventory.dto.InventoryMethodStatusResponse;
import id.co.nativeapp.finance.inventory.service.InventoryMethodActivationWriter;
import id.co.nativeapp.finance.inventory.service.InventoryMethodStatusReader;
import id.co.nativeapp.security.ApiExceptionHandler;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Web-slice test for {@link InventoryMethodController}: the HTTP contract of ADR 0067 Phase D4/D5's
 * activation + status endpoints — a 201 on fresh activation, a 200 replay, a 400 (RFC-7807) for a
 * missing Idempotency-Key or an invalid body, a 409 for an already-active company, and a 422 for a
 * sealed cutover. The writer + reader are mocked (no DB); the gateway's OWNER-ONLY gate on {@code
 * .../activate} is proven separately at the edge (gateway {@code RoutingConfig} test), not here.
 */
@WebMvcTest(InventoryMethodController.class)
@Import({InventoryMethodAdvice.class, ApiExceptionHandler.class, ConstraintViolationAdvice.class})
class InventoryMethodControllerTest {

  @Autowired private MockMvc mockMvc;

  @MockitoBean private InventoryMethodActivationWriter writer;
  @MockitoBean private InventoryMethodStatusReader reader;

  private static InventoryMethodStatusResponse sampleStatus() {
    return new InventoryMethodStatusResponse(
        true,
        "PERPETUAL",
        "2026-08",
        Instant.parse("2026-08-19T00:00:00Z"),
        5_000_000L,
        false,
        "IDR");
  }

  private static InventoryMethodConfig sampleConfig() {
    return InventoryMethodConfig.activate(
        "2026-08",
        Instant.parse("2026-08-19T00:00:00Z"),
        "IDR",
        5_000_000L,
        UUID.randomUUID(),
        "activate-key-1");
  }

  @Test
  void activateReturns201WithLocationAndTheStatusBody() throws Exception {
    when(writer.activate(anyString(), anyLong(), anyString(), anyString()))
        .thenReturn(new ActivationResult(sampleConfig(), true));
    when(reader.read()).thenReturn(sampleStatus());

    mockMvc
        .perform(
            post("/api/v1/inventory-method/activate")
                .header("Idempotency-Key", "activate-key-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"cutoverPeriod\":\"2026-08\",\"openingInventoryValueMinor\":5000000,"
                        + "\"currency\":\"IDR\"}"))
        .andExpect(status().isCreated())
        .andExpect(header().string("Location", "/api/v1/inventory-method"))
        .andExpect(jsonPath("$.active").value(true))
        .andExpect(jsonPath("$.method").value("PERPETUAL"))
        .andExpect(jsonPath("$.cutoverPeriod").value("2026-08"))
        .andExpect(jsonPath("$.inventoryAssetMinor").value(5_000_000L))
        .andExpect(jsonPath("$.inventoryAssetNegative").value(false))
        .andExpect(jsonPath("$.currency").value("IDR"));
  }

  @Test
  void activateReplayReturns200NotCreated() throws Exception {
    when(writer.activate(anyString(), anyLong(), anyString(), anyString()))
        .thenReturn(new ActivationResult(sampleConfig(), false));
    when(reader.read()).thenReturn(sampleStatus());

    mockMvc
        .perform(
            post("/api/v1/inventory-method/activate")
                .header("Idempotency-Key", "activate-key-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"cutoverPeriod\":\"2026-08\",\"openingInventoryValueMinor\":5000000,"
                        + "\"currency\":\"IDR\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.active").value(true));
  }

  @Test
  void activateWithoutIdempotencyKeyIsBadRequest() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/inventory-method/activate")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"cutoverPeriod\":\"2026-08\",\"openingInventoryValueMinor\":5000000,"
                        + "\"currency\":\"IDR\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
  }

  @Test
  void activateWithAMalformedCutoverPeriodIsBadRequest() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/inventory-method/activate")
                .header("Idempotency-Key", "activate-key-2")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"cutoverPeriod\":\"2026/08\",\"openingInventoryValueMinor\":0,"
                        + "\"currency\":\"IDR\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
  }

  @Test
  void activateWithANegativeOpeningValueIsBadRequest() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/inventory-method/activate")
                .header("Idempotency-Key", "activate-key-3")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"cutoverPeriod\":\"2026-08\",\"openingInventoryValueMinor\":-1,"
                        + "\"currency\":\"IDR\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
  }

  @Test
  void activateAnAlreadyActiveCompanyIsAProblemDetail409() throws Exception {
    when(writer.activate(anyString(), anyLong(), anyString(), anyString()))
        .thenThrow(new InventoryMethodAlreadyActiveException("already active"));

    mockMvc
        .perform(
            post("/api/v1/inventory-method/activate")
                .header("Idempotency-Key", "activate-key-4")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"cutoverPeriod\":\"2026-08\",\"openingInventoryValueMinor\":0,"
                        + "\"currency\":\"IDR\"}"))
        .andExpect(status().isConflict())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(
            jsonPath("$.type")
                .value("https://errors.nativeapp.id/inventory-method-already-active"));
  }

  @Test
  void activateASealedCutoverPeriodIsAProblemDetail422() throws Exception {
    when(writer.activate(anyString(), anyLong(), anyString(), anyString()))
        .thenThrow(new InventoryMethodSealedPeriodException("sealed"));

    mockMvc
        .perform(
            post("/api/v1/inventory-method/activate")
                .header("Idempotency-Key", "activate-key-5")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"cutoverPeriod\":\"2026-06\",\"openingInventoryValueMinor\":0,"
                        + "\"currency\":\"IDR\"}"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(
            jsonPath("$.type").value("https://errors.nativeapp.id/inventory-method-sealed-period"));
  }

  @Test
  void statusReturns200WithTheCurrentElection() throws Exception {
    when(reader.read()).thenReturn(sampleStatus());

    mockMvc
        .perform(get("/api/v1/inventory-method"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.active").value(true))
        .andExpect(jsonPath("$.inventoryAssetMinor").value(5_000_000L))
        .andExpect(jsonPath("$.inventoryAssetNegative").value(false));
  }

  @Test
  void statusForANonActivatedCompanyReadsActiveFalse() throws Exception {
    when(reader.read())
        .thenReturn(new InventoryMethodStatusResponse(false, null, null, null, 0L, false, null));

    mockMvc
        .perform(get("/api/v1/inventory-method"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.active").value(false))
        .andExpect(jsonPath("$.method").doesNotExist())
        .andExpect(jsonPath("$.inventoryAssetMinor").value(0));
  }
}
