package id.co.nativeapp.finance.platform.controller;

import id.co.nativeapp.finance.platform.dto.PlatformOutstandingResponse;
import id.co.nativeapp.finance.platform.dto.PlatformSettlementResponse;
import id.co.nativeapp.finance.platform.dto.PlatformSettlementResult;
import id.co.nativeapp.finance.platform.dto.SettlePlatformRequest;
import id.co.nativeapp.finance.platform.service.PlatformSettlementWriter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Platform channel settlements (ADR 0036 Phase C) — dashboard-facing (gateway DASHBOARD_ROLES):
 * record a payout, read outstanding per channel, browse history. Money rule 8 end to end.
 */
@Tag(name = "Platform settlements", description = "Online-platform payout settlements (ADR 0036)")
@RestController
@RequestMapping("/api/v1/platform-settlements")
public class PlatformSettlementController {

  private final PlatformSettlementWriter writer;

  public PlatformSettlementController(PlatformSettlementWriter writer) {
    this.writer = writer;
  }

  @Operation(
      summary =
          "Record one platform payout — posts Dr cash-clearing (net) + Dr platform-fee (gross −"
              + " net) / Cr platform-receivable (gross) and decrements the channel's outstanding"
              + " balance (guarded — over-settlement is rejected)")
  @PostMapping
  public ResponseEntity<PlatformSettlementResponse> settle(
      @Valid @RequestBody SettlePlatformRequest request,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
    // The key is REQUIRED (the payroll/AR/assets idiom): settling posts money, so a keyless
    // request — which could silently double-book a payout on a retry — is rejected with 400.
    if (idempotencyKey == null || idempotencyKey.isBlank()) {
      throw new IllegalArgumentException(
          "the Idempotency-Key header is required to record a platform settlement");
    }
    if (idempotencyKey.length() > 64) {
      throw new IllegalArgumentException("the Idempotency-Key header must be at most 64 chars");
    }
    PlatformSettlementResult result =
        writer.settle(
            request.channelCode(),
            request.grossMinor(),
            request.netMinor(),
            request.currency(),
            idempotencyKey);
    PlatformSettlementResponse body = PlatformSettlementResponse.from(result.settlement());
    // Idempotent-POST contract (ENGINEERING-STANDARDS §1.1): fresh → 201 + Location; replay → 200.
    if (!result.created()) {
      return ResponseEntity.ok(body);
    }
    return ResponseEntity.created(URI.create("/api/v1/platform-settlements/" + body.id()))
        .body(body);
  }

  @Operation(summary = "Every channel's outstanding platform receivable (may be negative)")
  @GetMapping("/outstanding")
  public List<PlatformOutstandingResponse> outstanding() {
    return writer.outstanding();
  }

  @Operation(summary = "Settlement history, most recent first (optionally filtered by channel)")
  @GetMapping
  public List<PlatformSettlementResponse> history(
      @RequestParam(value = "channelCode", required = false) String channelCode) {
    return writer.history(channelCode);
  }
}
