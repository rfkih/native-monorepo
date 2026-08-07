package id.co.nativeapp.barbershop.payment.service;

import id.co.nativeapp.barbershop.payment.messaging.PaymentChargeSucceededEvent;
import id.co.nativeapp.barbershop.payment.projection.BarbershopPaymentView;
import id.co.nativeapp.barbershop.payment.repository.BarbershopPaymentRepository;
import id.co.nativeapp.barbershop.ticket.domain.PaymentNotPendingException;
import id.co.nativeapp.barbershop.ticket.domain.TicketNotFoundException;
import id.co.nativeapp.barbershop.ticket.service.TicketCaptureWriter;
import id.co.nativeapp.errorinbox.ErrorInboxWriter;
import id.co.nativeapp.events.ProcessedEventStore;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the single {@code @Transactional} unit of work that applies a consumed {@code
 * PaymentChargeSucceeded} (ADR 0045) to the barbershop ticket it settles — idempotently. Mirrors
 * carwash-service's {@code PaymentChargeSucceededWriter} EXACTLY.
 *
 * <p>It is a distinct bean (not a private method on {@link PaymentChargeSucceededService}) so the
 * method is invoked through the Spring proxy: a self-invocation would bypass the
 * {@code @Transactional} advice and the {@link id.co.nativeapp.tenant.RlsAutoApplyAspect} that sets
 * the tenant GUC, and the GUC is exactly what makes the RLS-scoped {@link
 * BarbershopPaymentRepository#findViewByTicketId} read (and the capture writer's own writes)
 * resolve under the right tenant (rule 5). The caller ({@link PaymentChargeSucceededService}) binds
 * the tenant from the event's {@code company_id} before invoking this method.
 *
 * <p><strong>Idempotency (rule 3 / HR-3).</strong> The dedupe claim and the entire business
 * decision — skip / park / capture — run in ONE transaction: {@link
 * ProcessedEventStore#processOnce} claims the event UUID and runs the handler only on the FIRST
 * delivery, so a re-delivered {@code PaymentChargeSucceeded} (same event id) is a clean no-op.
 *
 * <p><strong>Park-don't-drop (ADR 0005/0009).</strong> A business anomaly (wrong vertical is a
 * silent skip, not an anomaly; missing/unknown ticket, an unexpected payment state, an amount/
 * currency mismatch, or a business exception surfaced by {@link TicketCaptureWriter#capture}) is
 * recorded to the error-log inbox via {@link ErrorInboxWriter#record} (its OWN {@code REQUIRES_NEW}
 * transaction, mirroring {@code revenue.service.RevenuePostingWriter}'s sealed-period-quarantine
 * precedent in finance-service) and the handler returns normally — so the {@code processOnce} claim
 * commits and the event is never retried. Money already moved at the PSP; a human resolves the
 * anomaly. Only an INFRASTRUCTURE failure (e.g. the DB is unreachable) propagates out of this
 * method, rolling back the whole transaction (including the dedupe claim) and letting the
 * container's bounded-retry-then-DLT error handler ({@code config.KafkaConfig}) take over.
 */
@Component
public class PaymentChargeSucceededWriter {

  /**
   * The only vertical this writer acts on; every other value is a silent, processed-marked skip.
   */
  static final String VERTICAL = "barbershop";

  static final String MISSING_REFERENCE_SOURCE = "barbershop.payment-charge.missing-reference";
  static final String UNKNOWN_TICKET_SOURCE = "barbershop.payment-charge.unknown-ticket";
  static final String STATE_MISMATCH_SOURCE = "barbershop.payment-charge.state-mismatch";
  static final String AMOUNT_MISMATCH_SOURCE = "barbershop.payment-charge.amount-mismatch";
  static final String CAPTURE_FAILED_SOURCE = "barbershop.payment-charge.capture-failed";

  private static final String STATUS_CAPTURED = "CAPTURED";
  private static final String STATUS_PENDING = "PENDING";

  private static final Logger log = LoggerFactory.getLogger(PaymentChargeSucceededWriter.class);

  private final BarbershopPaymentRepository paymentRepository;
  private final TicketCaptureWriter ticketCaptureWriter;
  private final ProcessedEventStore processedEvents;
  private final ErrorInboxWriter errorInboxWriter;

  public PaymentChargeSucceededWriter(
      BarbershopPaymentRepository paymentRepository,
      TicketCaptureWriter ticketCaptureWriter,
      ProcessedEventStore processedEvents,
      ErrorInboxWriter errorInboxWriter) {
    this.paymentRepository = paymentRepository;
    this.ticketCaptureWriter = ticketCaptureWriter;
    this.processedEvents = processedEvents;
    this.errorInboxWriter = errorInboxWriter;
  }

  /**
   * Applies the event exactly once per event id. Must be called inside a {@link
   * id.co.nativeapp.tenant.TenantContext} scope bound to the event's {@code company_id}.
   *
   * @return {@code true} if this delivery applied (first delivery), {@code false} if skipped as a
   *     duplicate (re-delivery)
   */
  @Transactional
  public boolean apply(PaymentChargeSucceededEvent event) {
    return processedEvents.processOnce(event.eventId(), () -> handle(event));
  }

  private void handle(PaymentChargeSucceededEvent event) {
    if (!VERTICAL.equals(event.vertical())) {
      log.debug(
          "Skipped PaymentChargeSucceeded eventId={} chargeId={} — vertical={} is not barbershop",
          event.eventId(),
          event.chargeId(),
          event.vertical());
      return;
    }

    if (event.referenceId() == null) {
      park(
          event,
          MISSING_REFERENCE_SOURCE,
          "PaymentChargeSucceeded chargeId="
              + event.chargeId()
              + " carries no reference_id (ticket id) — barbershop captures by ticket id and"
              + " cannot proceed");
      return;
    }

    UUID ticketId = event.referenceId();
    Optional<BarbershopPaymentView> paymentView = paymentRepository.findViewByTicketId(ticketId);
    if (paymentView.isEmpty()) {
      park(
          event,
          UNKNOWN_TICKET_SOURCE,
          "PaymentChargeSucceeded chargeId="
              + event.chargeId()
              + " references unknown ticket "
              + ticketId);
      return;
    }

    BarbershopPaymentView view = paymentView.get();
    if (STATUS_CAPTURED.equals(view.getStatus())) {
      // The cashier's manual mark-as-paid raced the webhook — harmless, no-op.
      log.debug(
          "PaymentChargeSucceeded eventId={} chargeId={} ticket={} already CAPTURED — no-op",
          event.eventId(),
          event.chargeId(),
          ticketId);
      return;
    }
    if (!STATUS_PENDING.equals(view.getStatus())) {
      park(
          event,
          STATE_MISMATCH_SOURCE,
          "PaymentChargeSucceeded chargeId="
              + event.chargeId()
              + " ticket="
              + ticketId
              + " payment status="
              + view.getStatus()
              + " is neither PENDING nor CAPTURED — never captures");
      return;
    }

    if (view.getAmountMinor() != event.amountMinor()
        || !view.getCurrency().strip().equals(event.currency())) {
      park(
          event,
          AMOUNT_MISMATCH_SOURCE,
          "PaymentChargeSucceeded chargeId="
              + event.chargeId()
              + " ticket="
              + ticketId
              + " amount "
              + event.amountMinor()
              + " "
              + event.currency()
              + " does not match the payment's "
              + view.getAmountMinor()
              + " "
              + view.getCurrency().strip());
      return;
    }

    try {
      ticketCaptureWriter.capture(ticketId);
    } catch (TicketNotFoundException | PaymentNotPendingException captureFailure) {
      // Money already moved at the PSP; a human resolves. The event is still marked processed —
      // never retried into the same failure.
      park(event, CAPTURE_FAILED_SOURCE, captureFailure);
    }
  }

  private void park(PaymentChargeSucceededEvent event, String source, String message) {
    park(event, source, new IllegalStateException(message));
  }

  private void park(PaymentChargeSucceededEvent event, String source, Exception cause) {
    errorInboxWriter.record(cause, source, event.companyId(), MDC.get("traceId"));
  }
}
