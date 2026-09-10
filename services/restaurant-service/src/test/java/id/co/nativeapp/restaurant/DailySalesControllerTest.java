package id.co.nativeapp.restaurant;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import id.co.nativeapp.restaurant.config.ConstraintViolationAdvice;
import id.co.nativeapp.restaurant.sale.controller.SaleController;
import id.co.nativeapp.restaurant.sale.dto.DailySalesResponse;
import id.co.nativeapp.restaurant.sale.service.SaleService;
import id.co.nativeapp.security.ApiExceptionHandler;
import java.time.LocalDate;
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
 * Web-slice tests for {@code GET /api/v1/sales/daily} — the phone home's per-day sales read (ADR
 * 0082). No DB, mirrors {@link ChannelSalesSummaryControllerTest}. Proves a well-formed window
 * reaches {@link SaleService#dailySummary} and maps every field, that an inverted window is a 400
 * (an empty list would read as "no sales", a different and misleading answer), and that a window
 * wider than the cap is a 400 before it ever reaches the service.
 */
@WebMvcTest(SaleController.class)
@Import({ApiExceptionHandler.class, ConstraintViolationAdvice.class})
class DailySalesControllerTest {

  private static final String PATH = "/api/v1/sales/daily";
  private static final UUID BUSINESS = UUID.fromString("22222222-2222-2222-2222-222222222222");

  @Autowired private MockMvc mockMvc;

  @MockitoBean private SaleService saleService;

  @Test
  void aValidWindowReturns200WithTheMappedDays() throws Exception {
    when(saleService.dailySummary(BUSINESS, LocalDate.of(2026, 9, 4), LocalDate.of(2026, 9, 10)))
        .thenReturn(
            List.of(
                new DailySalesResponse(
                    LocalDate.of(2026, 9, 9), 3L, 120_000L, 48_000L, 2L, "IDR", false),
                new DailySalesResponse(
                    LocalDate.of(2026, 9, 10), 1L, 40_000L, null, 0L, "IDR", true)));

    mockMvc
        .perform(
            get(PATH)
                .param("businessId", BUSINESS.toString())
                .param("from", "2026-09-04")
                .param("to", "2026-09-10"))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.length()").value(2))
        .andExpect(jsonPath("$[0].businessDate").value("2026-09-09"))
        .andExpect(jsonPath("$[0].transactionCount").value(3))
        .andExpect(jsonPath("$[0].netSalesMinor").value(120_000))
        .andExpect(jsonPath("$[0].cogsMinor").value(48_000))
        .andExpect(jsonPath("$[0].costedTransactionCount").value(2))
        .andExpect(jsonPath("$[0].currency").value("IDR"))
        .andExpect(jsonPath("$[0].usesIllustrativeRules").value(false))
        .andExpect(jsonPath("$[1].cogsMinor").doesNotExist())
        .andExpect(jsonPath("$[1].usesIllustrativeRules").value(true));
  }

  @Test
  void anInvertedWindowIsA400ProblemNotAnEmptyList() throws Exception {
    mockMvc
        .perform(
            get(PATH)
                .param("businessId", BUSINESS.toString())
                .param("from", "2026-09-10")
                .param("to", "2026-09-04"))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));

    verifyNoInteractions(saleService);
  }

  @Test
  void aWindowWiderThanTheCapIsA400() throws Exception {
    mockMvc
        .perform(
            get(PATH)
                .param("businessId", BUSINESS.toString())
                .param("from", "2026-01-01")
                .param("to", "2026-12-31"))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));

    verifyNoInteractions(saleService);
  }
}
