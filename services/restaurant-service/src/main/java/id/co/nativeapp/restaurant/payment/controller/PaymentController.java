package id.co.nativeapp.restaurant.payment.controller;

import id.co.nativeapp.money.Money;
import id.co.nativeapp.restaurant.payment.dto.PaymentResponse;
import id.co.nativeapp.restaurant.payment.dto.RefundRequest;
import id.co.nativeapp.restaurant.payment.service.PaymentCaptureService;
import id.co.nativeapp.restaurant.payment.service.VoidRefundService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Payment endpoints (ADR 0006, slices 3–4).
 *
 * <ul>
 *   <li>{@code POST /api/v1/payments/{id}/capture} — captures a PENDING digital payment, records
 *       revenue ({@code SaleRecorded}), and transitions the order to {@code COMPLETED}.
 *   <li>{@code GET /api/v1/payments/{id}/receipt} — returns the read-path view for a payment.
 *   <li>{@code POST /api/v1/payments/{id}/void} — voids a CAPTURED payment; emits {@code
 *       SaleVoided} for the finance reversal (ADR 0006, slice 4).
 *   <li>{@code POST /api/v1/payments/{id}/refund} — refunds a CAPTURED payment (partial or full);
 *       emits {@code SaleRefunded} for the finance reversal (ADR 0006, slice 4).
 * </ul>
 *
 * <p>The tenant ({@code company_id}) and actor come from the bound {@link
 * id.co.nativeapp.tenant.TenantContext TenantContext}, never from the URL or body (rule 5).
 */
@RestController
@RequestMapping("/api/v1/payments")
public class PaymentController {

  private final PaymentCaptureService captureService;
  private final VoidRefundService voidRefundService;

  public PaymentController(
      PaymentCaptureService captureService, VoidRefundService voidRefundService) {
    this.captureService = captureService;
    this.voidRefundService = voidRefundService;
  }

  /**
   * Captures a PENDING digital payment, records the Sale and emits {@code SaleRecorded}. Idempotent
   * — a second call for the same {@code id} (already CAPTURED) returns the existing state with
   * {@code 200 OK} and no second event.
   *
   * @param id the payment id (UUID path variable)
   * @return {@code 200 OK} with the captured payment body
   */
  @PostMapping("/{id}/capture")
  public ResponseEntity<PaymentResponse> capture(@PathVariable UUID id) {
    return ResponseEntity.ok(captureService.capture(id));
  }

  /**
   * Returns the read-path receipt for a payment. RLS-scoped — a tenant can only read its own
   * payments.
   *
   * @param id the payment id
   * @return {@code 200 OK} with the payment body
   */
  @GetMapping("/{id}/receipt")
  public ResponseEntity<PaymentResponse> receipt(@PathVariable UUID id) {
    return ResponseEntity.ok(captureService.getReceipt(id));
  }

  /**
   * Voids a CAPTURED payment — a full reversal before settlement. Emits {@code SaleVoided} so
   * finance can post the reversal contra entry. The payment must be in {@code CAPTURED} status;
   * digital or cash.
   *
   * @param id the payment id
   * @return {@code 200 OK} with the voided payment body (status = VOIDED)
   */
  @PostMapping("/{id}/void")
  public ResponseEntity<PaymentResponse> voidPayment(@PathVariable UUID id) {
    return ResponseEntity.ok(voidRefundService.voidPayment(id));
  }

  /**
   * Refunds part or all of a CAPTURED payment. Emits {@code SaleRefunded} so finance can post a
   * proportional contra entry. The payment must be in {@code CAPTURED} or {@code
   * PARTIALLY_REFUNDED} status; the refund amount must be positive and must not exceed the
   * remaining refundable balance.
   *
   * @param id the payment id
   * @param request the refund amount ({@code amountMinor} in minor units + {@code currency})
   * @return {@code 200 OK} with the updated payment body (status = PARTIALLY_REFUNDED or REFUNDED)
   */
  @PostMapping("/{id}/refund")
  public ResponseEntity<PaymentResponse> refund(
      @PathVariable UUID id, @Valid @RequestBody RefundRequest request) {
    Money refundAmount = Money.ofMinor(request.amountMinor(), request.currency());
    return ResponseEntity.ok(voidRefundService.refund(id, refundAmount));
  }
}
