package id.co.nativeapp.finance.labor.controller;

import id.co.nativeapp.finance.labor.domain.PayrollRunLedgerNotFoundException;
import id.co.nativeapp.finance.labor.domain.SettlementKind;
import id.co.nativeapp.finance.labor.dto.PayrollLiabilityRunResponse;
import id.co.nativeapp.finance.labor.dto.SettlePayrollLiabilityRequest;
import id.co.nativeapp.finance.labor.service.PayrollLiabilityReader;
import id.co.nativeapp.finance.labor.service.PayrollSettlementResult;
import id.co.nativeapp.finance.labor.service.PayrollSettlementWriter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/payroll-liabilities} — the payroll-liability recognition + settlement surface for
 * the bound tenant (ADR 0032, Track P phase P5). Console HR reads this finance-service endpoint
 * DIRECTLY (the established cross-service read pattern — finance owns the books; rule 2 forbids a
 * synchronous call between business services, but a dashboard reading finance's own API is not
 * one). Writes go through the RLS-bound {@link PayrollSettlementWriter}; every response is a DTO
 * (never the entity). Typed faults map to RFC-7807 via {@link PayrollLiabilityAdvice} (404
 * not-found, 409 invalid-state/idempotency-conflict, 422 currency-mismatch/negative-bucket, 400
 * bad-input/keyless).
 */
@Tag(
    name = "Payroll Liabilities",
    description = "Payroll liability recognition + settlement (ADR 0032, Track P phase P5).")
@RestController
@RequestMapping("/api/v1/payroll-liabilities")
@Validated
public class PayrollLiabilityController {

  private final PayrollLiabilityReader payrollLiabilityReader;
  private final PayrollSettlementWriter payrollSettlementWriter;

  public PayrollLiabilityController(
      PayrollLiabilityReader payrollLiabilityReader,
      PayrollSettlementWriter payrollSettlementWriter) {
    this.payrollLiabilityReader = payrollLiabilityReader;
    this.payrollSettlementWriter = payrollSettlementWriter;
  }

  @Operation(
      summary = "The period's ACTIVE payroll runs — five liability buckets + settlement status")
  @GetMapping
  public List<PayrollLiabilityRunResponse> list(
      @RequestParam
          @Pattern(
              regexp = "\\d{4}-(0[1-9]|1[0-2])",
              message = "period must be a valid YYYY-MM month")
          String period) {
    return payrollLiabilityReader.forPeriod(period);
  }

  @Operation(summary = "One run's liability buckets + settlement status by run-ledger id")
  @GetMapping("/{runLedgerId}")
  public PayrollLiabilityRunResponse get(@PathVariable UUID runLedgerId) {
    return payrollLiabilityReader
        .forRunLedger(runLedgerId)
        .orElseThrow(() -> new PayrollRunLedgerNotFoundException(runLedgerId));
  }

  @Operation(
      summary =
          "Settle one liability bucket of a run (posts Dr <bucket role account> / Cr"
              + " cash-clearing) — the amount is always read back from the run's own liability"
              + " entry, never client-supplied")
  @PostMapping("/{runLedgerId}/settlements")
  public ResponseEntity<PayrollLiabilityRunResponse> settle(
      @PathVariable UUID runLedgerId,
      @Valid @RequestBody SettlePayrollLiabilityRequest request,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
    // The key is REQUIRED (the AR/assets idiom): settling posts money, so a keyless request —
    // which could silently double-pay a bucket on a retry — is rejected with 400.
    if (idempotencyKey == null || idempotencyKey.isBlank()) {
      throw new IllegalArgumentException(
          "the Idempotency-Key header is required to settle a payroll liability");
    }
    SettlementKind kind = SettlementKind.valueOf(request.kind());
    PayrollSettlementResult result =
        payrollSettlementWriter.settle(runLedgerId, kind, idempotencyKey);
    PayrollLiabilityRunResponse detail =
        payrollLiabilityReader
            .forRunLedger(runLedgerId)
            .orElseThrow(() -> new PayrollRunLedgerNotFoundException(runLedgerId));
    // Idempotent-POST contract (ENGINEERING-STANDARDS §1.1): a fresh settle -> 201 + Location; a
    // same-key replay -> 200 with the current state, posting nothing again.
    if (!result.created()) {
      return ResponseEntity.ok(detail);
    }
    return ResponseEntity.created(URI.create("/api/v1/payroll-liabilities/" + runLedgerId))
        .body(detail);
  }
}
