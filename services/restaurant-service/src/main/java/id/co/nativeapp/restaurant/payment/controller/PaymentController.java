package id.co.nativeapp.restaurant.payment.controller;

import id.co.nativeapp.restaurant.payment.dto.PaymentResponse;
import id.co.nativeapp.restaurant.payment.service.PaymentCaptureService;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Payment endpoints (ADR 0006, slice 3).
 *
 * <ul>
 *   <li>{@code POST /api/v1/payments/{id}/capture} — captures a PENDING digital payment, records
 *       revenue ({@code SaleRecorded}), and transitions the order to {@code COMPLETED}.
 *   <li>{@code GET /api/v1/payments/{id}/receipt} — returns the read-path view for a payment (the
 *       receipt shown to the customer / operator after a capture or cash checkout).
 * </ul>
 *
 * <p>The tenant ({@code company_id}) and actor come from the bound {@link
 * id.co.nativeapp.tenant.TenantContext TenantContext}, never from the URL or body (rule 5).
 */
@RestController
@RequestMapping("/api/v1/payments")
public class PaymentController {

  private final PaymentCaptureService captureService;

  public PaymentController(PaymentCaptureService captureService) {
    this.captureService = captureService;
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
}
