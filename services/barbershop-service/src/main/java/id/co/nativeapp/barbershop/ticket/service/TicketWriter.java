package id.co.nativeapp.barbershop.ticket.service;

import id.co.nativeapp.barbershop.catalog.domain.StaffProfile;
import id.co.nativeapp.barbershop.catalog.repository.StaffProfileRepository;
import id.co.nativeapp.barbershop.loyaltyref.service.LoyaltyRedemptionGuard;
import id.co.nativeapp.barbershop.outletref.service.OutletAccessGuard;
import id.co.nativeapp.barbershop.payment.domain.BarbershopPayment;
import id.co.nativeapp.barbershop.payment.domain.TenderType;
import id.co.nativeapp.barbershop.payment.projection.BarbershopPaymentView;
import id.co.nativeapp.barbershop.payment.repository.BarbershopPaymentRepository;
import id.co.nativeapp.barbershop.payment.service.PaymentInstruction;
import id.co.nativeapp.barbershop.payment.service.PaymentProviderRegistry;
import id.co.nativeapp.barbershop.payment.service.TenderAuthorization;
import id.co.nativeapp.barbershop.pricing.domain.PriceBreakdown;
import id.co.nativeapp.barbershop.pricing.service.TaxChargeService;
import id.co.nativeapp.barbershop.promotion.domain.AppliedPromotion;
import id.co.nativeapp.barbershop.promotion.domain.CouponExhaustedException;
import id.co.nativeapp.barbershop.promotion.domain.CouponInvalidException;
import id.co.nativeapp.barbershop.promotion.dto.AppliedDeduction;
import id.co.nativeapp.barbershop.promotion.dto.CouponOutcome;
import id.co.nativeapp.barbershop.promotion.dto.EvalInput;
import id.co.nativeapp.barbershop.promotion.dto.EvalLine;
import id.co.nativeapp.barbershop.promotion.dto.EvalResult;
import id.co.nativeapp.barbershop.promotion.repository.AppliedPromotionRepository;
import id.co.nativeapp.barbershop.promotion.repository.CouponRepository;
import id.co.nativeapp.barbershop.promotion.service.ManualDiscountGuard;
import id.co.nativeapp.barbershop.promotion.service.PromotionEngineService;
import id.co.nativeapp.barbershop.ticket.domain.BarbershopTicket;
import id.co.nativeapp.barbershop.ticket.domain.BarbershopTicketLine;
import id.co.nativeapp.barbershop.ticket.domain.TicketNotFoundException;
import id.co.nativeapp.barbershop.ticket.dto.CheckoutRequest;
import id.co.nativeapp.barbershop.ticket.dto.CheckoutResult;
import id.co.nativeapp.barbershop.ticket.dto.TicketResponse;
import id.co.nativeapp.barbershop.ticket.projection.TicketLineView;
import id.co.nativeapp.barbershop.ticket.projection.TicketView;
import id.co.nativeapp.barbershop.ticket.repository.BarbershopTicketLineRepository;
import id.co.nativeapp.barbershop.ticket.repository.BarbershopTicketRepository;
import id.co.nativeapp.money.Money;
import id.co.nativeapp.tenant.RlsAutoApplyAspect;
import id.co.nativeapp.tenant.TenantContext;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the {@code @Transactional} write unit of work for barbershop ticket checkout — the carwash
 * {@code TicketWriter} idempotency/orchestration contract, verbatim (ADR 0024).
 *
 * <p>A distinct bean (not private methods on {@link TicketService}) so each transactional method is
 * invoked through the Spring proxy — a self-invocation would bypass the {@code @Transactional}
 * advice and the {@link RlsAutoApplyAspect} that sets the tenant GUC. {@link TicketService} calls
 * {@link #create} and, only on a concurrent-collision conflict, {@link #findExistingByKey} in a
 * <em>separate</em> transaction (a PostgreSQL transaction is poisoned once a constraint fires).
 *
 * <p><strong>Checkout = one atomic unit of work.</strong> {@link #create} runs in its own {@code
 * REQUIRES_NEW} transaction and:
 *
 * <ol>
 *   <li>Idempotency fast path: returns the existing ticket if the key is already present — BEFORE
 *       the outlet guard AND before the promotions engine runs, so a replay never re-evaluates
 *       promotions and never re-redeems a coupon (an idempotent replay of an already-recorded
 *       ticket still returns; only NEW work is rejected — the restaurant {@code OutletGate}
 *       ordering).
 *   <li>{@link OutletAccessGuard#enforce}: the attendant must be assigned to the outlet.
 *   <li>Phase 3 (ADR 0026): a positive manual discount requires owner/manager ({@link
 *       ManualDiscountGuard}).
 *   <li>Resolves + validates the requested lines via {@link TicketItemReader} (server-side pricing
 *       — never trusts a client amount).
 *   <li>Resolves the MANDATORY barber {@link StaffProfile} and snapshots its employee link (ADR
 *       0024 — every cut has a barber; unlike carwash's optional washer, this is never skipped).
 *   <li>Phase 3 (ADR 0026): pre-generates the ticket id and builds the {@link BarbershopTicketLine}
 *       entities (their ids are known immediately, independent of the parent ticket's persisted
 *       state — a plain-UUID FK column, unlike restaurant's JPA-associated {@code OrderLine}) so
 *       the {@link PromotionEngineService} can be evaluated (line-scope matching + {@code
 *       AppliedDeduction.lineRef}) BEFORE the ticket itself is constructed, since the ticket's
 *       constructor needs the already-resolved {@link PriceBreakdown} the engine feeds into.
 *   <li>Resolves the price breakdown via {@link TaxChargeService}, using the promotions engine's
 *       collapsed discount as the fixed discount.
 *   <li>If a coupon was supplied: redeems it ATOMICALLY (409 on exhaustion — the whole transaction
 *       rolls back) and stamps {@code barbershop_ticket.coupon_id}.
 *   <li>Persists the ticket + lines + the {@code applied_promotion} audit rows, then authorizes the
 *       tender via {@link PaymentProviderRegistry} and persists the {@link BarbershopPayment}.
 *   <li>CASH ({@code !tenderType.isDigital()}): links the sale (ticket id = sale id, ADR 0023
 *       decision 2), writes {@code SaleRecorded} + metrics via {@link TicketEventEmitter} — all in
 *       this same transaction (rule 3). Digital (PENDING): no sale id, no events — deferred to
 *       {@link TicketCaptureWriter}; the {@code applied_promotion} rows are written with {@code
 *       sale_id = NULL} and stamped at capture time.
 * </ol>
 */
@Component
public class TicketWriter {

  private final BarbershopTicketRepository ticketRepository;
  private final BarbershopTicketLineRepository lineRepository;
  private final BarbershopPaymentRepository paymentRepository;
  private final StaffProfileRepository staffProfileRepository;
  private final TicketItemReader itemResolver;
  private final TaxChargeService taxChargeService;
  private final PaymentProviderRegistry paymentProviderRegistry;
  private final OutletAccessGuard outletAccessGuard;
  private final TicketEventEmitter eventEmitter;
  private final TicketPostOutboxHook postOutboxHook;
  private final PromotionEngineService promotionEngine;
  private final CouponRepository couponRepository;
  private final AppliedPromotionRepository appliedPromotionRepository;
  private final ManualDiscountGuard manualDiscountGuard;
  private final LoyaltyRedemptionGuard loyaltyRedemptionGuard;

  @SuppressWarnings("checkstyle:ParameterNumber")
  public TicketWriter(
      BarbershopTicketRepository ticketRepository,
      BarbershopTicketLineRepository lineRepository,
      BarbershopPaymentRepository paymentRepository,
      StaffProfileRepository staffProfileRepository,
      TicketItemReader itemResolver,
      TaxChargeService taxChargeService,
      PaymentProviderRegistry paymentProviderRegistry,
      OutletAccessGuard outletAccessGuard,
      TicketEventEmitter eventEmitter,
      TicketPostOutboxHook postOutboxHook,
      PromotionEngineService promotionEngine,
      CouponRepository couponRepository,
      AppliedPromotionRepository appliedPromotionRepository,
      ManualDiscountGuard manualDiscountGuard,
      LoyaltyRedemptionGuard loyaltyRedemptionGuard) {
    this.ticketRepository = ticketRepository;
    this.lineRepository = lineRepository;
    this.paymentRepository = paymentRepository;
    this.staffProfileRepository = staffProfileRepository;
    this.itemResolver = itemResolver;
    this.taxChargeService = taxChargeService;
    this.paymentProviderRegistry = paymentProviderRegistry;
    this.outletAccessGuard = outletAccessGuard;
    this.eventEmitter = eventEmitter;
    this.postOutboxHook = postOutboxHook;
    this.promotionEngine = promotionEngine;
    this.couponRepository = couponRepository;
    this.appliedPromotionRepository = appliedPromotionRepository;
    this.manualDiscountGuard = manualDiscountGuard;
    this.loyaltyRedemptionGuard = loyaltyRedemptionGuard;
  }

  /**
   * Persists a ticket, its lines, and its payment — and, for CASH, the {@code SaleRecorded} +
   * metrics outbox rows — in ONE transaction.
   *
   * @throws org.springframework.dao.DataIntegrityViolationException if a concurrent racer already
   *     inserted the {@code (company_id, idempotency_key)} row
   * @throws id.co.nativeapp.barbershop.promotion.domain.ManualDiscountForbiddenException if {@code
   *     discountMinor > 0} and the caller is not owner/manager
   * @throws CouponInvalidException if {@code couponCode} is supplied and does not resolve to a
   *     usable coupon
   * @throws CouponExhaustedException if {@code couponCode} resolves to a coupon whose redemption
   *     limit is reached
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public CheckoutResult create(CheckoutRequest request) {
    String companyId = TenantContext.require().companyId();

    // Idempotency fast path — BEFORE the outlet guard AND before the promotions engine (restaurant
    // OutletGate ordering: an idempotent replay of an already-recorded ticket still returns; only
    // NEW work is rejected — guard ordering is load-bearing so a replay never re-redeems a coupon).
    Optional<TicketView> existing =
        ticketRepository.findViewByIdempotencyKey(request.idempotencyKey());
    if (existing.isPresent()) {
      return new CheckoutResult(assembleResponse(existing.get().getId()), false);
    }

    outletAccessGuard.enforce(request.businessId());

    // Phase 3 (ADR 0026): a positive manual discount requires owner/manager.
    manualDiscountGuard.enforce(request.discountMinor());

    TicketItemReader.ResolvedCart cart =
        itemResolver.resolve(request.businessId(), request.lines());

    // MANDATORY barber attribution (ADR 0024): staffProfileId is @NotNull at the DTO boundary
    // (400 problem when missing), so this is defensive re-validation, not the primary guard.
    UUID barberEmployeeId = resolveBarber(request.businessId(), request.staffProfileId());

    Instant now = Instant.now();

    // Phase 3 (ADR 0026): pre-generate the ticket id and build the REAL BarbershopTicketLine
    // entities now (their ids are already final, independent of ticket persistence — a plain-UUID
    // FK, unlike restaurant's JPA-associated OrderLine) so the promotions engine sees real
    // lineRefs.
    UUID ticketId = UUID.randomUUID();
    List<BarbershopTicketLine> ticketLines =
        buildLines(ticketId, request.businessId(), cart.lines());

    List<EvalLine> evalLines = new ArrayList<>(ticketLines.size());
    for (BarbershopTicketLine line : ticketLines) {
      evalLines.add(
          new EvalLine(
              line.getId(),
              line.getItemId(),
              null,
              line.getUnitPrice().amountMinor(),
              line.getQty()));
    }

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

    // Phase 4 (ADR 0027): points redemption is a DEDUCTION that EXTENDS the promo discount —
    // clamp+decrement (atomically, in THIS transaction) against the subtotal remaining AFTER the
    // promo discount, then feed the COMBINED figure into TaxChargeService.resolve.
    LoyaltyRedemptionGuard.validatePairing(
        request.loyaltyMemberId(),
        request.loyaltyRedeemPoints(),
        request.giftCardId(),
        request.giftCardRedeemMinor());
    long loyaltyRedeemedMinor = 0L;
    Money combinedDiscount = promoDiscount;
    if (request.loyaltyMemberId() != null) {
      long remainingDeductibleMinor = cart.subtotal().amountMinor() - promoDiscount.amountMinor();
      loyaltyRedeemedMinor =
          loyaltyRedemptionGuard.redeemPoints(
              request.loyaltyMemberId(), request.loyaltyRedeemPoints(), remainingDeductibleMinor);
      if (loyaltyRedeemedMinor > 0L) {
        combinedDiscount =
            promoDiscount.plus(Money.ofMinor(loyaltyRedeemedMinor, cart.currencyCode()));
      }
    }

    PriceBreakdown breakdown = taxChargeService.resolve(cart.subtotal(), 0L, combinedDiscount, now);

    // A zero (or negative) grand total must never record revenue: a fully-discounted or
    // zero-priced cart would otherwise emit a zero-amount SaleRecorded, which the finance journal
    // rejects (a balanced entry needs a positive total). 400 — mirrors carwash review S1.
    if (breakdown.grandTotal().amountMinor() <= 0L) {
      throw new IllegalArgumentException("ticket grand total must be positive");
    }

    // Phase 4 (ADR 0027): gift-card redemption is a TENDER settlement (never a discount), clamped
    // + atomically decremented against the GRAND TOTAL now that tax/service-charge are known.
    long giftCardRedeemedMinor = 0L;
    if (request.giftCardId() != null) {
      giftCardRedeemedMinor =
          loyaltyRedemptionGuard.redeemGiftCard(
              request.giftCardId(),
              request.giftCardRedeemMinor(),
              breakdown.grandTotal().amountMinor(),
              cart.currencyCode());
    }

    // Phase 3 (ADR 0026): coupon redemption — the atomic guard; 0 rows -> 409, whole tx rolls back.
    // Runs AFTER validation so a foreseeable rejection never wastes a redemption slot.
    UUID redeemedCouponId = redeemCouponIfApplicable(evalResult);

    BarbershopTicket ticket =
        new BarbershopTicket(
            ticketId,
            request.businessId(),
            request.chair(),
            request.staffProfileId(),
            barberEmployeeId,
            breakdown,
            now,
            request.idempotencyKey(),
            request.loyaltyMemberId(),
            loyaltyRedeemedMinor > 0L ? loyaltyRedeemedMinor : null,
            loyaltyRedeemedMinor > 0L ? loyaltyRedeemedMinor : null,
            giftCardRedeemedMinor > 0L ? request.giftCardId() : null,
            giftCardRedeemedMinor > 0L ? giftCardRedeemedMinor : null);
    if (redeemedCouponId != null) {
      ticket.attachCoupon(redeemedCouponId);
    }
    ticket.setCompanyId(companyId);
    BarbershopTicket savedTicket = ticketRepository.saveAndFlush(ticket);
    persistLines(ticketLines, companyId);

    // Phase 4 (ADR 0027): the gift-card TENDER settlement reduces WHAT REMAINS for the requested
    // payment method to authorize — never the sale amount. residual == 0 skips the provider
    // entirely (the fully-gift-card-paid contract).
    TenderType tenderType = request.payment().tenderType();
    long grandTotalMinor = breakdown.grandTotal().amountMinor();
    long residualMinor = grandTotalMinor - giftCardRedeemedMinor;
    boolean giftCardFullyCovers = residualMinor == 0L;

    BarbershopPayment payment;
    if (giftCardFullyCovers) {
      Money zero = Money.zero(breakdown.grandTotal().currency());
      payment =
          BarbershopPayment.capturedCash(
              savedTicket.getId(), request.businessId(), zero, zero, zero);
    } else {
      PaymentInstruction instruction =
          new PaymentInstruction(
              savedTicket.getId(),
              tenderType,
              Money.ofMinor(residualMinor, cart.currencyCode()),
              request.payment().tenderedMinor(),
              request.idempotencyKey());
      TenderAuthorization auth =
          paymentProviderRegistry.providerFor(tenderType).authorize(instruction);
      if (!tenderType.isDigital()) {
        Money tendered = Money.ofMinor(request.payment().tenderedMinor(), cart.currencyCode());
        payment =
            BarbershopPayment.capturedCash(
                savedTicket.getId(),
                request.businessId(),
                Money.ofMinor(residualMinor, cart.currencyCode()),
                tendered,
                auth.change());
      } else {
        payment =
            BarbershopPayment.pendingDigital(
                savedTicket.getId(),
                request.businessId(),
                tenderType,
                Money.ofMinor(residualMinor, cart.currencyCode()),
                auth.providerRef());
      }
    }
    payment.setCompanyId(companyId);
    paymentRepository.saveAndFlush(payment);

    boolean isDigitalPending = !giftCardFullyCovers && tenderType.isDigital();
    if (!isDigitalPending) {
      // CASH / gift-card-fully-covers: revenue is recognised now (ADR 0006, preserved by ADR
      // 0023/0024) — the sale id IS the ticket id (decision 2). tender_type on the wire is NULL
      // when the gift card fully covered the sale (ADR 0027 pinned semantics).
      savedTicket.linkSale(savedTicket.getId());
      ticketRepository.saveAndFlush(savedTicket);
      persistAppliedPromotions(savedTicket.getId(), savedTicket.getId(), evalResult, companyId);
      eventEmitter.recognizeRevenue(
          savedTicket,
          breakdown,
          barberEmployeeId,
          companyId,
          giftCardFullyCovers ? null : tenderType.name(),
          now);
      // Test seam: a no-op in production; a test can install a hook that throws here to prove the
      // ticket, its lines, its payment, AND the outbox rows roll back together (atomicity, rule 3).
      postOutboxHook.afterOutboxWrite(savedTicket);
    } else {
      // Digital (PENDING, residual > 0): no sale id, no events — deferred to TicketCaptureWriter.
      // The applied_promotion rows are written with sale_id = NULL now; TicketCaptureWriter stamps
      // it once the sale records at capture time (ADR 0006 revenue-at-capture).
      persistAppliedPromotions(savedTicket.getId(), null, evalResult, companyId);
    }

    return new CheckoutResult(assembleResponse(savedTicket.getId()), true);
  }

  /**
   * Re-reads a ticket by idempotency key in a FRESH transaction, used to recover the loser of a
   * concurrent insert race after its own create transaction aborted.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
  public Optional<TicketResponse> findExistingByKey(String idempotencyKey) {
    return ticketRepository
        .findViewByIdempotencyKey(idempotencyKey)
        .map(view -> assembleResponse(view.getId()));
  }

  /**
   * Resolves the MANDATORY barber {@link StaffProfile}, validating it exists, is active, and
   * belongs to {@code businessId}, and returns its snapshotted employee link (may be {@code null} —
   * the LINK stays optional even though the PROFILE selection is mandatory).
   *
   * @throws IllegalArgumentException if the profile is unknown, cross-business, or inactive (→ 400)
   */
  private UUID resolveBarber(UUID businessId, UUID staffProfileId) {
    Objects.requireNonNull(staffProfileId, "staffProfileId");
    StaffProfile profile =
        staffProfileRepository
            .findById(staffProfileId)
            .orElseThrow(
                () -> new IllegalArgumentException("Staff profile not found: " + staffProfileId));
    if (!businessId.equals(profile.getBusinessId())) {
      throw new IllegalArgumentException(
          "Staff profile " + staffProfileId + " belongs to a different business");
    }
    if (!profile.isActive()) {
      throw new IllegalArgumentException("Staff profile is inactive: " + staffProfileId);
    }
    return profile.getEmployeeId();
  }

  /**
   * Builds the (not-yet-persisted) {@link BarbershopTicketLine} entities for a resolved cart
   * against a pre-generated {@code ticketId} — each line's id is final at construction (Phase 3,
   * ADR 0026: the promotions engine needs it before the ticket itself exists; see class javadoc).
   */
  private List<BarbershopTicketLine> buildLines(
      UUID ticketId, UUID businessId, List<TicketItemReader.ResolvedLine> lines) {
    List<BarbershopTicketLine> result = new ArrayList<>(lines.size());
    for (TicketItemReader.ResolvedLine rl : lines) {
      Money unitPrice = Money.ofMinor(rl.priceMinor(), rl.currency());
      result.add(
          new BarbershopTicketLine(
              ticketId, businessId, rl.itemType(), rl.itemId(), rl.name(), unitPrice, rl.qty()));
    }
    return result;
  }

  private void persistLines(List<BarbershopTicketLine> lines, String companyId) {
    for (BarbershopTicketLine line : lines) {
      line.setCompanyId(companyId);
      lineRepository.save(line);
    }
    // Explicit flush: the response is assembled via a NATIVE query later in this same transaction,
    // which does not auto-synchronize with Hibernate's persistence context — the physical INSERTs
    // must already be on the connection for the native SELECT to see them (CLAUDE.md convention).
    lineRepository.flush();
  }

  /**
   * Inspects a promotions-engine result's coupon outcome and either does nothing (no coupon
   * supplied), redeems the coupon atomically and returns its id, or throws.
   *
   * @throws CouponInvalidException if the supplied coupon code did not resolve to a usable coupon
   * @throws CouponExhaustedException if the coupon's redemption limit is reached (either the
   *     engine's advisory check, or — the definitive, race-safe guard — the atomic UPDATE lost the
   *     race to a concurrent checkout)
   */
  private UUID redeemCouponIfApplicable(EvalResult evalResult) {
    if (evalResult.couponOutcome() == null) {
      return null;
    }
    CouponOutcome outcome = evalResult.couponOutcome();
    return switch (outcome.status()) {
      case INVALID -> throw new CouponInvalidException(outcome.code());
      case EXHAUSTED -> throw new CouponExhaustedException(outcome.code());
      case APPLIED -> {
        // APPLIED but non-redeemable = the coupon granted no NEW benefit (its rule already fired
        // automatically, or its deduction zeroed out) — never burn a redemption for nothing.
        if (!outcome.redeemable()) {
          yield null;
        }
        int rows = couponRepository.redeemIfAvailable(outcome.couponId());
        if (rows == 0) {
          throw new CouponExhaustedException(outcome.code());
        }
        yield outcome.couponId();
      }
    };
  }

  /**
   * Persists the {@code applied_promotion} audit rows for a checkout, same transaction as the
   * ticket/sale (ADR 0026). A {@code null} {@code saleId} is legal (the digital-tender path defers
   * revenue to capture — {@code TicketCaptureWriter} stamps it in later). Does nothing when there
   * is nothing to persist (no rule-backed deduction — a ticket discounted ONLY by the manual layer
   * produces zero rows here, since the manual discount has no {@code ruleId}).
   */
  private void persistAppliedPromotions(
      UUID ticketId, UUID saleId, EvalResult evalResult, String companyId) {
    if (evalResult.deductions().isEmpty()) {
      return;
    }
    List<AppliedPromotion> rows = new ArrayList<>(evalResult.deductions().size());
    for (AppliedDeduction d : evalResult.deductions()) {
      AppliedPromotion row =
          new AppliedPromotion(
              ticketId,
              saleId,
              d.ruleId(),
              d.couponId(),
              d.nameSnapshot(),
              d.typeSnapshot().name(),
              d.rateBpSnapshot(),
              d.lineRef(),
              d.amount());
      row.setCompanyId(companyId);
      rows.add(row);
    }
    appliedPromotionRepository.saveAll(rows);
  }

  /**
   * Assembles the full response by re-reading the ticket + lines + payment (native projections).
   */
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
