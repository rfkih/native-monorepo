package id.co.nativeapp.finance;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import id.co.nativeapp.finance.config.ConstraintViolationAdvice;
import id.co.nativeapp.finance.labor.controller.PayrollLiabilityAdvice;
import id.co.nativeapp.finance.labor.controller.PayrollLiabilityController;
import id.co.nativeapp.finance.labor.domain.NegativeLiabilityBucketException;
import id.co.nativeapp.finance.labor.domain.PayrollLiabilityNotSettleableException;
import id.co.nativeapp.finance.labor.domain.PayrollSettlementAlreadySettledException;
import id.co.nativeapp.finance.labor.domain.PayrollSettlementIdempotencyKeyConflictException;
import id.co.nativeapp.finance.labor.domain.SettlementKind;
import id.co.nativeapp.finance.labor.dto.PayrollLiabilityBucketResponse;
import id.co.nativeapp.finance.labor.dto.PayrollLiabilityRunResponse;
import id.co.nativeapp.finance.labor.service.PayrollLiabilityReader;
import id.co.nativeapp.finance.labor.service.PayrollSettlementResult;
import id.co.nativeapp.finance.labor.service.PayrollSettlementWriter;
import id.co.nativeapp.finance.pnl.domain.MismatchedPostingCurrencyException;
import id.co.nativeapp.security.ApiExceptionHandler;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Web-slice test for {@link PayrollLiabilityController} (ADR 0032, Track P phase P5): the period
 * read (200), a single run read (200/404), settle (201 fresh / 200 replay / 400 keyless / 409
 * conflicts / 422 negative-bucket / 422 currency-mismatch). Services mocked. Mirrors {@code
 * TaxControllerTest}/{@code AssetControllerTest}.
 */
@WebMvcTest(PayrollLiabilityController.class)
@Import({PayrollLiabilityAdvice.class, ApiExceptionHandler.class, ConstraintViolationAdvice.class})
class PayrollLiabilityControllerTest {

  private static final UUID RUN_LEDGER = UUID.fromString("aaaaaaaa-0000-0000-0000-00000000000a");

  @Autowired private MockMvc mockMvc;

  @MockitoBean private PayrollLiabilityReader payrollLiabilityReader;
  @MockitoBean private PayrollSettlementWriter payrollSettlementWriter;

  private static PayrollLiabilityRunResponse sampleRun(boolean netWagesSettled) {
    return new PayrollLiabilityRunResponse(
        RUN_LEDGER,
        UUID.randomUUID(),
        "2026-07",
        1,
        "REGULAR",
        "IDR",
        List.of(
            new PayrollLiabilityBucketResponse(
                "NET_WAGES",
                17_798_333L,
                "IDR",
                false,
                netWagesSettled,
                netWagesSettled ? java.time.Instant.parse("2026-08-05T00:00:00Z") : null,
                netWagesSettled ? UUID.randomUUID() : null),
            new PayrollLiabilityBucketResponse(
                "PPH21", 2_101_667L, "IDR", false, false, null, null),
            new PayrollLiabilityBucketResponse(
                "BPJS_KES", 500_000L, "IDR", false, false, null, null),
            new PayrollLiabilityBucketResponse(
                "BPJS_TK", 400_000L, "IDR", false, false, null, null),
            new PayrollLiabilityBucketResponse("OTHER", 0L, "IDR", false, false, null, null)));
  }

  @Test
  void listReturnsThePeriodsActiveRuns() throws Exception {
    when(payrollLiabilityReader.forPeriod("2026-07")).thenReturn(List.of(sampleRun(false)));

    mockMvc
        .perform(get("/api/v1/payroll-liabilities").param("period", "2026-07"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].runLedgerId").value(RUN_LEDGER.toString()))
        .andExpect(jsonPath("$[0].buckets[0].kind").value("NET_WAGES"))
        .andExpect(jsonPath("$[0].buckets[0].amountMinor").value(17_798_333L));
  }

  @Test
  void listWithABadPeriodIsA400() throws Exception {
    mockMvc
        .perform(get("/api/v1/payroll-liabilities").param("period", "2026-13"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void getReturnsTheRunDetail() throws Exception {
    when(payrollLiabilityReader.forRunLedger(RUN_LEDGER)).thenReturn(Optional.of(sampleRun(false)));

    mockMvc
        .perform(get("/api/v1/payroll-liabilities/{runLedgerId}", RUN_LEDGER))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.runLedgerId").value(RUN_LEDGER.toString()));
  }

  @Test
  void getAnUnknownRunLedgerIsA404() throws Exception {
    when(payrollLiabilityReader.forRunLedger(RUN_LEDGER)).thenReturn(Optional.empty());

    mockMvc
        .perform(get("/api/v1/payroll-liabilities/{runLedgerId}", RUN_LEDGER))
        .andExpect(status().isNotFound())
        .andExpect(
            jsonPath("$.type").value("https://errors.nativeapp.id/payroll-liability-not-found"));
  }

  @Test
  void settleFreshReturns201WithLocation() throws Exception {
    when(payrollSettlementWriter.settle(RUN_LEDGER, SettlementKind.NET_WAGES, "settle-key-1"))
        .thenReturn(new PayrollSettlementResult(UUID.randomUUID(), true));
    when(payrollLiabilityReader.forRunLedger(RUN_LEDGER)).thenReturn(Optional.of(sampleRun(true)));

    mockMvc
        .perform(
            post("/api/v1/payroll-liabilities/{runLedgerId}/settlements", RUN_LEDGER)
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "settle-key-1")
                .content("{\"kind\":\"NET_WAGES\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.buckets[0].settled").value(true));
  }

  @Test
  void settleReplayReturns200() throws Exception {
    when(payrollSettlementWriter.settle(RUN_LEDGER, SettlementKind.NET_WAGES, "settle-key-1"))
        .thenReturn(new PayrollSettlementResult(UUID.randomUUID(), false));
    when(payrollLiabilityReader.forRunLedger(RUN_LEDGER)).thenReturn(Optional.of(sampleRun(true)));

    mockMvc
        .perform(
            post("/api/v1/payroll-liabilities/{runLedgerId}/settlements", RUN_LEDGER)
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "settle-key-1")
                .content("{\"kind\":\"NET_WAGES\"}"))
        .andExpect(status().isOk());
  }

  @Test
  void settleWithoutAnIdempotencyKeyIsA400() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/payroll-liabilities/{runLedgerId}/settlements", RUN_LEDGER)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"kind\":\"NET_WAGES\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(
            jsonPath("$.type")
                .value("https://errors.nativeapp.id/payroll-liability-invalid-request"));
  }

