package id.co.nativeapp.finance.platform.controller;

import id.co.nativeapp.finance.platform.domain.SettlementAllocation.SourceLine;
import id.co.nativeapp.finance.platform.domain.SettlementSourceKind;
import id.co.nativeapp.finance.platform.dto.OverdueSourceResponse;
import id.co.nativeapp.finance.platform.dto.PayoutSourceResponse;
import id.co.nativeapp.finance.platform.dto.PlatformOutstandingResponse;
import id.co.nativeapp.finance.platform.dto.PlatformSettlementResponse;
import id.co.nativeapp.finance.platform.dto.PlatformSettlementResult;
import id.co.nativeapp.finance.platform.dto.PlatformSettlementSummaryResponse;
import id.co.nativeapp.finance.platform.dto.SettlePayoutRequest;
import id.co.nativeapp.finance.platform.dto.SettlePlatformRequest;
import id.co.nativeapp.finance.platform.service.PlatformSettlementWriter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import java.net.URI;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
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
@Validated
public class PlatformSettlementController {

  private final PlatformSettlementWriter writer;

  public PlatformSettlementController(PlatformSettlementWriter writer) {
    this.writer = writer;
  }

  @Operation(
      summary =
          "Payers whose money has sat past its usual payout cycle — what the Beranda nudge shows."
              + " Driven by cadence, not by the calendar: a daily prompt would mostly fire on days"
              + " nothing is due")
  @GetMapping("/overdue")
  public List<OverdueSourceResponse> overdue() {
    return writer.overdueSources();
  }

  @Operation(
      summary =
          "What each payer still owes, with the sources making it up — grouped by payer because"
              + " that is how the money arrives (one Shopee transfer covers ShopeeFood and its"
              + " counter QRIS)")
  @GetMapping("/sources")
  public List<PayoutSourceResponse> sources() {
    return writer.payoutSources();
  }

  @Operation(
      summary =
          "Record ONE payout covering every source the payer settled — enter the net that reached"
              + " the bank once; the deduction is derived and split across the lines pro-rata, each"
              + " share booked to its own fee account")
  @PostMapping("/payouts")
  public ResponseEntity<PlatformSettlementResponse> settlePayout(
      @Valid @RequestBody SettlePayoutRequest request,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
    // The key is REQUIRED (the payroll/AR/assets idiom): a payout posts money, so a keyless
    // request — which a retry could silently double-book — is rejected with 400.
    requireIdempotencyKey(idempotencyKey);

    PlatformSettlementResult result =
        writer.settleSources(
            request.sourceCode(),
            request.lines().stream()
                .map(
                    l ->
                        new SourceLine(
                            SettlementSourceKind.valueOf(l.sourceKind()),
                            l.channelCode(),
                            l.grossMinor()))
                .toList(),
            request.netMinor(),
            request.currency(),
            idempotencyKey);

    PlatformSettlementResponse body = PlatformSettlementResponse.from(result.settlement());
    return result.created()
        ? ResponseEntity.created(URI.create("/api/v1/platform-settlements/" + body.id())).body(body)
        : ResponseEntity.ok(body);
  }

  /** A money-posting request without a key could double-book on retry (400, never a silent post). */
  private static void requireIdempotencyKey(String idempotencyKey) {
    if (idempotencyKey == null || idempotencyKey.isBlank()) {
      throw new IllegalArgumentException(
          "the Idempotency-Key header is required to record a platform settlement");
    }
    if (idempotencyKey.length() > 64) {
      throw new IllegalArgumentException("the Idempotency-Key header must be at most 64 chars");
    }
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

  /**
   * The per-channel settlement summary for a {@code YYYY-MM} period — one row per {@code
   * (channelCode, currency)} bucket: settled gross/fee/net totals and settlement count. READ-ONLY,
   * tenant-scoped via RLS (rule 5). Empty list when the period has no settlements.
   */
  @Operation(
      summary = "Per-channel platform-settlement summary for a period",
      description =
          "Returns the per-channel settlement summary for the given YYYY-MM period: settled"
              + " gross/fee/net totals and settlement count per (channelCode, currency) bucket."
              + " Empty list when the period has no settlements.")
  @GetMapping("/summary")
  public List<PlatformSettlementSummaryResponse> summary(
      @RequestParam
          @Pattern(
              regexp = "\\d{4}-(0[1-9]|1[0-2])",
              message = "period must be a valid YYYY-MM month")
          String period) {
    return writer.summary(period);
  }
}
