package id.co.nativeapp.barbershop.ticket.service;

import id.co.nativeapp.barbershop.payment.projection.BarbershopPaymentView;
import id.co.nativeapp.barbershop.ticket.domain.ItemType;
import id.co.nativeapp.barbershop.ticket.dto.PriceBreakdownResponse;
import id.co.nativeapp.barbershop.ticket.dto.TicketLineResponse;
import id.co.nativeapp.barbershop.ticket.dto.TicketPaymentResponse;
import id.co.nativeapp.barbershop.ticket.dto.TicketResponse;
import id.co.nativeapp.barbershop.ticket.projection.TicketLineView;
import id.co.nativeapp.barbershop.ticket.projection.TicketView;
import java.util.List;

/**
 * Maps the read-path projections ({@link TicketView}, {@link TicketLineView}, {@link
 * BarbershopPaymentView}) to the {@link TicketResponse} shape. Shared by {@link TicketWriter} (the
 * idempotency fast path and the post-write re-read that builds its response), {@link
 * TicketCaptureWriter}, and {@link TicketReader} so the three call sites cannot diverge — mirrors
 * carwash-service's {@code TicketResponseFactory}.
 *
 * <p><strong>Why the write path re-reads via projections instead of mapping the in-memory
 * entities.</strong> A ticket response is assembled from three tables (the ticket header, its
 * lines, its payment) plus a LEFT JOIN for the barber's display label — exactly the shape the
 * native projection queries already produce. Re-reading (after an explicit flush makes the writes
 * visible on the same connection/transaction) avoids a second, parallel "from-entities" mapping
 * that could drift from the read path.
 *
 * <p>Not a Spring bean — a stateless mapping utility, package-visible to the {@code ticket.service}
 * layer only.
 */
final class TicketResponseFactory {

  private TicketResponseFactory() {
    // static holder
  }

  /**
   * Builds a {@link TicketResponse} from the ticket header view, its lines, and its payment (which
   * may be {@code null} — never true in practice today, since checkout always persists exactly one
   * payment, but the shape allows for it).
   */
  static TicketResponse toResponse(
      TicketView view, List<TicketLineView> lines, BarbershopPaymentView payment) {
    List<TicketLineResponse> lineResponses =
        lines.stream().map(TicketResponseFactory::toLine).toList();
    PriceBreakdownResponse breakdown =
        new PriceBreakdownResponse(
            view.getSubtotalMinor(),
            view.getDiscountMinor(),
            view.getServiceChargeMinor(),
            view.getTaxMinor(),
            view.getTotalMinor(),
            view.getCurrency().strip(),
            view.isUsesIllustrativeRules());
    TicketPaymentResponse paymentResponse =
        payment == null ? null : toPayment(payment, view.getSaleId());
    return new TicketResponse(
        view.getId(),
        view.getBusinessId(),
        view.getChair(),
        view.getStaffProfileId(),
        view.getStaffLabel(),
        view.getSaleId(),
        breakdown,
        view.getOccurredAt(),
        lineResponses,
        paymentResponse);
  }

  private static TicketLineResponse toLine(TicketLineView view) {
    return new TicketLineResponse(
        ItemType.valueOf(view.getItemType()),
        view.getItemId(),
        view.getName(),
        view.getPriceMinor(),
        view.getCurrency().strip(),
        view.getQty());
  }

  /**
   * {@code barbershop_payment} carries no {@code sale_id} column of its own (unlike restaurant's
   * payment aggregate) — the ticket-level {@code saleId} is the single source of truth, so it is
   * threaded in from the caller rather than read off the payment view.
   */
  private static TicketPaymentResponse toPayment(
      BarbershopPaymentView view, java.util.UUID saleId) {
    return new TicketPaymentResponse(
        view.getId(),
        view.getTicketId(),
        view.getTenderType(),
        view.getStatus(),
        view.getAmountMinor(),
        view.getCurrency().strip(),
        view.getTenderedMinor(),
        view.getChangeMinor(),
        view.isProviderPending(),
        saleId);
  }
}
