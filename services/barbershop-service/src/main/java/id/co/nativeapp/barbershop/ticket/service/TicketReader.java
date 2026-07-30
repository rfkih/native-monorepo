package id.co.nativeapp.barbershop.ticket.service;

import id.co.nativeapp.barbershop.payment.projection.BarbershopPaymentView;
import id.co.nativeapp.barbershop.payment.repository.BarbershopPaymentRepository;
import id.co.nativeapp.barbershop.pricing.domain.PriceBreakdown;
import id.co.nativeapp.barbershop.pricing.service.TaxChargeService;
import id.co.nativeapp.barbershop.ticket.domain.TicketNotFoundException;
import id.co.nativeapp.barbershop.ticket.dto.PriceBreakdownResponse;
import id.co.nativeapp.barbershop.ticket.dto.QuoteRequest;
import id.co.nativeapp.barbershop.ticket.dto.TicketResponse;
import id.co.nativeapp.barbershop.ticket.projection.TicketLineView;
import id.co.nativeapp.barbershop.ticket.projection.TicketView;
import id.co.nativeapp.barbershop.ticket.repository.BarbershopTicketLineRepository;
import id.co.nativeapp.barbershop.ticket.repository.BarbershopTicketRepository;
import id.co.nativeapp.money.Money;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the {@code @Transactional(readOnly = true)} ticket reads: the stateless price quote (no
 * persistence, no side effect) and the {@code GET /{id}} response assembly. A distinct bean from
 * {@link TicketWriter}/{@link TicketCaptureWriter} so it is invoked through the Spring proxy — the
 * tx advice and {@link id.co.nativeapp.tenant.RlsAutoApplyAspect} that binds the tenant GUC only
 * apply through the proxy. Mirrors carwash-service's {@code TicketReader}.
 *
 * <p>{@link #quote} reuses {@link TicketItemReader} — the SAME item-resolution + pricing pipeline
 * {@link TicketWriter#create} uses — so a quote can never diverge from what checkout would actually
 * charge.
 */
@Service
public class TicketReader {

  private final BarbershopTicketRepository ticketRepository;
  private final BarbershopTicketLineRepository lineRepository;
  private final BarbershopPaymentRepository paymentRepository;
  private final TicketItemReader itemResolver;
  private final TaxChargeService taxChargeService;

  public TicketReader(
      BarbershopTicketRepository ticketRepository,
      BarbershopTicketLineRepository lineRepository,
      BarbershopPaymentRepository paymentRepository,
      TicketItemReader itemResolver,
      TaxChargeService taxChargeService) {
    this.ticketRepository = ticketRepository;
    this.lineRepository = lineRepository;
    this.paymentRepository = paymentRepository;
    this.itemResolver = itemResolver;
    this.taxChargeService = taxChargeService;
  }

  /**
   * A stateless price preview for the requested lines — resolves + validates items exactly as
   * checkout would, computes the breakdown, and returns it. No ticket is written; no event emitted.
   */
  @Transactional(readOnly = true)
  public PriceBreakdownResponse quote(QuoteRequest request) {
    TicketItemReader.ResolvedCart cart =
        itemResolver.resolve(request.businessId(), request.lines());
    Money discount =
        request.discountMinor() != null
            ? Money.ofMinor(request.discountMinor(), cart.currencyCode())
            : null;
    PriceBreakdown breakdown =
        taxChargeService.resolve(cart.subtotal(), 0L, discount, Instant.now());
    return new PriceBreakdownResponse(
        breakdown.subtotal().amountMinor(),
        breakdown.discount().amountMinor(),
        breakdown.serviceCharge().amountMinor(),
        breakdown.tax().amountMinor(),
        breakdown.grandTotal().amountMinor(),
        breakdown.grandTotal().currency().getCurrencyCode(),
        breakdown.usesIllustrativeRules());
  }

  /**
   * Fetches a single ticket by id (RLS-scoped) — the full receipt shape (header, lines, payment).
   *
   * @throws TicketNotFoundException if the ticket is unknown or belongs to another tenant (→ 404)
   */
  @Transactional(readOnly = true)
  public TicketResponse getById(UUID ticketId) {
    TicketView view =
        ticketRepository
            .findViewById(ticketId)
            .orElseThrow(() -> new TicketNotFoundException(ticketId));
    List<TicketLineView> lines = lineRepository.findViewsByTicketId(ticketId);
    BarbershopPaymentView payment = paymentRepository.findViewByTicketId(ticketId).orElse(null);
    return TicketResponseFactory.toResponse(view, lines, payment);
  }
}
