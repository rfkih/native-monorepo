package id.co.nativeapp.restaurant.payment.service;

import id.co.nativeapp.errorinbox.ErrorInboxWriter;
import id.co.nativeapp.events.ProcessedEventStore;
import id.co.nativeapp.restaurant.bill.service.BillPaymentWriter;
import id.co.nativeapp.restaurant.order.domain.Order;
import id.co.nativeapp.restaurant.order.repository.OrderRepository;
import id.co.nativeapp.restaurant.payment.domain.Payment;
import id.co.nativeapp.restaurant.payment.messaging.PaymentChargeExpiredEvent;
import id.co.nativeapp.restaurant.payment.repository.PaymentRepository;
import id.co.nativeapp.tenant.TenantContext;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the single {@code @Transactional} unit of work that applies a consumed {@code
 * PaymentChargeExpired} event (ADR 0045) — the un-happy-path counterpart of {@link
 * PaymentChargeSucceededWriter}. When a dynamic-QRIS gateway charge terminated WITHOUT settling,
 * the PENDING tender restaurant-service was holding for it must be RELEASED so the sale can be paid
 * some other way (cash, or a fresh QR). No money moves; no {@code SaleRecorded}.
 *
 * <p>A distinct bean from {@link PaymentChargeExpiredService} so the method is invoked through the
 * Spring proxy (the {@code @Transactional} advice + the {@code RlsAutoApplyAspect} tenant GUC
 * engage — rule 5). The caller binds the tenant from the event's {@code company_id}.
 *
 * <p><strong>Idempotency (rule 3 / HR-3).</strong> The dedupe claim and the release run in ONE
 * transaction via {@link ProcessedEventStore#processOnce}: a re-delivered event is a clean no-op.
 * The PENDING-status precondition below makes the release naturally idempotent even across the
 * consumer-vs-webhook race — a payment already CAPTURED (the cashier's manual mark-as-paid won) or
 * already ABANDONED (a prior delivery released it) is left untouched.
 *
 * <p><strong>Release routing.</strong> A BILL payment is released via {@link
 * BillPaymentWriter#abandonForExpiredChargeInCurrentTx} (bill-row lock, fresh PENDING re-check,
 * {@code bill_line} reservation release, then {@code payment} → ABANDONED — ADR 0069 order). An
 * ORDER payment is released by reverting the order out of {@code AWAITING_PAYMENT} back to {@code
 * PENDING} (it becomes payable again) and abandoning the tender. Neither path recognises revenue,
 * upholding the ADR 0006 revenue-at-capture invariant.
 *
 * <p><strong>Park, don't drop.</strong> A genuine inconsistency (unknown payment, an order the
 * payment points at that is missing, a bill/order state divergence) is recorded to the {@code
 * error_log} inbox IN THIS SAME TRANSACTION and the method returns normally — it never throws for a
 * business anomaly, so the record is not redelivered to the DLT. The EXPECTED benign outcomes (a
 * non-PENDING payment on redelivery/race) are a silent no-op, not a park.
 */
@Component
public class PaymentChargeExpiredWriter {

  /**
   * Only events for this vertical are ours; carwash/barbershop events are skipped (still marked).
   */
  static final String RESTAURANT_VERTICAL = "restaurant";

  /**
   * The order status a digital tender awaits capture in — the only state a release reverts from.
   */
  private static final String AWAITING_PAYMENT = "AWAITING_PAYMENT";

  static final String UNKNOWN_PAYMENT_SOURCE = "restaurant.payment-charge-expired.unknown-payment";
  static final String UNKNOWN_ORDER_SOURCE = "restaurant.payment-charge-expired.unknown-order";
  static final String RELEASE_FAILED_SOURCE = "restaurant.payment-charge-expired.release-failed";

  private static final Logger log = LoggerFactory.getLogger(PaymentChargeExpiredWriter.class);

  private final ProcessedEventStore processedEvents;
  private final PaymentRepository paymentRepository;
  private final OrderRepository orderRepository;
  private final BillPaymentWriter billPaymentWriter;
  private final ErrorInboxWriter errorInboxWriter;

  public PaymentChargeExpiredWriter(
      ProcessedEventStore processedEvents,
      PaymentRepository paymentRepository,
      OrderRepository orderRepository,
      BillPaymentWriter billPaymentWriter,
      ErrorInboxWriter errorInboxWriter) {
    this.processedEvents = processedEvents;
    this.paymentRepository = paymentRepository;
    this.orderRepository = orderRepository;
    this.billPaymentWriter = billPaymentWriter;
    this.errorInboxWriter = errorInboxWriter;
  }

  /**
   * Applies the event exactly once per event id. Must be called inside a {@link TenantContext}
   * scope bound to the event's {@code company_id}.
   *
   * @return {@code true} if this delivery applied (first delivery), {@code false} if skipped as a
   *     duplicate (re-delivery)
   */
  @Transactional
  public boolean apply(PaymentChargeExpiredEvent event) {
    return processedEvents.processOnce(event.eventId(), () -> handle(event));
  }

  private void handle(PaymentChargeExpiredEvent event) {
    if (!RESTAURANT_VERTICAL.equals(event.vertical())) {
      // Not ours (carwash/barbershop) — every vertical consumes the one topic and filters (ADR
      // 0045). Still processed-marked by the enclosing processOnce claim.
      log.debug(
          "Skipped PaymentChargeExpired chargeId={} — vertical={} is not ours",
          event.chargeId(),
          event.vertical());
      return;
    }

    Optional<Payment> paymentLookup = paymentRepository.findById(event.paymentId());
    if (paymentLookup.isEmpty()) {
      // A restaurant-vertical expiry whose payment we do not have is a genuine inconsistency —
      // surface it. No money moved (nothing to release), so it is a low-stakes park.
      park(
          UNKNOWN_PAYMENT_SOURCE,
          "PaymentChargeExpired chargeId="
              + event.chargeId()
              + " (reason="
              + event.reason()
              + ") references unknown paymentId="
              + event.paymentId());
      return;
    }
    Payment payment = paymentLookup.get();

    if (payment.getStatus() != Payment.Status.PENDING) {
      // The EXPECTED benign outcome: the cashier's manual mark-as-paid already CAPTURED it (races
      // the dying charge harmlessly), or a prior delivery already ABANDONED it. Nothing to release.
      log.debug(
          "PaymentChargeExpired chargeId={} paymentId={} is {} (not PENDING) — no-op",
          event.chargeId(),
          event.paymentId(),
          payment.getStatus());
      return;
    }

    if (payment.isForBill()) {
      // Bill path: release the bill_line reservation + abandon, under the bill-row lock (ADR 0069),
      // via the system-initiated variant (no cashier to outlet-scope). A concurrent settle that won
      // under the lock surfaces as IllegalStateException — the benign "already settled" no-op.
      try {
        billPaymentWriter.abandonForExpiredChargeInCurrentTx(payment.getId());
      } catch (IllegalStateException alreadySettled) {
        log.debug(
            "PaymentChargeExpired chargeId={} paymentId={} — bill payment already settled under the"
                + " lock, nothing to release ({})",
            event.chargeId(),
            event.paymentId(),
            alreadySettled.getMessage());
      }
      return;
    }

    // Order path: revert the order out of AWAITING_PAYMENT so it is payable again, and abandon the
    // tender. Both mutations commit atomically with the processed-event claim.
    Order order = orderRepository.findById(payment.getOrderId()).orElse(null);
    if (order == null) {
      park(
          UNKNOWN_ORDER_SOURCE,
          "PaymentChargeExpired chargeId="
              + event.chargeId()
              + " paymentId="
              + event.paymentId()
              + " references unknown orderId="
              + payment.getOrderId());
      return;
    }

    // Validate the order state BEFORE any mutation (read-only), so the divergence/park decision
    // does
    // not depend on write ordering. The order was loaded fresh above, so its status is the
    // committed
    // truth at that point.
    if (!AWAITING_PAYMENT.equals(order.getStatus())) {
      // The order is not AWAITING_PAYMENT. Distinguish the benign race from a real divergence with
      // a
      // FRESH read of the payment status: `payment` above was loaded at the top of this method, so
      // a
      // concurrent capture (the cashier's manual mark-as-paid won: payment → CAPTURED, order →
      // COMPLETED) may have committed since — that is a no-op, not a park. Only a payment that is
      // STILL PENDING while its order is not AWAITING_PAYMENT is a genuine divergence for a human.
      String freshStatus =
          paymentRepository.findStatusFresh(payment.getId()).orElse(Payment.Status.PENDING.name());
      if (!Payment.Status.PENDING.name().equals(freshStatus)) {
        log.debug(
            "PaymentChargeExpired chargeId={} paymentId={} — order {} already left AWAITING_PAYMENT"
                + " and the payment is now {} (capture won the race) — no-op",
            event.chargeId(),
            event.paymentId(),
            order.getId(),
            freshStatus);
        return;
      }
      park(
          RELEASE_FAILED_SOURCE,
          "PaymentChargeExpired chargeId="
              + event.chargeId()
              + " paymentId="
              + event.paymentId()
              + " — payment is PENDING but order "
              + order.getId()
              + " is not AWAITING_PAYMENT (status "
              + order.getStatus()
              + ")");
      return;
    }

    // LOCK ORDER (ADR 0069 discipline extended to the order path): write the PAYMENT row first,
    // then
    // the ORDER row — the SAME order PaymentCaptureWriter.capture takes (payment → order). The
    // previous order-first sequencing inverted it against a concurrent manual capture and could
    // deadlock. Safe: the order state was validated read-only above; a concurrent capture that
    // commits in the race window bumps payment.version, so the abandon flush below fails the
    // optimistic check and the whole delivery rolls back + redelivers to a clean no-op.
    payment.abandon(); // PENDING → ABANDONED (no revenue)
    paymentRepository.saveAndFlush(payment);
    order.revertAwaitingToPending(); // AWAITING_PAYMENT → PENDING
    orderRepository.saveAndFlush(order);
    log.info(
        "PaymentChargeExpired chargeId={} released order paymentId={} (order {} → PENDING,"
            + " reason={})",
        event.chargeId(),
        event.paymentId(),
        order.getId(),
        event.reason());
  }

  /**
   * Records {@code message} to the error inbox IN THE CALLER'S transaction ({@link
   * ErrorInboxWriter#recordInCurrentTx}) so the park and the {@code processed_event} claim commit
   * atomically (mirrors {@link PaymentChargeSucceededWriter}). Never throws — the caller returns
   * normally afterward.
   */
  private void park(String source, String message) {
    String companyId = TenantContext.require().companyId();
    String traceId = MDC.get("traceId");
    log.warn("{}: {}", source, message);
    errorInboxWriter.recordInCurrentTx(
        new IllegalStateException(message), source, companyId, traceId);
  }
}
