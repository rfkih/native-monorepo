package id.co.nativeapp.finance;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import id.co.nativeapp.finance.config.ConstraintViolationAdvice;
import id.co.nativeapp.finance.gl.domain.GlMultiCurrencyException;
import id.co.nativeapp.finance.statements.controller.StatementsController;
import id.co.nativeapp.finance.statements.dto.BalanceSheetLineItem;
import id.co.nativeapp.finance.statements.dto.BalanceSheetResponse;
import id.co.nativeapp.finance.statements.dto.IncomeLineItem;
import id.co.nativeapp.finance.statements.dto.IncomeStatementResponse;
import id.co.nativeapp.finance.statements.service.BalanceSheetReader;
import id.co.nativeapp.finance.statements.service.IncomeStatementReader;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Web-slice test for {@code GET /api/v1/statements/{income,balance-sheet}}: a 200 with the
 * assembled statement when data exists, a 204 when there are no GL entries, an RFC-7807 {@code
 * application/problem+json} 400 for a malformed period, and a typed 422 when the underlying GL
 * spans more than one currency. Both readers are mocked — no DB. Mirrors {@code PnlControllerTest}.
 */
@WebMvcTest(StatementsController.class)
@Import(ConstraintViolationAdvice.class)
class StatementsControllerTest {

  private static final String PERIOD = "2026-06";

  @Autowired private MockMvc mockMvc;

  @MockitoBean private IncomeStatementReader incomeStatementReader;
  @MockitoBean private BalanceSheetReader balanceSheetReader;

  // --- Income statement -----------------------------------------------------

  @Test
  void incomeStatementReturns200WithRevenueExpenseAndNet() throws Exception {
    IncomeStatementResponse income =
        new IncomeStatementResponse(
            PERIOD,
            "IDR",
            List.of(new IncomeLineItem("4000", "REVENUE", 10_000_000L, "IDR")),
            List.of(new IncomeLineItem("5100", "EXPENSE", 4_000_000L, "IDR")),
            10_000_000L,
            4_000_000L,
            6_000_000L,
            true);
    when(incomeStatementReader.read(PERIOD)).thenReturn(Optional.of(income));

    mockMvc
        .perform(get("/api/v1/statements/income").param("period", PERIOD))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.period").value(PERIOD))
        .andExpect(jsonPath("$.currency").value("IDR"))
        .andExpect(jsonPath("$.totalRevenueMinor").value(10_000_000L))
        .andExpect(jsonPath("$.totalExpenseMinor").value(4_000_000L))
        .andExpect(jsonPath("$.netMinor").value(6_000_000L))
        // The illustrative flag reaches the API so the dashboard can render a provisional badge.
        .andExpect(jsonPath("$.usesIllustrativeRules").value(true))
        .andExpect(jsonPath("$.revenueLines[0].accountCode").value("4000"));
  }

  @Test
  void incomeStatementReturns204WhenNoGlEntries() throws Exception {
    when(incomeStatementReader.read("2026-07")).thenReturn(Optional.empty());

    mockMvc
        .perform(get("/api/v1/statements/income").param("period", "2026-07"))
        .andExpect(status().isNoContent());
  }

  @Test
  void incomeStatementRejectsMalformedPeriodWithProblemDetail400() throws Exception {
    mockMvc
        .perform(get("/api/v1/statements/income").param("period", "June-2026"))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.type").value("https://errors.nativeapp.id/validation-failed"));
  }

  @Test
  void incomeStatementMapsMultiCurrencyTo422ProblemDetail() throws Exception {
    when(incomeStatementReader.read(PERIOD))
        .thenThrow(new GlMultiCurrencyException("period " + PERIOD, "IDR", "USD"));

    mockMvc
        .perform(get("/api/v1/statements/income").param("period", PERIOD))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.type").value("https://errors.nativeapp.id/multi-currency-ledger"));
  }

  // --- Balance sheet --------------------------------------------------------

  @Test
  void balanceSheetReturns200AndBalances() throws Exception {
    BalanceSheetResponse bs =
        new BalanceSheetResponse(
            PERIOD,
            "IDR",
            List.of(new BalanceSheetLineItem("1100", "ASSET", 6_000_000L, "IDR")),
            List.of(),
            List.of(
                new BalanceSheetLineItem("3000-RETAINED-EARNINGS", "EQUITY", 6_000_000L, "IDR")),
            6_000_000L,
            0L,
            6_000_000L,
            6_000_000L,
            6_000_000L,
            false);
    when(balanceSheetReader.read(PERIOD)).thenReturn(Optional.of(bs));

    mockMvc
        .perform(get("/api/v1/statements/balance-sheet").param("asOf", PERIOD))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.asOf").value(PERIOD))
        .andExpect(jsonPath("$.currency").value("IDR"))
        .andExpect(jsonPath("$.totalAssetsMinor").value(6_000_000L))
        .andExpect(jsonPath("$.totalLiabilitiesAndEquityMinor").value(6_000_000L))
        .andExpect(jsonPath("$.retainedEarningsMinor").value(6_000_000L));
  }

  @Test
  void balanceSheetReturns204WhenNoGlEntries() throws Exception {
    when(balanceSheetReader.read("2026-07")).thenReturn(Optional.empty());

    mockMvc
        .perform(get("/api/v1/statements/balance-sheet").param("asOf", "2026-07"))
        .andExpect(status().isNoContent());
  }

  @Test
  void balanceSheetRejectsMalformedAsOfWithProblemDetail400() throws Exception {
    mockMvc
        .perform(get("/api/v1/statements/balance-sheet").param("asOf", "2026-13"))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.type").value("https://errors.nativeapp.id/validation-failed"));
  }

  @Test
  void balanceSheetMapsMultiCurrencyTo422ProblemDetail() throws Exception {
    when(balanceSheetReader.read(PERIOD))
        .thenThrow(new GlMultiCurrencyException("periods up to " + PERIOD, "IDR", "USD"));

    mockMvc
        .perform(get("/api/v1/statements/balance-sheet").param("asOf", PERIOD))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.type").value("https://errors.nativeapp.id/multi-currency-ledger"));
  }
}
