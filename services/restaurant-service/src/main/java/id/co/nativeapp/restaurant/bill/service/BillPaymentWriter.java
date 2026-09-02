package id.co.nativeapp.restaurant.bill.service;

import id.co.nativeapp.restaurant.bill.repository.BillLineRepository;
import id.co.nativeapp.restaurant.bill.repository.BillRepository;
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
  private final BillRepository billRepository;
  private final OutletAccessGuard outletAccessGuard;

  public BillPaymentWriter(
      PaymentRepository paymentRepository,
      BillLineRepository lineRepository,
      BillRepository billRepository,
      OutletAccessGuard outletAccessGuard) {
    this.paymentRepository = paymentRepository;
    this.lineRepository = lineRepository;
    this.billRepository = billRepository;
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
    return doAbandon(paymentId, true);
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
    return doAbandon(paymentId, true);
  }

  /**
   * System-initiated release of a bill's PENDING gateway reservation when its charge DIED — the
   * {@code PaymentChargeExpired} consumer ({@code payment.service.PaymentChargeExpiredWriter})
   * drives this, joining the consumer's transaction (propagation {@code MANDATORY}).
   *
   * <p><strong>Skips the outlet-access guard on purpose.</strong> {@link OutletAccessGuard} scopes
   * a CASHIER's actions to their assigned outlet; this release runs on a Kafka consumer thread from
   * an authenticated payment-service event — a system actor with no operator session and no {@code
   * X-Roles} (so {@code isOwnerOrManager()} is false and the guard would wrongly throw {@code
   * OutletNotAssignedException} for any outlet-scoping tenant). There is no cashier to scope: the
   * charge death is the authority for releasing the hold. Every other step (bill-row lock, fresh
   * PENDING re-check, {@code bill_line → payment} release order) is identical to {@link
   * #abandonInCurrentTx}.
   *
   * @throws IllegalArgumentException if the payment is not found or is not a bill payment
   * @throws IllegalStateException if the payment is not currently PENDING (a benign "already
   *     settled" outcome the caller treats as a no-op)
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public PaymentResponse abandonForExpiredChargeInCurrentTx(UUID paymentId) {
    return doAbandon(paymentId, false);
  }

  private PaymentResponse doAbandon(UUID paymentId, boolean enforceOutlet) {
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
    // initiatePendingPayment's "Phase 5 enforcement at the money moment" guard. Skipped only for
    // the
    // system-initiated expired-charge release, which has no cashier to scope (see
    // abandonForExpiredChargeInCurrentTx).
    if (enforceOutlet) {
      outletAccessGuard.enforce(payment.getBusinessId());
    }

    // ADR 0069: abandon mutates bill_line reservation state, so it MUST serialize on the bill row
    // like every other bill write path (bill → bill_line → payment). Re-entrant for the in-tx
    // self-heal callers, whose transaction already holds this lock.
    billRepository
        .findWithLockById(payment.getBillId())
        .orElseThrow(
            () ->
                new IllegalStateException(
                    "Bill " + payment.getBillId() + " not found for payment " + paymentId));

    // FRESH status re-check UNDER the bill lock (audit-fix review W1): `payment` above was read
    // BEFORE the lock, so a concurrent settle (capture, or another abandon) may have committed in
    // between — the stale in-memory PENDING would pass Payment#abandon's guard and then blow up as
    // an optimistic-lock 500 at flush. A native read sees the committed truth; non-PENDING throws
    // the same IllegalStateException the self-heal callers already treat as the benign
    // "already settled" outcome. While we hold the bill lock no further settle can start.
    String freshStatus =
        paymentRepository
            .findStatusFresh(paymentId)
            .orElseThrow(
                () -> new IllegalStateException("Payment " + paymentId + " no longer exists"));
    if (!Payment.Status.PENDING.name().equals(freshStatus)) {
      throw new IllegalStateException(
          "only a PENDING bill payment can be abandoned; payment "
              + paymentId
              + " is "
              + freshStatus);
    }

    // LOCK ORDER (audit #4 deadlock fix): release the bill_line reservation FIRST, then update the
    // payment row — bill_line → payment, the same canonical order capture takes (bill → bill_line
    // → payment). The previous payment-first order deadlocked against a concurrent webhook capture
    // (each held the other's second lock). Ordering is safe: if the payment turns out not to be
    // PENDING, Payment#abandon below throws and THIS whole transaction — including the release —
    // rolls back; and a captured payment's lines were already re-pointed by
    // markLinesPaidForCapture, so the release UPDATE would have matched zero rows anyway.
    lineRepository.releaseReservation(paymentId, actor);

    // Payment#abandon enforces the PENDING guard (throws IllegalStateException otherwise).
    payment.abandon();
    paymentRepository.saveAndFlush(payment);

    return PaymentResponse.from(payment);
  }
}
