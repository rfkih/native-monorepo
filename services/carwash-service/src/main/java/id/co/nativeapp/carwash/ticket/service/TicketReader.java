package id.co.nativeapp.carwash.ticket.service;

import id.co.nativeapp.carwash.loyaltyref.repository.GiftCardBalance;
import id.co.nativeapp.carwash.loyaltyref.repository.GiftCardRefRepository;
import id.co.nativeapp.carwash.loyaltyref.repository.MemberBalanceRefRepository;
import id.co.nativeapp.carwash.payment.projection.CarwashPaymentView;
import id.co.nativeapp.carwash.payment.repository.CarwashPaymentRepository;
import id.co.nativeapp.carwash.pricing.domain.PriceBreakdown;
import id.co.nativeapp.carwash.pricing.service.TaxChargeService;
import id.co.nativeapp.carwash.promotion.dto.EvalInput;
import id.co.nativeapp.carwash.promotion.dto.EvalLine;
import id.co.nativeapp.carwash.promotion.dto.EvalResult;
import id.co.nativeapp.carwash.promotion.service.PromotionEngineService;
import id.co.nativeapp.carwash.ticket.domain.TicketNotFoundException;
import id.co.nativeapp.carwash.ticket.dto.AppliedPromotionResponse;
import id.co.nativeapp.carwash.ticket.dto.PriceBreakdownResponse;
import id.co.nativeapp.carwash.ticket.dto.QuoteRequest;
import id.co.nativeapp.carwash.ticket.dto.TicketResponse;
import id.co.nativeapp.carwash.ticket.projection.TicketLineView;
import id.co.nativeapp.carwash.ticket.projection.TicketView;
import id.co.nativeapp.carwash.ticket.repository.CarwashTicketLineRepository;
import id.co.nativeapp.carwash.ticket.repository.CarwashTicketRepository;
import id.co.nativeapp.money.Money;
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
 * apply through the proxy.
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

  private final CarwashTicketRepository ticketRepository;
  private final CarwashTicketLineRepository lineRepository;
  private final CarwashPaymentRepository paymentRepository;
  private final TicketItemReader itemResolver;
  private final TaxChargeService taxChargeService;
  private final PromotionEngineService promotionEngine;
  private final MemberBalanceRefRepository memberBalanceRefRepository;
  private final GiftCardRefRepository giftCardRefRepository;

  @SuppressWarnings("checkstyle:ParameterNumber")
  public TicketReader(
      CarwashTicketRepository ticketRepository,
      CarwashTicketLineRepository lineRepository,
      CarwashPaymentRepository paymentRepository,
      TicketItemReader itemResolver,
      TaxChargeService taxChargeService,
      PromotionEngineService promotionEngine,
      MemberBalanceRefRepository memberBalanceRefRepository,
      GiftCardRefRepository giftCardRefRepository) {
    this.ticketRepository = ticketRepository;
    this.lineRepository = lineRepository;
    this.paymentRepository = paymentRepository;
    this.itemResolver = itemResolver;
    this.taxChargeService = taxChargeService;
    this.promotionEngine = promotionEngine;
    this.memberBalanceRefRepository = memberBalanceRefRepository;
    this.giftCardRefRepository = giftCardRefRepository;
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
    Money promoDiscount = evalResult.totalDiscount();

    // Phase 4 (ADR 0027): PREVIEW a loyalty/gift-card redemption — same clamp math as checkout,
    // but READ-ONLY (no atomic decrement, never throws on an unknown/insufficient member or
    // card). See QuoteRequest javadoc for the documented deviation from checkout's fail-closed 409.
    long loyaltyRedeemedMinor = 0L;
    if (request.loyaltyMemberId() != null
        && request.loyaltyRedeemPoints() != null
        && request.loyaltyRedeemPoints() > 0L) {
      long remainingDeductibleMinor = cart.subtotal().amountMinor() - promoDiscount.amountMinor();
      long cachedBalance =
          memberBalanceRefRepository.findBalance(request.loyaltyMemberId()).orElse(0L);
      loyaltyRedeemedMinor =
          Math.max(
              0L,
              Math.min(
                  request.loyaltyRedeemPoints(),
                  Math.min(Math.max(cachedBalance, 0L), Math.max(remainingDeductibleMinor, 0L))));
    }
    Money combinedDiscount =
        loyaltyRedeemedMinor > 0L
            ? promoDiscount.plus(Money.ofMinor(loyaltyRedeemedMinor, cart.currencyCode()))
            : promoDiscount;

    PriceBreakdown breakdown = taxChargeService.resolve(cart.subtotal(), 0L, combinedDiscount, now);

    long giftCardRedeemedMinor = 0L;
    if (request.giftCardId() != null
        && request.giftCardRedeemMinor() != null
        && request.giftCardRedeemMinor() > 0L) {
      long grandTotalMinor = breakdown.grandTotal().amountMinor();
      giftCardRedeemedMinor =
          giftCardRefRepository
              .findActiveBalance(request.giftCardId())
              .filter(bal -> bal.currency().equalsIgnoreCase(cart.currencyCode()))
              .map(GiftCardBalance::balanceMinor)
              .map(
                  cachedBalance ->
                      Math.max(
                          0L,
                          Math.min(
                              request.giftCardRedeemMinor(),
                              Math.min(
                                  Math.max(cachedBalance, 0L), Math.max(grandTotalMinor, 0L)))))
              .orElse(0L);
    }

    List<AppliedPromotionResponse> applied =
        evalResult.deductions().stream().map(AppliedPromotionResponse::from).toList();
    String couponStatus =
        evalResult.couponOutcome() == null ? null : evalResult.couponOutcome().status().name();
    long promoOnlyDiscountMinor = breakdown.discount().amountMinor() - loyaltyRedeemedMinor;
    long grandTotalMinor = breakdown.grandTotal().amountMinor();

    return new PriceBreakdownResponse(
        breakdown.subtotal().amountMinor(),
        promoOnlyDiscountMinor,
        breakdown.serviceCharge().amountMinor(),
        breakdown.tax().amountMinor(),
        grandTotalMinor,
        breakdown.grandTotal().currency().getCurrencyCode(),
        breakdown.usesIllustrativeRules(),
        applied,
        couponStatus,
        loyaltyRedeemedMinor,
        giftCardRedeemedMinor,
        grandTotalMinor - giftCardRedeemedMinor);
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
    CarwashPaymentView payment = paymentRepository.findViewByTicketId(ticketId).orElse(null);
    return TicketResponseFactory.toResponse(view, lines, payment);
  }
}
