package id.co.nativeapp.restaurant.payment.controller;

import id.co.nativeapp.money.Money;
import id.co.nativeapp.restaurant.payment.dto.PaymentResponse;
import id.co.nativeapp.restaurant.payment.dto.RefundRequest;
import id.co.nativeapp.restaurant.payment.service.PaymentCaptureService;
import id.co.nativeapp.restaurant.payment.service.VoidRefundService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
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
 *       revenue ({@code SaleRecorded}), and transitions the order to {@code COMPLETED} (or, for a
 *       bill-originated payment (V38), the bill to PAID once every line is paid).
 *   <li>{@code GET /api/v1/payments/{id}/receipt} — returns the read-path view for a payment.
 *   <li>{@code POST /api/v1/payments/{id}/void} — voids a CAPTURED payment; emits {@code
 *       SaleVoided} for the finance reversal (ADR 0006, slice 4).
 *   <li>{@code POST /api/v1/payments/{id}/refund} — refunds a CAPTURED payment (partial or full);
 *       emits {@code SaleRefunded} for the finance reversal (ADR 0006, slice 4).
 *   <li>{@code POST /api/v1/payments/{id}/abandon} — abandons a PENDING bill gateway payment (V38),
 *       releasing its line reservation. Bill payments only.
 * </ul>
 *
 * <p>The tenant ({@code company_id}) and actor come from the bound {@link
 * id.co.nativeapp.tenant.TenantContext TenantContext}, never from the URL or body (rule 5).
 */
@Tag(name = "Payments", description = "Payment capture, receipt read, void, and refund (ADR 0006)")
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
  @Operation(
      summary = "Capture a PENDING digital payment",
      description =
          "Captures a PENDING digital payment, records the Sale and emits SaleRecorded. Idempotent"
              + " — a second call for the same id (already CAPTURED) returns the existing state"
              + " with 200 OK and no second event.")
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
  @Operation(
      summary = "Get a payment receipt",
      description =
          "Returns the read-path receipt for a payment. RLS-scoped — a tenant can only read its"
              + " own payments.")
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
  @Operation(
      summary = "Void a CAPTURED payment",
      description =
          "Voids a CAPTURED payment — a full reversal before settlement. Emits SaleVoided so"
              + " finance can post the reversal contra entry. The payment must be in CAPTURED"
              + " status; digital or cash.")
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
  @Operation(
      summary = "Refund a CAPTURED payment",
      description =
          "Refunds part or all of a CAPTURED payment. Emits SaleRefunded so finance can post a"
              + " proportional contra entry. The payment must be in CAPTURED or PARTIALLY_REFUNDED"
              + " status; the refund amount must not exceed the remaining refundable balance.")
  @PostMapping("/{id}/refund")
  public ResponseEntity<PaymentResponse> refund(
      @PathVariable UUID id, @Valid @RequestBody RefundRequest request) {
    Money refundAmount = Money.ofMinor(request.amountMinor(), request.currency());
    return ResponseEntity.ok(voidRefundService.refund(id, refundAmount));
  }

  /**
   * Abandons a PENDING bill gateway payment (V38, dynamic QRIS/CARD) — releases its {@code
   * bill_line} reservation so the lines become payable again (cash, or a fresh gateway attempt).
   * Bill payments only; an order payment has no reservation to release.
   *
   * @param id the payment id
   * @return {@code 200 OK} with the abandoned payment body (status = ABANDONED)
   */
  @Operation(
      summary = "Abandon a PENDING bill gateway payment",
      description =
          "Abandons a PENDING bill gateway payment (V38) and releases its bill_line reservation."
              + " Bill payments only.")
  @PostMapping("/{id}/abandon")
  public ResponseEntity<PaymentResponse> abandon(@PathVariable UUID id) {
    return ResponseEntity.ok(captureService.abandon(id));
  }
}
