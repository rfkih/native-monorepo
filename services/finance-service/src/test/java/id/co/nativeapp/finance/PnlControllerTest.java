package id.co.nativeapp.finance;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import id.co.nativeapp.finance.config.ConstraintViolationAdvice;
import id.co.nativeapp.finance.fx.PresentationConverter;
import id.co.nativeapp.finance.pnl.ConsolidatedPnl;
import id.co.nativeapp.finance.pnl.PnlController;
import id.co.nativeapp.finance.pnl.PnlReader;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Web-slice test for {@code GET /api/v1/pnl}: a 200 with revenue/expense/net when a P&L exists, a
 * 200 zero in the supplied currency for an empty period, a 204 when there is neither data nor a
 * currency hint, and an RFC-7807 {@code application/problem+json} 400 for a malformed period. The
 * {@link PnlReader} is mocked — no DB.
 */
@WebMvcTest(PnlController.class)
@Import(ConstraintViolationAdvice.class)
class PnlControllerTest {

  @Autowired private MockMvc mockMvc;

  @MockitoBean private PnlReader pnlReader;

  // The presentation path is not exercised in these native-only cases; mocked so the context wires.
  @MockitoBean private PresentationConverter presentationConverter;

  @Test
  void returnsTheConsolidatedPnlWithRevenueExpenseAndNet() throws Exception {
    ConsolidatedPnl pnl = Mockito.mock(ConsolidatedPnl.class);
    when(pnl.getPeriod()).thenReturn("2026-06");
    when(pnl.revenue()).thenReturn(id.co.nativeapp.money.Money.ofMinor(2_000_000L, "IDR"));
    when(pnl.expense()).thenReturn(id.co.nativeapp.money.Money.ofMinor(750_000L, "IDR"));
    when(pnl.net()).thenReturn(id.co.nativeapp.money.Money.ofMinor(1_250_000L, "IDR"));
    when(pnl.usesIllustrativeRules()).thenReturn(true);
    when(pnlReader.pnlForPeriod("2026-06")).thenReturn(Optional.of(pnl));

    mockMvc
        .perform(get("/api/v1/pnl").param("period", "2026-06"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.period").value("2026-06"))
        .andExpect(jsonPath("$.revenueMinor").value(2_000_000L))
        .andExpect(jsonPath("$.expenseMinor").value(750_000L))
        .andExpect(jsonPath("$.netMinor").value(1_250_000L))
        .andExpect(jsonPath("$.currency").value("IDR"))
        // The illustrative flag reaches the API so the dashboard can render a provisional badge.
        .andExpect(jsonPath("$.usesIllustrativeRules").value(true));
  }

  @Test
  void returnsAZeroPnlInTheSuppliedCurrencyForAnEmptyPeriod() throws Exception {
    when(pnlReader.pnlForPeriod("2026-07")).thenReturn(Optional.empty());

    mockMvc
        .perform(get("/api/v1/pnl").param("period", "2026-07").param("currency", "IDR"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.revenueMinor").value(0L))
        .andExpect(jsonPath("$.expenseMinor").value(0L))
        .andExpect(jsonPath("$.netMinor").value(0L))
        .andExpect(jsonPath("$.currency").value("IDR"));
  }

  @Test
  void returns204WhenNoDataAndNoCurrencyHint() throws Exception {
    when(pnlReader.pnlForPeriod("2026-08")).thenReturn(Optional.empty());

    mockMvc
        .perform(get("/api/v1/pnl").param("period", "2026-08"))
        .andExpect(status().isNoContent());
  }

  @Test
  void rejectsAMalformedPeriodWithAProblemDetail400() throws Exception {
    mockMvc
        .perform(get("/api/v1/pnl").param("period", "June-2026"))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.type").value("https://errors.nativeapp.id/validation-failed"));
  }
}
