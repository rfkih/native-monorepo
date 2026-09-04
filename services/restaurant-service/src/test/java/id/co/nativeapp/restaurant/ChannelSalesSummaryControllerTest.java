package id.co.nativeapp.restaurant;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import id.co.nativeapp.restaurant.config.ConstraintViolationAdvice;
import id.co.nativeapp.restaurant.sale.controller.SaleController;
import id.co.nativeapp.restaurant.sale.dto.ChannelSalesSummaryResponse;
import id.co.nativeapp.restaurant.sale.service.SaleService;
import id.co.nativeapp.security.ApiExceptionHandler;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Web-slice tests for {@code GET /api/v1/sales/channel-summary} — no DB, mirrors {@code
 * SaleHistoryControllerTest}'s {@code @WebMvcTest} style. Proves a valid {@code period} reaches
 * {@link SaleService#channelSalesSummary} and maps the result, and that a malformed {@code period}
 * is rejected with an RFC 7807 {@code application/problem+json} 400 (via the
 * restaurant-service-specific {@link ConstraintViolationAdvice}, mirroring finance-service's {@code
 * StatementsControllerTest}).
 */
@WebMvcTest(SaleController.class)
@Import({ApiExceptionHandler.class, ConstraintViolationAdvice.class})
class ChannelSalesSummaryControllerTest {

  private static final String PATH = "/api/v1/sales/channel-summary";

  @Autowired private MockMvc mockMvc;

  @MockitoBean private SaleService saleService;

  @Test
  void aValidPeriodReturns200WithTheMappedSummary() throws Exception {
    when(saleService.channelSalesSummary("2026-06"))
        .thenReturn(List.of(new ChannelSalesSummaryResponse("GOFOOD", 120_000L, 2L, "IDR")));

    mockMvc
        .perform(get(PATH).param("period", "2026-06"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].channelCode").value("GOFOOD"))
        .andExpect(jsonPath("$[0].grossSalesMinor").value(120_000))
        .andExpect(jsonPath("$[0].transactionCount").value(2))
        .andExpect(jsonPath("$[0].currency").value("IDR"));
  }

  @Test
  void noOnlineSalesInThePeriodReturns200WithAnEmptyList() throws Exception {
    when(saleService.channelSalesSummary("2026-07")).thenReturn(List.of());

    mockMvc
        .perform(get(PATH).param("period", "2026-07"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$").isArray())
        .andExpect(jsonPath("$").isEmpty());
  }

  @Test
  void aMalformedPeriodIsRejectedWithAProblemDetail400() throws Exception {
    mockMvc
        .perform(get(PATH).param("period", "June-2026"))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.status").value(400))
        .andExpect(jsonPath("$.type").value("https://errors.nativeapp.id/validation-failed"));
  }

  @Test
  void anImpossibleMonthIsRejectedWithAProblemDetail400() throws Exception {
    mockMvc
        .perform(get(PATH).param("period", "2026-13"))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.type").value("https://errors.nativeapp.id/validation-failed"));
  }
}