  @Test
  void settleWithAnUnknownKindIsA400() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/payroll-liabilities/{runLedgerId}/settlements", RUN_LEDGER)
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "settle-key-1")
                .content("{\"kind\":\"NOT_A_KIND\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void settleAnAlreadySettledBucketIsA409() throws Exception {
    when(payrollSettlementWriter.settle(any(), any(), any()))
        .thenThrow(new PayrollSettlementAlreadySettledException(RUN_LEDGER, "NET_WAGES"));

    mockMvc
        .perform(
            post("/api/v1/payroll-liabilities/{runLedgerId}/settlements", RUN_LEDGER)
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "settle-key-2")
                .content("{\"kind\":\"NET_WAGES\"}"))
        .andExpect(status().isConflict())
        .andExpect(
            jsonPath("$.type")
                .value("https://errors.nativeapp.id/payroll-settlement-already-settled"));
  }

  @Test
  void aSupersededRunIsA409NotSettleable() throws Exception {
    when(payrollSettlementWriter.settle(any(), any(), any()))
        .thenThrow(new PayrollLiabilityNotSettleableException(RUN_LEDGER, "superseded"));

    mockMvc
        .perform(
            post("/api/v1/payroll-liabilities/{runLedgerId}/settlements", RUN_LEDGER)
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "settle-key-3")
                .content("{\"kind\":\"NET_WAGES\"}"))
        .andExpect(status().isConflict())
        .andExpect(
            jsonPath("$.type")
                .value("https://errors.nativeapp.id/payroll-liability-not-settleable"));
  }

  @Test
  void anIdempotencyKeyReusedAgainstADifferentBucketIsA409() throws Exception {
    when(payrollSettlementWriter.settle(any(), any(), any()))
        .thenThrow(
            new PayrollSettlementIdempotencyKeyConflictException(
                RUN_LEDGER, "PPH21", RUN_LEDGER, "NET_WAGES"));

    mockMvc
        .perform(
            post("/api/v1/payroll-liabilities/{runLedgerId}/settlements", RUN_LEDGER)
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "shared-key")
                .content("{\"kind\":\"PPH21\"}"))
        .andExpect(status().isConflict())
        .andExpect(
            jsonPath("$.type")
                .value("https://errors.nativeapp.id/payroll-settlement-idempotency-key-conflict"));
  }

  @Test
  void aNegativeBucketIsA422() throws Exception {
    when(payrollSettlementWriter.settle(any(), any(), any()))
        .thenThrow(new NegativeLiabilityBucketException("PPH21"));

    mockMvc
        .perform(
            post("/api/v1/payroll-liabilities/{runLedgerId}/settlements", RUN_LEDGER)
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "settle-key-4")
                .content("{\"kind\":\"PPH21\"}"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(
            jsonPath("$.type")
                .value("https://errors.nativeapp.id/payroll-liability-negative-bucket"));
  }

  @Test
  void aDivergentCurrencyIsA422() throws Exception {
    when(payrollSettlementWriter.settle(any(), any(), any()))
        .thenThrow(new MismatchedPostingCurrencyException("2026-08", "IDR", "USD"));

    mockMvc
        .perform(
            post("/api/v1/payroll-liabilities/{runLedgerId}/settlements", RUN_LEDGER)
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "settle-key-5")
                .content("{\"kind\":\"NET_WAGES\"}"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(
            jsonPath("$.type")
                .value("https://errors.nativeapp.id/payroll-liability-currency-mismatch"));
  }
}
