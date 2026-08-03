package id.co.nativeapp.finance.opening.controller;

import id.co.nativeapp.finance.opening.dto.OpeningBalanceResponse;
import id.co.nativeapp.finance.opening.dto.OpeningBalanceResult;
import id.co.nativeapp.finance.opening.dto.RecordOpeningBalancesRequest;
import id.co.nativeapp.finance.opening.service.OpeningBalanceWriter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Opening balances & business migration (ADR 0037) — dashboard-facing (gateway DASHBOARD_ROLES):
 * record a company's opening balance sheet once (assets/liabilities/equity, auto-plugged to Opening
 * Balance Equity), and read the recorded summary. Money rule 8 end to end.
 */
@Tag(
    name = "Opening balances",
    description = "Opening balance sheet & business migration (ADR 0037)")
@RestController
@RequestMapping("/api/v1/opening-balances")
public class OpeningBalanceController {

  private final OpeningBalanceWriter writer;

  public OpeningBalanceController(OpeningBalanceWriter writer) {
    this.writer = writer;
  }

  @Operation(
      summary =
          "Record the opening balance sheet — posts one balanced entry (Dr assets / Cr liabilities +"
              + " equity), auto-plugging the residual to Opening Balance Equity. Once-only per"
              + " company; balance-sheet accounts only.")
  @PostMapping
  public ResponseEntity<OpeningBalanceResponse> record(
      @Valid @RequestBody RecordOpeningBalancesRequest request,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
    // The key is REQUIRED (the payroll/AR/assets/platform idiom): posting opening balances moves
    // the
    // books, so a keyless request — which could silently double-post on a retry — is rejected 400.
    if (idempotencyKey == null || idempotencyKey.isBlank()) {
      throw new IllegalArgumentException(
          "the Idempotency-Key header is required to record opening balances");
    }
    if (idempotencyKey.length() > 64) {
      throw new IllegalArgumentException("the Idempotency-Key header must be at most 64 chars");
    }
    OpeningBalanceResult result =
        writer.record(request.asOfDate(), request.currency(), request.lines(), idempotencyKey);
    OpeningBalanceResponse body = OpeningBalanceResponse.from(result.openingBalance());
    // Idempotent-POST contract (ENGINEERING-STANDARDS §1.1): fresh → 201 + Location; replay → 200.
    if (!result.created()) {
      return ResponseEntity.ok(body);
    }
    return ResponseEntity.created(URI.create("/api/v1/opening-balances/" + body.id())).body(body);
  }

  @Operation(
      summary = "The recorded opening balance sheet for this company (404 if not yet recorded)")
  @GetMapping
  public ResponseEntity<OpeningBalanceResponse> current() {
    return ResponseEntity.of(writer.current().map(OpeningBalanceResponse::from));
  }
}
