package id.co.nativeapp.restaurant.payment.service;

import id.co.nativeapp.restaurant.bill.service.BillPaymentCaptureWriter;
import id.co.nativeapp.restaurant.bill.service.BillPaymentWriter;
import id.co.nativeapp.restaurant.payment.dto.PaymentResponse;
import id.co.nativeapp.restaurant.payment.projection.PaymentView;
import id.co.nativeapp.restaurant.payment.repository.PaymentRepository;
import id.co.nativeapp.tenant.TenantContext;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orchestrates digital-payment capture and the read path for payment receipts (ADR 0006, slice 3).
 *
 * <p>Not itself {@code @Transactional} — transactional units live in {@link PaymentCaptureWriter}
 * (order payments), {@link BillPaymentCaptureWriter} (bill payments, V38), {@link
 * BillPaymentWriter} (bill payment abandon, V38), and {@link PaymentCaptureService#getReceipt} (a
 * thin {@code @Transactional readOnly} read). Follows the same {@code SaleService} / {@code
 * OrderService} pattern exactly.
 *
 * <p><strong>Dispatch (V38).</strong> {@link #capture} and {@link #abandon} route to the bill-only
 * writers when the target payment is bill-originated ({@link
 * id.co.nativeapp.restaurant.payment.domain.Payment#isForBill()}) and to the pre-existing
 * order-only {@link PaymentCaptureWriter} otherwise — the order path is byte-identical to before
 * this change.
 *
 * <p>Capture is idempotent: a concurrent second capture for the same payment (same payment id)
 * either finds a CAPTURED payment and returns it without side effects ({@link
 * PaymentCaptureWriter#capture}'s / {@link BillPaymentCaptureWriter#capture}'s re-delivery guard),
 * or one winner races the sale {@code UNIQUE (company_id, idempotency_key)} constraint and the
 * loser catches the conflict and re-reads the committed result — exactly the {@code SaleService}
 * pattern.
 */
@Service
public class PaymentCaptureService {

  private final PaymentCaptureWriter writer;
  private final BillPaymentCaptureWriter billCaptureWriter;
  private final BillPaymentWriter billPaymentWriter;
  private final PaymentDispatchReader dispatchReader;
  private final PaymentRepository repository;

  public PaymentCaptureService(
      PaymentCaptureWriter writer,
      BillPaymentCaptureWriter billCaptureWriter,
      BillPaymentWriter billPaymentWriter,
      PaymentDispatchReader dispatchReader,
      PaymentRepository repository) {
    this.writer = writer;
    this.billCaptureWriter = billCaptureWriter;
    this.billPaymentWriter = billPaymentWriter;
    this.dispatchReader = dispatchReader;
    this.repository = repository;
  }

  /**
   * Captures a PENDING digital payment, records revenue, and emits {@code SaleRecorded}. Dispatches
   * to {@link BillPaymentCaptureWriter} for a bill-originated payment (V38), else to the order-only
   * {@link PaymentCaptureWriter}.
   *
   * <p>Idempotent: a re-delivery (same payment id, already CAPTURED) returns the existing state
   * without writing a second {@code SaleRecorded}. A concurrent collision on the sale's {@code
   * (company_id, idempotency_key)} is caught and recovered via a fresh-transaction re-read.
   *
   * @param paymentId the payment to capture (must be PENDING and visible to the current tenant)
   * @return the captured payment response
   * @throws IllegalArgumentException if the payment is not found or is not a digital tender
   */
  public PaymentResponse capture(UUID paymentId) {
    TenantContext.require();
    try {
      // capturedAt is captured INSIDE the writer, after the CashWindowLock (verified HIGH race
      // fix) — see PaymentCaptureWriter / BillPaymentCaptureWriter class javadoc. The dispatch
      // read goes through PaymentDispatchReader (a distinct, properly @Transactional bean — see
      // its javadoc for why an inline repository call here would silently fail RLS closed); a
      // not-visible payment id 404s here exactly as it would inside either writer's own findById.
      PaymentView view =
          dispatchReader
              .findView(paymentId)
              .orElseThrow(() -> new IllegalArgumentException("Payment not found: " + paymentId));
      return (view.getBillId() != null)
          ? billCaptureWriter.capture(paymentId)
          : writer.capture(paymentId);
    } catch (DataIntegrityViolationException conflict) {
      // A concurrent racer won the sale (company_id, idempotency_key) unique constraint.
      // The create transaction is aborted; re-read the result from a fresh transaction.
      // The payment must now be CAPTURED (the winner committed), so just read the view.
      return getReceipt(paymentId);
    }
  }

  /**
   * Abandons a PENDING bill gateway payment (V38) — PENDING → ABANDONED, releasing its {@code
   * bill_line} reservation. Order payments do not support this operation (they have no reservation
   * to release).
   *
   * @param paymentId the payment to abandon
   * @return the abandoned payment response
   * @throws IllegalArgumentException if the payment is not found or is not a bill payment
   * @throws IllegalStateException if the payment is not currently PENDING
   */
  public PaymentResponse abandon(UUID paymentId) {
    TenantContext.require();
    return billPaymentWriter.abandon(paymentId);
  }

  /**
   * Returns the receipt (read-path view) for a payment. RLS-scoped to the current tenant.
   *
   * @param paymentId the payment id
   * @return the payment response
   * @throws IllegalArgumentException if the payment is not found or not visible to this tenant
   */
  @Transactional(readOnly = true)
  public PaymentResponse getReceipt(UUID paymentId) {
    TenantContext.require();
    PaymentView view =
        repository
            .findViewById(paymentId)
            .orElseThrow(() -> new IllegalArgumentException("Payment not found: " + paymentId));
    return fromView(view);
  }

  /** Maps the read-path projection to the response DTO (currency CHAR(3) — strip padding). */
  static PaymentResponse fromView(PaymentView view) {
    return new PaymentResponse(
        view.getId(),
        view.getOrderId(),
        view.getBillId(),
        view.getTenderType(),
        view.getStatus(),
        view.getAmountMinor(),
        view.getCurrency().strip(),
        view.getTenderedMinor(),
        view.getChangeMinor(),
        view.isProviderPending(),
        view.getSaleId());
  }
}
