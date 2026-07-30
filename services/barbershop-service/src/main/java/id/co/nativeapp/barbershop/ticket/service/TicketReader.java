package id.co.nativeapp.barbershop.ticket.service;

import id.co.nativeapp.barbershop.payment.projection.BarbershopPaymentView;
import id.co.nativeapp.barbershop.payment.repository.BarbershopPaymentRepository;
import id.co.nativeapp.barbershop.pricing.domain.PriceBreakdown;
import id.co.nativeapp.barbershop.pricing.service.TaxChargeService;
import id.co.nativeapp.barbershop.promotion.dto.EvalInput;
import id.co.nativeapp.barbershop.promotion.dto.EvalLine;
import id.co.nativeapp.barbershop.promotion.dto.EvalResult;
import id.co.nativeapp.barbershop.promotion.service.PromotionEngineService;
import id.co.nativeapp.barbershop.ticket.domain.TicketNotFoundException;
import id.co.nativeapp.barbershop.ticket.dto.AppliedPromotionResponse;
import id.co.nativeapp.barbershop.ticket.dto.PriceBreakdownResponse;
import id.co.nativeapp.barbershop.ticket.dto.QuoteRequest;
import id.co.nativeapp.barbershop.ticket.dto.TicketResponse;
import id.co.nativeapp.barbershop.ticket.projection.TicketLineView;
import id.co.nativeapp.barbershop.ticket.projection.TicketView;
import id.co.nativeapp.barbershop.ticket.repository.BarbershopTicketLineRepository;
import id.co.nativeapp.barbershop.ticket.repository.BarbershopTicketRepository;
import java.time.Instant;
import java.util.ArrayList;
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
 * charge. Phase 3 (ADR 0026): {@link #quote} ALSO evaluates {@link PromotionEngineService} — every
 * currently-effective automatic rule plus the resolved outcome of an optional coupon code — and
 * echoes the per-rule detail on the response. A quote persists nothing, so every {@link EvalLine}
 * carries {@code lineId = null}; a line-scope deduction on the quote response therefore carries a
 * {@code null} {@code lineRef}. NEVER throws for a bad/expired/exhausted coupon code — {@code
 * couponStatus} reports it instead (a quote is only a pricing preview; the atomic redemption never
 * runs in this read-only transaction).
 */
@Service
public class TicketReader {

  private final BarbershopTicketRepository ticketRepository;
  private final BarbershopTicketLineRepository lineRepository;
  private final BarbershopPaymentRepository paymentRepository;
  private final TicketItemReader itemResolver;
  private final TaxChargeService taxChargeService;
  private final PromotionEngineService promotionEngine;

  public TicketReader(
      BarbershopTicketRepository ticketRepository,
      BarbershopTicketLineRepository lineRepository,
      BarbershopPaymentRepository paymentRepository,
      TicketItemReader itemResolver,
      TaxChargeService taxChargeService,
      PromotionEngineService promotionEngine) {
    this.ticketRepository = ticketRepository;
    this.lineRepository = lineRepository;
    this.paymentRepository = paymentRepository;
    this.itemResolver = itemResolver;
    this.taxChargeService = taxChargeService;
    this.promotionEngine = promotionEngine;
  }

  /**
   * A stateless price preview for the requested lines — resolves + validates items exactly as
   * checkout would, evaluates the Phase-3 promotions engine (automatics + at most one coupon), and
   * computes the breakdown. No ticket is written; no event emitted; no coupon is redeemed.
   */
  @Transactional(readOnly = true)
  public PriceBreakdownResponse quote(QuoteRequest request) {
    TicketItemReader.ResolvedCart cart =
        itemResolver.resolve(request.businessId(), request.lines());

    List<EvalLine> evalLines = new ArrayList<>(cart.lines().size());
    for (TicketItemReader.ResolvedLine rl : cart.lines()) {
      evalLines.add(new EvalLine(null, rl.itemId(), null, rl.priceMinor(), rl.qty()));
    }

    Instant now = Instant.now();
    long manualDiscountMinor = (request.discountMinor() != null) ? request.discountMinor() : 0L;
    EvalInput evalInput =
        new EvalInput(
            evalLines,
            cart.currencyCode(),
            cart.subtotal(),
            now,
            request.couponCode(),
            manualDiscountMinor);
    EvalResult evalResult = promotionEngine.evaluate(evalInput);

    PriceBreakdown breakdown =
        taxChargeService.resolve(cart.subtotal(), 0L, evalResult.totalDiscount(), now);

    List<AppliedPromotionResponse> applied =
        evalResult.deductions().stream().map(AppliedPromotionResponse::from).toList();
    String couponStatus =
        evalResult.couponOutcome() == null ? null : evalResult.couponOutcome().status().name();

    return new PriceBreakdownResponse(
        breakdown.subtotal().amountMinor(),
        breakdown.discount().amountMinor(),
        breakdown.serviceCharge().amountMinor(),
        breakdown.tax().amountMinor(),
        breakdown.grandTotal().amountMinor(),
        breakdown.grandTotal().currency().getCurrencyCode(),
        breakdown.usesIllustrativeRules(),
        applied,
        couponStatus);
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
