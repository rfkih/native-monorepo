package id.co.nativeapp.payment.charge.controller;

import id.co.nativeapp.payment.charge.dto.ChargeResponse;
import id.co.nativeapp.payment.charge.dto.CreateChargeRequest;
import id.co.nativeapp.payment.charge.service.ChargeService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The dynamic-QRIS charge surface (ADR 0045) — POS roles at the gateway: the till creates the
 * charge for its own PENDING payment, polls it while the QR is on screen, and can sync/cancel.
 * Capture never happens here — it arrives via {@code PaymentChargeSucceeded} in the vertical.
 */
@RestController
@RequestMapping("/api/v1/payment-charges")
public class PaymentChargeController {

  private final ChargeService service;

  public PaymentChargeController(ChargeService service) {
    this.service = service;
  }

  @Operation(summary = "Create (or replay) a dynamic-QRIS charge for a PENDING vertical payment")
  @PostMapping
  public ResponseEntity<ChargeResponse> create(
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      @Valid @RequestBody CreateChargeRequest request) {
    ChargeService.CreateResult result = service.create(request, idempotencyKey);
    return ResponseEntity.status(result.fresh() ? HttpStatus.CREATED : HttpStatus.OK)
        .body(result.response());
  }

  @Operation(summary = "Poll a charge (the till's QR panel refresh; lazily expires a stale QR)")
  @GetMapping("/{chargeId}")
  public ChargeResponse get(@PathVariable UUID chargeId) {
    return service.get(chargeId);
  }

  @Operation(summary = "Server-side Midtrans status sync (the no-webhook reconcile fallback)")
  @PostMapping("/{chargeId}/sync")
  public ChargeResponse sync(@PathVariable UUID chargeId) {
    return service.sync(chargeId);
  }

  @Operation(summary = "Cancel a live charge (settles instead if the customer already paid)")
  @PostMapping("/{chargeId}/cancel")
  public ChargeResponse cancel(@PathVariable UUID chargeId) {
    return service.cancel(chargeId);
  }
}
