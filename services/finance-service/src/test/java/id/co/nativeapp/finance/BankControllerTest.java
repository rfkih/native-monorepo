package id.co.nativeapp.finance;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import id.co.nativeapp.finance.bank.controller.BankAdvice;
import id.co.nativeapp.finance.bank.controller.BankController;
import id.co.nativeapp.finance.bank.domain.ReconciliationCategory;
import id.co.nativeapp.finance.bank.domain.ReconciliationStateException;
import id.co.nativeapp.finance.bank.domain.StatementLineNotFoundException;
import id.co.nativeapp.finance.bank.dto.ReconciliationReportResponse;
import id.co.nativeapp.finance.bank.dto.StatementLineResponse;
import id.co.nativeapp.finance.bank.service.ReconciliationReader;
import id.co.nativeapp.finance.bank.service.ReconciliationWriter;
import id.co.nativeapp.finance.bank.service.StatementLineReader;
import id.co.nativeapp.finance.config.ConstraintViolationAdvice;
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
 * Web-slice test for {@link BankController}: a 200 on reconcile with the fresh line detail, a 409
 * for a double-reconcile, a 400 for a category/direction mismatch or an invalid category, a 404 for
 * an unknown line, and the reconciliation report read. Services mocked; fault mappings from {@link
 * BankAdvice} + the shared {@link ApiExceptionHandler}. Mirrors {@link BillControllerTest}.
 */
@WebMvcTest(BankController.class)
@Import({BankAdvice.class, ApiExceptionHandler.class, ConstraintViolationAdvice.class})
class BankControllerTest {

  private static final UUID LINE = UUID.fromString("eeeeeeee-0000-0000-0000-000000000005");
  private static final UUID BANK_ACCOUNT = UUID.fromString("dddddddd-0000-0000-0000-000000000004");

  @Autowired private MockMvc mockMvc;

  @MockitoBean private ReconciliationWriter reconciliationWriter;
  @MockitoBean private ReconciliationReader reconciliationReader;
  @MockitoBean private StatementLineReader statementLineReader;

  private static StatementLineResponse sampleLine(String status, String category) {
    return new StatementLineResponse(
        LINE,
        BANK_ACCOUNT,
        LocalDate.parse("2026-06-14"),
        1_000_000L,
        "IDR",
        "Deposit",
        "REF-1",
        status,
        category,
        category == null ? null : UUID.randomUUID());
  }

  @Test
  void reconcileReturns200WithTheReconciledLine() throws Exception {
    when(reconciliationWriter.reconcile(eq(LINE), eq(ReconciliationCategory.CLEARING), any()))
        .thenReturn(LINE);
    when(statementLineReader.get(LINE)).thenReturn(sampleLine("RECONCILED", "CLEARING"));

    mockMvc
        .perform(
            post("/api/v1/bank/reconcile/{lineId}", LINE)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"category\":\"CLEARING\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("RECONCILED"))
        .andExpect(jsonPath("$.reconciledCategory").value("CLEARING"));
  }

  @Test
  void reconcileAnAlreadyReconciledLineIsAProblemDetail409() throws Exception {
    when(reconciliationWriter.reconcile(eq(LINE), any(), any()))
        .thenThrow(
            new ReconciliationStateException(
                "only an UNRECONCILED line can be reconciled; current status=RECONCILED"));

    mockMvc
        .perform(
            post("/api/v1/bank/reconcile/{lineId}", LINE)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"category\":\"CLEARING\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.type").value("https://errors.nativeapp.id/bank-invalid-state"));
  }

  @Test
  void reconcileWithMismatchedCategoryIsAProblemDetail400() throws Exception {
    when(reconciliationWriter.reconcile(eq(LINE), eq(ReconciliationCategory.BANK_FEE), any()))
        .thenThrow(new IllegalArgumentException("BANK_FEE can only reconcile a withdrawal"));

    mockMvc
        .perform(
            post("/api/v1/bank/reconcile/{lineId}", LINE)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"category\":\"BANK_FEE\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.type").value("https://errors.nativeapp.id/bank-invalid-request"));
  }

  @Test
  void reconcileAnUnknownLineIsAProblemDetail404() throws Exception {
    when(reconciliationWriter.reconcile(eq(LINE), any(), any()))
        .thenThrow(new StatementLineNotFoundException(LINE));

    mockMvc
        .perform(
            post("/api/v1/bank/reconcile/{lineId}", LINE)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"category\":\"CLEARING\"}"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.type").value("https://errors.nativeapp.id/bank-not-found"));
  }

  @Test
  void reconcileWithInvalidCategoryIsAProblemDetail400() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/bank/reconcile/{lineId}", LINE)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"category\":\"BOGUS\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void reconciliationReportReturns200() throws Exception {
    when(reconciliationReader.report(BANK_ACCOUNT))
        .thenReturn(
            new ReconciliationReportResponse(
                BANK_ACCOUNT,
                "IDR",
                1_000_000L,
                5_000_000L,
                1L,
                List.of(sampleLine("UNRECONCILED", null))));

    mockMvc
        .perform(get("/api/v1/bank/reconciliation").param("bankAccountId", BANK_ACCOUNT.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.bankBalanceMinor").value(1_000_000L))
        .andExpect(jsonPath("$.cashClearingBalanceMinor").value(5_000_000L))
        .andExpect(jsonPath("$.unreconciledCount").value(1));
  }
}
