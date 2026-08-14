package id.co.nativeapp.restaurant.bill.service;

import id.co.nativeapp.restaurant.bill.repository.BillLineRepository;
import id.co.nativeapp.restaurant.outletref.service.OutletAccessGuard;
import id.co.nativeapp.restaurant.payment.domain.Payment;
import id.co.nativeapp.restaurant.payment.dto.PaymentResponse;
import id.co.nativeapp.restaurant.payment.repository.PaymentRepository;
import id.co.nativeapp.tenant.RlsAutoApplyAspect;
import id.co.nativeapp.tenant.TenantContext;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the {@code @Transactional} unit of work that abandons a PENDING bill gateway payment (V38):
 * {@link Payment.Status#PENDING} → {@link Payment.Status#ABANDONED}, releasing its {@code
 * bill_line} reservation so a cash/manual payment can claim those lines again. Never produces
 * revenue (ADR 0006 invariant, extended to bills).
 *
 * <p>A distinct bean so each transactional method is invoked through the Spring proxy — the {@code
 * OrderWriter}/{@code BillWriter} pattern (self-invocation would bypass both the
 * {@code @Transactional} advice and the {@link RlsAutoApplyAspect} that sets the tenant GUC).
 *
 * <p>Exposes two entry points sharing the same core logic:
 *
 * <ul>
 *   <li>{@link #abandon} — {@code REQUIRES_NEW}, its own transaction — the standalone {@code POST
 *       /api/v1/payments/{id}/abandon} endpoint.
 *   <li>{@link #abandonInCurrentTx} — {@code MANDATORY}, joins the caller's transaction — {@code
 *       BillWriter.initiatePendingPayment}'s self-heal of a stale prior attempt, so the abandon and
 *       the fresh mint commit (or roll back) atomically.
 * </ul>
 */
@Component
public class BillPaymentWriter {

  private final PaymentRepository paymentRepository;
  private final BillLineRepository lineRepository;
  private final OutletAccessGuard outletAccessGuard;

  public BillPaymentWriter(
      PaymentRepository paymentRepository,
      BillLineRepository lineRepository,
      OutletAccessGuard outletAccessGuard) {
    this.paymentRepository = paymentRepository;
    this.lineRepository = lineRepository;
    this.outletAccessGuard = outletAccessGuard;
  }

  /**
   * Abandons a PENDING bill gateway payment in its own {@code REQUIRES_NEW} transaction.
   *
   * @throws IllegalArgumentException if the payment is not found or is not a bill payment
   * @throws IllegalStateException if the payment is not currently PENDING
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public PaymentResponse abandon(UUID paymentId) {
    return doAbandon(paymentId);
  }

  /**
   * Abandons a PENDING bill gateway payment by joining the caller's transaction (propagation {@code
   * MANDATORY}) — used by {@code BillWriter.initiatePendingPayment}'s self-heal so the abandon and
   * the freshly-minted replacement payment commit atomically.
   *
   * @throws IllegalArgumentException if the payment is not found or is not a bill payment
   * @throws IllegalStateException if the payment is not currently PENDING
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public PaymentResponse abandonInCurrentTx(UUID paymentId) {
    return doAbandon(paymentId);
  }

  private PaymentResponse doAbandon(UUID paymentId) {
    String actor = TenantContext.require().actor();

    // Load the full aggregate (write path).
    Payment payment =
        paymentRepository
            .findById(paymentId)
            .orElseThrow(() -> new IllegalArgumentException("Payment not found: " + paymentId));

    if (!payment.isForBill()) {
      throw new IllegalArgumentException(
          "Payment " + paymentId + " is not a bill payment; use the order void/refund endpoints");
    }

    // MEDIUM fix (code review): enforce outlet access against the PAYMENT's own outlet before
    // touching it — without this, a cashier at outlet X could abandon outlet Y's reservation
    // (cross-outlet grief, or a window for a double-charge if the released lines then get paid
    // some other way while the original QRIS payment is still live). Mirrors payBill/
    // initiatePendingPayment's "Phase 5 enforcement at the money moment" guard.
    outletAccessGuard.enforce(payment.getBusinessId());

    // Payment#abandon enforces the PENDING guard (throws IllegalStateException otherwise).
    payment.abandon();
    paymentRepository.saveAndFlush(payment);

    // Release the reservation so the lines are payable again (cash, or a fresh gateway attempt).
    lineRepository.releaseReservation(paymentId, actor);

    return PaymentResponse.from(payment);
  }
}
