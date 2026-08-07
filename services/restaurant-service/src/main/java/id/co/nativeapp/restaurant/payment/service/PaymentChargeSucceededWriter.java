package id.co.nativeapp.restaurant.payment.service;

import id.co.nativeapp.errorinbox.ErrorInboxWriter;
import id.co.nativeapp.events.ProcessedEventStore;
import id.co.nativeapp.money.Money;
import id.co.nativeapp.restaurant.payment.domain.Payment;
import id.co.nativeapp.restaurant.payment.messaging.PaymentChargeSucceededEvent;
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
 * PaymentChargeSucceeded} event (ADR 0045) to this service's {@code payment} aggregate —
 * idempotently.
 *
 * <p>A distinct bean from {@link PaymentChargeSucceededService} so the method is invoked through
 * the Spring proxy: a self-invocation would bypass the {@code @Transactional} advice and the {@code
 * RlsAutoApplyAspect} that sets the tenant GUC — exactly what makes the RLS-scoped {@link
 * PaymentRepository#findById} see only this tenant's payment (rule 5). The caller binds the tenant
 * from the event's {@code company_id} before invoking this method.
 *
 * <p><strong>Idempotency (rule 3 / HR-3).</strong> The dedupe claim and the business logic below
 * run in ONE transaction: {@link ProcessedEventStore#processOnce} claims the event UUID and runs
 * the handler only on the FIRST delivery, so a re-delivered event is a clean no-op — including a
 * re-delivery that only reached a park (the park is never repeated either).
 *
 * <p><strong>Park, don't drop (ADR 0045).</strong> Every anomaly — an unknown payment id, a payment
 * that is not PENDING/CAPTURED (VOIDED/REFUNDED/etc.), or an amount/currency mismatch — is recorded
 * to the {@code error_log} inbox ({@code libs/error-inbox}) IN THIS SAME TRANSACTION ({@link
 * ErrorInboxWriter#recordInCurrentTx}), so the park and the processed-mark commit atomically, and
 * {@link #handle} then returns normally — it NEVER throws for a business anomaly, so the record is
 * never redelivered to the DLT. Only an infrastructure failure upstream of a park (e.g. the
 * database becoming unreachable) can still fail this transaction and let the container's error
 * handler retry/DLT it.
 *
 * <p><strong>Capture.</strong> A payment found PENDING with a matching amount/currency is captured
 * via the EXISTING {@code PaymentCaptureWriter#capture(java.util.UUID)} (already idempotent, its
 * own {@code REQUIRES_NEW} transaction) — so {@code SaleRecorded} and finance's QRIS GL routing
 * need ZERO change. If capture throws (e.g. {@code InsufficientStockException} — real money has
 * already moved at the PSP), the failure is parked for a human instead of propagated: because
 * capture runs in its OWN {@code REQUIRES_NEW} transaction, its rollback is physically independent
 * of this method's transaction, which stays healthy and can still record the park + processed-mark.
 */
@Component
public class PaymentChargeSucceededWriter {

  /**
   * Only events for this vertical are ours; carwash/barbershop events are skipped (still marked).
   */
  static final String RESTAURANT_VERTICAL = "restaurant";

  static final String UNKNOWN_PAYMENT_SOURCE = "restaurant.payment-charge.unknown-payment";
  static final String STATE_MISMATCH_SOURCE = "restaurant.payment-charge.state-mismatch";
  static final String AMOUNT_MISMATCH_SOURCE = "restaurant.payment-charge.amount-mismatch";
  static final String CAPTURE_FAILED_SOURCE = "restaurant.payment-charge.capture-failed";

  private static final Logger log = LoggerFactory.getLogger(PaymentChargeSucceededWriter.class);

  private final ProcessedEventStore processedEvents;
  private final PaymentRepository paymentRepository;
  private final PaymentCaptureWriter captureWriter;
  private final ErrorInboxWriter errorInboxWriter;

  public PaymentChargeSucceededWriter(
      ProcessedEventStore processedEvents,
      PaymentRepository paymentRepository,
      PaymentCaptureWriter captureWriter,
      ErrorInboxWriter errorInboxWriter) {
    this.processedEvents = processedEvents;
    this.paymentRepository = paymentRepository;
    this.captureWriter = captureWriter;
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
  public boolean apply(PaymentChargeSucceededEvent event) {
    return processedEvents.processOnce(event.eventId(), () -> handle(event));
  }

  private void handle(PaymentChargeSucceededEvent event) {
    if (!RESTAURANT_VERTICAL.equals(event.vertical())) {
      // Not ours (carwash/barbershop) — every vertical consumes the one topic and filters (ADR
      // 0045). Still processed-marked by the enclosing processOnce claim.
      log.debug(
          "Skipped PaymentChargeSucceeded chargeId={} — vertical={} is not ours",
          event.chargeId(),
          event.vertical());
      return;
    }

    Optional<Payment> paymentLookup = paymentRepository.findById(event.paymentId());
    if (paymentLookup.isEmpty()) {
      park(
          UNKNOWN_PAYMENT_SOURCE,
          "PaymentChargeSucceeded chargeId="
              + event.chargeId()
              + " references unknown paymentId="
              + event.paymentId());
      return;
    }
    Payment payment = paymentLookup.get();

    if (payment.getStatus() == Payment.Status.CAPTURED) {
      // The cashier's manual mark-as-paid raced the webhook and already won — harmless by design
      // (ADR 0045). No-op: no second capture, no second SaleRecorded.
      log.debug(
          "PaymentChargeSucceeded chargeId={} paymentId={} already CAPTURED — no-op",
          event.chargeId(),
          event.paymentId());
      return;
    }

    if (payment.getStatus() != Payment.Status.PENDING) {
      park(
          STATE_MISMATCH_SOURCE,
          "PaymentChargeSucceeded chargeId="
              + event.chargeId()
              + " paymentId="
              + event.paymentId()
              + " is "
              + payment.getStatus()
              + ", expected PENDING");
      return;
    }

    Money expected = payment.getAmount();
    if (expected.amountMinor() != event.amountMinor()
        || !expected.currency().getCurrencyCode().equals(event.currency())) {
      park(
          AMOUNT_MISMATCH_SOURCE,
          "PaymentChargeSucceeded chargeId="
              + event.chargeId()
              + " paymentId="
              + event.paymentId()
              + " amount "
              + event.amountMinor()
              + " "
              + event.currency()
              + " does not match payment amount "
              + expected.amountMinor()
              + " "
              + expected.currency().getCurrencyCode());
      return;
    }

    try {
      captureWriter.capture(payment.getId());
    } catch (IllegalArgumentException
        | IllegalStateException
        | id.co.nativeapp.restaurant.menu.domain.InsufficientStockException captureFailure) {
      // Code review W1: DELIBERATELY narrow — only capture's deterministic BUSINESS failures park
      // (unknown payment/order, non-PENDING guard, insufficient stock). A transient infrastructure
      // fault (lock timeout, deadlock, DB blip) propagates instead, so the container's bounded
      // retry gets to re-run the idempotent capture rather than turning a blip into ops toil —
      // the carwash/barbershop consumers' exact posture.
      park(
          CAPTURE_FAILED_SOURCE,
          "PaymentChargeSucceeded chargeId="
              + event.chargeId()
              + " paymentId="
              + event.paymentId()
              + " — capture failed ("
              + captureFailure.getClass().getSimpleName()
              + ": "
              + captureFailure.getMessage()
              + "); real money already moved at the PSP, a human must resolve");
    }
  }

  /**
   * Records {@code message} to the error inbox IN THE CALLER'S transaction ({@link
   * ErrorInboxWriter#recordInCurrentTx}) — the park documents THIS delivery's own outcome, so it
   * must commit (or roll back) atomically with the {@code processed_event} claim (mirrors {@code
   * menu.service.StockDeductionWriter#recordDiscrepancy}). Never throws — the caller always returns
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
