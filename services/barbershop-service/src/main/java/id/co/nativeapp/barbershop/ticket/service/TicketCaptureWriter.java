package id.co.nativeapp.barbershop.ticket.service;

import id.co.nativeapp.barbershop.payment.domain.BarbershopPayment;
import id.co.nativeapp.barbershop.payment.projection.BarbershopPaymentView;
import id.co.nativeapp.barbershop.payment.repository.BarbershopPaymentRepository;
import id.co.nativeapp.barbershop.pricing.domain.PriceBreakdown;
import id.co.nativeapp.barbershop.ticket.domain.BarbershopTicket;
import id.co.nativeapp.barbershop.ticket.domain.PaymentNotPendingException;
import id.co.nativeapp.barbershop.ticket.domain.TicketNotFoundException;
import id.co.nativeapp.barbershop.ticket.dto.TicketResponse;
import id.co.nativeapp.barbershop.ticket.projection.TicketLineView;
import id.co.nativeapp.barbershop.ticket.projection.TicketView;
import id.co.nativeapp.barbershop.ticket.repository.BarbershopTicketLineRepository;
import id.co.nativeapp.barbershop.ticket.repository.BarbershopTicketRepository;
import id.co.nativeapp.tenant.TenantContext;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the {@code @Transactional} write unit of work for {@code POST
 * /api/v1/barbershop/tickets/{id}/capture} — the moment a digital (QRIS/CARD) tender's revenue is
 * recognised (mirrors carwash-service's {@code PaymentCaptureWriter} / {@code TicketCaptureWriter},
 * ADR 0023 decision 2).
 *
 * <p><strong>Idempotency.</strong> If the ticket's payment is already {@code CAPTURED}
 * (re-delivery), this returns the current state with NO side effects — no second sale, no second
 * {@code SaleRecorded}, no second metrics. If the payment is not {@code PENDING} (and not already
 * {@code CAPTURED}), it throws {@link PaymentNotPendingException} → {@code 409}.
 *
 * <p>No stock concept in barbershop — unlike restaurant's payment capture, there is nothing else to
 * deduct.
 */
@Component
public class TicketCaptureWriter {

  private final BarbershopTicketRepository ticketRepository;
  private final BarbershopTicketLineRepository lineRepository;
  private final BarbershopPaymentRepository paymentRepository;
  private final TicketEventEmitter eventEmitter;

  public TicketCaptureWriter(
      BarbershopTicketRepository ticketRepository,
      BarbershopTicketLineRepository lineRepository,
      BarbershopPaymentRepository paymentRepository,
      TicketEventEmitter eventEmitter) {
    this.ticketRepository = ticketRepository;
    this.lineRepository = lineRepository;
    this.paymentRepository = paymentRepository;
    this.eventEmitter = eventEmitter;
  }

  /**
   * Captures a {@code PENDING} digital payment: transitions it to {@code CAPTURED} (leaving {@code
   * provider_pending = true} — the flagged-demo marker), links the ticket's sale id, and writes
   * {@code SaleRecorded} + the metric set — all in one transaction.
   *
   * @throws TicketNotFoundException if the ticket is unknown or belongs to another tenant (→ 404)
   * @throws PaymentNotPendingException if the payment is neither {@code CAPTURED} nor {@code
   *     PENDING} (→ 409)
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public TicketResponse capture(UUID ticketId) {
    String companyId = TenantContext.require().companyId();

    BarbershopTicket ticket =
        ticketRepository
            .findById(ticketId)
            .orElseThrow(() -> new TicketNotFoundException(ticketId));

    BarbershopPaymentView paymentView =
        paymentRepository
            .findViewByTicketId(ticketId)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "No payment found for ticket "
                            + ticketId
                            + " — checkout always persists exactly one payment"));

    if ("CAPTURED".equals(paymentView.getStatus())) {
      // Idempotent re-delivery: return the existing state without side effects.
      return assembleResponse(ticketId);
    }
    if (!"PENDING".equals(paymentView.getStatus())) {
      throw new PaymentNotPendingException(ticketId);
    }

    BarbershopPayment payment =
        paymentRepository
            .findById(paymentView.getId())
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "Payment " + paymentView.getId() + " vanished mid-capture"));
    payment.capture();
    paymentRepository.saveAndFlush(payment);

    ticket.linkSale(ticket.getId());
    ticketRepository.saveAndFlush(ticket);

    Instant capturedAt = Instant.now();
    PriceBreakdown breakdown = ticket.toBreakdown();

    eventEmitter.recognizeRevenue(
        ticket,
        breakdown,
        ticket.getBarberEmployeeId(),
        companyId,
        payment.getTenderType().name(),
        capturedAt);

    return assembleResponse(ticketId);
  }

  private TicketResponse assembleResponse(UUID ticketId) {
    TicketView view =
        ticketRepository
            .findViewById(ticketId)
            .orElseThrow(() -> new TicketNotFoundException(ticketId));
    List<TicketLineView> lines = lineRepository.findViewsByTicketId(ticketId);
    BarbershopPaymentView payment = paymentRepository.findViewByTicketId(ticketId).orElse(null);
    return TicketResponseFactory.toResponse(view, lines, payment);
  }
}
