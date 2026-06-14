package id.co.nativeapp.finance;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import id.co.nativeapp.finance.config.ConstraintViolationAdvice;
import id.co.nativeapp.finance.fx.PresentationConverter;
import id.co.nativeapp.finance.revenue.RevenueController;
import id.co.nativeapp.finance.revenue.RevenueReader;
import id.co.nativeapp.money.Money;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Web-slice test for {@code GET /api/v1/revenue}: a 200 with the Money pair when revenue exists, a
 * 200 zero in the supplied currency for an empty period, a 204 when there is neither data nor a
 * currency hint, and an RFC-7807 {@code application/problem+json} 400 for a malformed period. The
 * {@link RevenueReader} is mocked — no DB.
 */
@WebMvcTest(RevenueController.class)
@Import(ConstraintViolationAdvice.class)
class RevenueControllerTest {

  @Autowired private MockMvc mockMvc;

  @MockitoBean private RevenueReader revenueReader;

  // The presentation path is not exercised in these native-only cases; mocked so the context wires.
  @MockitoBean private PresentationConverter presentationConverter;

  @Test
  void returnsTheConsolidatedRevenueAsMinorPlusCurrency() throws Exception {
    when(revenueReader.revenueForPeriod("2026-06"))
        .thenReturn(Optional.of(Money.ofMinor(1_500_000L, "IDR")));

    mockMvc
        .perform(get("/api/v1/revenue").param("period", "2026-06"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.period").value("2026-06"))
        .andExpect(jsonPath("$.totalMinor").value(1_500_000L))
        .andExpect(jsonPath("$.currency").value("IDR"));
  }

  @Test
  void returnsAZeroTotalInTheSuppliedCurrencyForAnEmptyPeriod() throws Exception {
    when(revenueReader.revenueForPeriod("2026-07")).thenReturn(Optional.empty());

    mockMvc
        .perform(get("/api/v1/revenue").param("period", "2026-07").param("currency", "IDR"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalMinor").value(0L))
        .andExpect(jsonPath("$.currency").value("IDR"));
  }

  @Test
  void returns204WhenNoDataAndNoCurrencyHint() throws Exception {
    when(revenueReader.revenueForPeriod("2026-08")).thenReturn(Optional.empty());

    mockMvc
        .perform(get("/api/v1/revenue").param("period", "2026-08"))
        .andExpect(status().isNoContent());
  }

  @Test
  void rejectsAMalformedPeriodWithAProblemDetail400() throws Exception {
    mockMvc
        .perform(get("/api/v1/revenue").param("period", "June-2026"))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.type").value("https://errors.nativeapp.id/validation-failed"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"2026-13", "2026-00"})
  void rejectsAnImpossibleMonthWithAProblemDetail400(String impossiblePeriod) throws Exception {
    // A well-FORMED but impossible month (13 / 00) used to slip through the bare \d{4}-\d{2}
    // regex and return 204; it must now be a validation-failed 400, like a malformed format.
    mockMvc
        .perform(get("/api/v1/revenue").param("period", impossiblePeriod))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.type").value("https://errors.nativeapp.id/validation-failed"));
  }
}
