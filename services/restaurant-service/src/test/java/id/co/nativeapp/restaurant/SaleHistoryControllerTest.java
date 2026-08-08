package id.co.nativeapp.restaurant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import id.co.nativeapp.restaurant.sale.controller.SaleController;
import id.co.nativeapp.restaurant.sale.dto.SaleHistoryResponse;
import id.co.nativeapp.restaurant.sale.service.SaleService;
import id.co.nativeapp.security.ApiExceptionHandler;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Web-slice tests for {@code GET /api/v1/sales} (the cashier's "today's transactions" list) — no
 * DB, mirrors {@link SaleControllerValidationTest}'s {@code @WebMvcTest} style.
 *
 * <p>Proves the three required query params ({@code businessId}, {@code from}, {@code to}) bind
 * correctly and are threaded to {@link SaleService#findHistory}, that a valid request returns
 * {@code 200 OK} with the mapped {@link SaleHistoryResponse} list, and that a missing required
 * param never reaches the handler (the service is never invoked). Note: {@code
 * MissingServletRequestParameterException} has no bespoke mapping in the shared {@link
 * ApiExceptionHandler} (libs/security, fleet-wide — out of this endpoint's scope), so it currently
 * falls through to that advice's catch-all and surfaces as a {@code 5xx}, not a {@code 400} — the
 * SAME pre-existing behavior every other required {@code @RequestParam} in this service already has
 * (e.g. {@code BillController}/{@code OrderController}). These tests pin that actual behavior
 * rather than assert an aspirational one.
 */
@WebMvcTest(SaleController.class)
@Import(ApiExceptionHandler.class)
class SaleHistoryControllerTest {

  private static final String SALES_PATH = "/api/v1/sales";
  private static final String BUSINESS_ID = "22222222-2222-2222-2222-222222222222";

  @Autowired private MockMvc mockMvc;

  @MockitoBean private SaleService saleService;

  @Test
  void aValidRequestReturns200WithTheMappedHistoryList() throws Exception {
    UUID saleId = UUID.fromString("33333333-3333-3333-3333-333333333333");
    UUID orderId = UUID.fromString("44444444-4444-4444-4444-444444444444");
    Instant occurredAt = Instant.parse("2026-08-08T02:00:00Z");
    when(saleService.findHistory(any(), any(), any()))
        .thenReturn(
            List.of(
                new SaleHistoryResponse(
                    saleId, orderId, occurredAt, 150_000L, "IDR", "CASH", null)));

    mockMvc
        .perform(
            get(SALES_PATH)
                .param("businessId", BUSINESS_ID)
                .param("from", "2026-08-08T00:00:00Z")
                .param("to", "2026-08-09T00:00:00Z"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].saleId").value(saleId.toString()))
        .andExpect(jsonPath("$[0].orderId").value(orderId.toString()))
        .andExpect(jsonPath("$[0].amountMinor").value(150_000))
        .andExpect(jsonPath("$[0].currency").value("IDR"))
        .andExpect(jsonPath("$[0].tenderType").value("CASH"))
        .andExpect(jsonPath("$[0].channelCode").doesNotExist());
  }

  @Test
  void theBoundParamsAreThreadedToTheServiceUnchanged() throws Exception {
    when(saleService.findHistory(any(), any(), any())).thenReturn(List.of());

    mockMvc
        .perform(
            get(SALES_PATH)
                .param("businessId", BUSINESS_ID)
                .param("from", "2026-08-08T00:00:00Z")
                .param("to", "2026-08-09T00:00:00Z"))
        .andExpect(status().isOk());

    verify(saleService)
        .findHistory(
            UUID.fromString(BUSINESS_ID),
            Instant.parse("2026-08-08T00:00:00Z"),
            Instant.parse("2026-08-09T00:00:00Z"));
  }

  @Test
  void missingBusinessIdNeverReachesTheService() throws Exception {
    mockMvc
        .perform(
            get(SALES_PATH)
                .param("from", "2026-08-08T00:00:00Z")
                .param("to", "2026-08-09T00:00:00Z"))
        .andExpect(status().is5xxServerError());

    verifyNoInteractions(saleService);
  }

  @Test
  void missingFromNeverReachesTheService() throws Exception {
    mockMvc
        .perform(
            get(SALES_PATH).param("businessId", BUSINESS_ID).param("to", "2026-08-09T00:00:00Z"))
        .andExpect(status().is5xxServerError());

    verifyNoInteractions(saleService);
  }

  @Test
  void missingToNeverReachesTheService() throws Exception {
    mockMvc
        .perform(
            get(SALES_PATH).param("businessId", BUSINESS_ID).param("from", "2026-08-08T00:00:00Z"))
        .andExpect(status().is5xxServerError());

    verifyNoInteractions(saleService);
  }
}
