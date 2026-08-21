package id.co.nativeapp.finance;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import id.co.nativeapp.finance.config.ConstraintViolationAdvice;
import id.co.nativeapp.finance.platform.controller.PlatformSettlementController;
import id.co.nativeapp.finance.platform.dto.PlatformSettlementSummaryResponse;
import id.co.nativeapp.finance.platform.service.PlatformSettlementWriter;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Web-slice test for {@code GET /api/v1/platform-settlements/summary} — no DB, mirrors {@code
 * StatementsControllerTest}'s {@code @WebMvcTest} style. Proves a valid {@code period} reaches
 * {@link PlatformSettlementWriter#summary} and maps the result, and that a malformed {@code period}
 * is rejected with an RFC 7807 {@code application/problem+json} 400 via the shared {@link
 * ConstraintViolationAdvice}.
 */
@WebMvcTest(PlatformSettlementController.class)
@Import(ConstraintViolationAdvice.class)
class PlatformSettlementSummaryControllerTest {

  private static final String PATH = "/api/v1/platform-settlements/summary";

  @Autowired private MockMvc mockMvc;

  @MockitoBean private PlatformSettlementWriter writer;

  @Test
  void aValidPeriodReturns200WithTheMappedSummary() throws Exception {
    when(writer.summary("2026-06"))
        .thenReturn(
            List.of(
                new PlatformSettlementSummaryResponse(
                    "GOFOOD", 300_000L, 30_000L, 270_000L, 2L, "IDR")));

    mockMvc
        .perform(get(PATH).param("period", "2026-06"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].channelCode").value("GOFOOD"))
        .andExpect(jsonPath("$[0].settledGrossMinor").value(300_000))
        .andExpect(jsonPath("$[0].feeMinor").value(30_000))
        .andExpect(jsonPath("$[0].netMinor").value(270_000))
        .andExpect(jsonPath("$[0].settlementCount").value(2))
        .andExpect(jsonPath("$[0].currency").value("IDR"));
  }

  @Test
  void noSettlementsInThePeriodReturns200WithAnEmptyList() throws Exception {
    when(writer.summary("2026-07")).thenReturn(List.of());

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
