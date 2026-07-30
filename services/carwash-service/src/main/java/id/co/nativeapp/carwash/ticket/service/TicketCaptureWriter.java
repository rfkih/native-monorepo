package id.co.nativeapp.carwash.ticket.service;

import id.co.nativeapp.carwash.payment.domain.CarwashPayment;
import id.co.nativeapp.carwash.payment.projection.CarwashPaymentView;
import id.co.nativeapp.carwash.payment.repository.CarwashPaymentRepository;
import id.co.nativeapp.carwash.pricing.domain.PriceBreakdown;
import id.co.nativeapp.carwash.ticket.domain.CarwashTicket;
import id.co.nativeapp.carwash.ticket.domain.ItemType;
import id.co.nativeapp.carwash.ticket.domain.PaymentNotPendingException;
import id.co.nativeapp.carwash.ticket.domain.TicketNotFoundException;
import id.co.nativeapp.carwash.ticket.dto.TicketResponse;
import id.co.nativeapp.carwash.ticket.projection.TicketLineView;
import id.co.nativeapp.carwash.ticket.projection.TicketView;
import id.co.nativeapp.carwash.ticket.repository.CarwashTicketLineRepository;
import id.co.nativeapp.carwash.ticket.repository.CarwashTicketRepository;
import id.co.nativeapp.money.Money;
import id.co.nativeapp.tenant.TenantContext;
import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the {@code @Transactional} write unit of work for {@code POST
 * /api/v1/carwash/tickets/{id}/capture} — the moment a digital (QRIS/CARD) tender's revenue is
 * recognised (ADR 0023 decision 2). Mirrors restaurant-service's {@code PaymentCaptureWriter}.
 *
 * <p><strong>Idempotency.</strong> If the ticket's payment is already {@code CAPTURED}
 * (re-delivery), this returns the current state with NO side effects — no second sale, no second
 * {@code SaleRecorded}, no second metrics. If the payment is not {@code PENDING} (and not already
 * {@code CAPTURED}), it throws {@link PaymentNotPendingException} → {@code 409}.
 *
 * <p>No stock concept in carwash — unlike restaurant's payment capture, there is nothing else to
 * deduct.
 */
@Component
public class TicketCaptureWriter {

  private final CarwashTicketRepository ticketRepository;
  private final CarwashTicketLineRepository lineRepository;
  private final CarwashPaymentRepository paymentRepository;
  private final TicketEventEmitter eventEmitter;

  public TicketCaptureWriter(
      CarwashTicketRepository ticketRepository,
      CarwashTicketLineRepository lineRepository,
      CarwashPaymentRepository paymentRepository,
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

    CarwashTicket ticket =
        ticketRepository
            .findById(ticketId)
            .orElseThrow(() -> new TicketNotFoundException(ticketId));

    CarwashPaymentView paymentView =
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

    CarwashPayment payment =
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
    List<TicketLineView> lines = lineRepository.findViewsByTicketId(ticketId);
    Money addonTotal = sumAddonTotal(lines, breakdown.grandTotal().currency().getCurrencyCode());

    eventEmitter.recognizeRevenue(
        ticket,
        breakdown,
        ticket.getWasherEmployeeId(),
        addonTotal,
        companyId,
        payment.getTenderType().name(),
        capturedAt);

    return assembleResponse(ticketId);
  }

  private static Money sumAddonTotal(List<TicketLineView> lines, String currencyCode) {
    Money total = Money.zero(Currency.getInstance(currencyCode));
    for (TicketLineView lv : lines) {
      if (ItemType.ADDON.name().equals(lv.getItemType())) {
        total = total.plus(Money.ofMinor(lv.getPriceMinor(), currencyCode).multiply(lv.getQty()));
      }
    }
    return total;
  }

  private TicketResponse assembleResponse(UUID ticketId) {
    TicketView view =
        ticketRepository
            .findViewById(ticketId)
            .orElseThrow(() -> new TicketNotFoundException(ticketId));
    List<TicketLineView> lines = lineRepository.findViewsByTicketId(ticketId);
    CarwashPaymentView payment = paymentRepository.findViewByTicketId(ticketId).orElse(null);
    return TicketResponseFactory.toResponse(view, lines, payment);
  }
}
