package id.co.nativeapp.carwash.ticket.service;

import id.co.nativeapp.carwash.catalog.domain.StaffProfile;
import id.co.nativeapp.carwash.catalog.repository.StaffProfileRepository;
import id.co.nativeapp.carwash.outletref.service.OutletAccessGuard;
import id.co.nativeapp.carwash.payment.domain.CarwashPayment;
import id.co.nativeapp.carwash.payment.domain.TenderType;
import id.co.nativeapp.carwash.payment.projection.CarwashPaymentView;
import id.co.nativeapp.carwash.payment.repository.CarwashPaymentRepository;
import id.co.nativeapp.carwash.payment.service.PaymentInstruction;
import id.co.nativeapp.carwash.payment.service.PaymentProviderRegistry;
import id.co.nativeapp.carwash.payment.service.TenderAuthorization;
import id.co.nativeapp.carwash.pricing.domain.PriceBreakdown;
import id.co.nativeapp.carwash.pricing.service.TaxChargeService;
import id.co.nativeapp.carwash.promotion.domain.AppliedPromotion;
import id.co.nativeapp.carwash.promotion.domain.CouponExhaustedException;
import id.co.nativeapp.carwash.promotion.domain.CouponInvalidException;
import id.co.nativeapp.carwash.promotion.dto.AppliedDeduction;
import id.co.nativeapp.carwash.promotion.dto.CouponOutcome;
import id.co.nativeapp.carwash.promotion.dto.EvalInput;
import id.co.nativeapp.carwash.promotion.dto.EvalLine;
import id.co.nativeapp.carwash.promotion.dto.EvalResult;
import id.co.nativeapp.carwash.promotion.repository.AppliedPromotionRepository;
import id.co.nativeapp.carwash.promotion.repository.CouponRepository;
import id.co.nativeapp.carwash.promotion.service.ManualDiscountGuard;
import id.co.nativeapp.carwash.promotion.service.PromotionEngineService;
import id.co.nativeapp.carwash.ticket.domain.CarwashTicket;
import id.co.nativeapp.carwash.ticket.domain.CarwashTicketLine;
import id.co.nativeapp.carwash.ticket.domain.TicketNotFoundException;
import id.co.nativeapp.carwash.ticket.dto.CheckoutRequest;
import id.co.nativeapp.carwash.ticket.dto.CheckoutResult;
import id.co.nativeapp.carwash.ticket.dto.TicketResponse;
import id.co.nativeapp.carwash.ticket.projection.TicketLineView;
import id.co.nativeapp.carwash.ticket.projection.TicketView;
import id.co.nativeapp.carwash.ticket.repository.CarwashTicketLineRepository;
import id.co.nativeapp.carwash.ticket.repository.CarwashTicketRepository;
import id.co.nativeapp.money.Money;
import id.co.nativeapp.tenant.RlsAutoApplyAspect;
import id.co.nativeapp.tenant.TenantContext;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the {@code @Transactional} write unit of work for carwash ticket checkout — the {@code
 * WashWriter} idempotency/orchestration contract applied to the POS-parity ticket (ADR 0023).
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
 *       promotions and never re-redeems a coupon (an idempotent replay of an already-recorded ticket
 *       still returns; only NEW work is rejected — the restaurant {@code OutletGate} ordering).
 *   <li>{@link OutletAccessGuard#enforce}: the attendant must be assigned to the outlet.
 *   <li>Phase 3 (ADR 0026): a positive manual discount requires owner/manager ({@link
 *       ManualDiscountGuard}).
 *   <li>Resolves + validates the requested lines via {@link TicketItemReader} (server-side pricing
 *       — never trusts a client amount).
 *   <li>Resolves the optional washer {@link StaffProfile} and snapshots its employee link.
 *   <li>Phase 3 (ADR 0026): pre-generates the ticket id and builds the {@link CarwashTicketLine}
 *       entities (their ids are known immediately, independent of the parent ticket's persisted
 *       state — a plain-UUID FK column, unlike restaurant's JPA-associated {@code OrderLine}) so the
 *       {@link PromotionEngineService} can be evaluated (line-scope matching + {@code
 *       AppliedDeduction.lineRef}) BEFORE the ticket itself is constructed, since the ticket's
 *       constructor needs the already-resolved {@link PriceBreakdown} the engine feeds into.
 *   <li>Resolves the price breakdown via {@link TaxChargeService}, using the promotions engine's
 *       collapsed discount as the fixed discount.
 *   <li>If a coupon was supplied: redeems it ATOMICALLY (409 on exhaustion — the whole transaction
 *       rolls back) and stamps {@code carwash_ticket.coupon_id}.
 *   <li>Persists the ticket + lines + the {@code applied_promotion} audit rows, then authorizes the
 *       tender via {@link PaymentProviderRegistry} and persists the {@link CarwashPayment}.
 *   <li>CASH ({@code !tenderType.isDigital()}): links the sale (ticket id = sale id, ADR 0023
 *       decision 2), writes {@code SaleRecorded} + metrics via {@link TicketEventEmitter} — all in
 *       this same transaction (rule 3). Digital (PENDING): no sale id, no events — deferred to
 *       {@link TicketCaptureWriter}; the {@code applied_promotion} rows are written with {@code
 *       sale_id = NULL} and stamped at capture time.
 * </ol>
 */
@Component
public class TicketWriter {

  private final CarwashTicketRepository ticketRepository;
  private final CarwashTicketLineRepository lineRepository;
  private final CarwashPaymentRepository paymentRepository;
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

  @SuppressWarnings("checkstyle:ParameterNumber")
  public TicketWriter(
      CarwashTicketRepository ticketRepository,
      CarwashTicketLineRepository lineRepository,
      CarwashPaymentRepository paymentRepository,
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
      ManualDiscountGuard manualDiscountGuard) {
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
  }

  /**
   * Persists a ticket, its lines, and its payment — and, for CASH, the {@code SaleRecorded} +
   * metrics outbox rows — in ONE transaction.
   *
   * @throws org.springframework.dao.DataIntegrityViolationException if a concurrent racer already
   *     inserted the {@code (company_id, idempotency_key)} row
   * @throws id.co.nativeapp.carwash.promotion.domain.ManualDiscountForbiddenException if {@code
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

    UUID washerEmployeeId = resolveWasher(request.businessId(), request.staffProfileId());

    Instant now = Instant.now();

    // Phase 3 (ADR 0026): pre-generate the ticket id and build the REAL CarwashTicketLine entities
    // now (their ids are already final, independent of ticket persistence — a plain-UUID FK, unlike
    // restaurant's JPA-associated OrderLine) so the promotions engine sees real lineRefs.
    UUID ticketId = UUID.randomUUID();
    List<CarwashTicketLine> ticketLines = buildLines(ticketId, request.businessId(), cart.lines());

    List<EvalLine> evalLines = new ArrayList<>(ticketLines.size());
    for (CarwashTicketLine line : ticketLines) {
      evalLines.add(
          new EvalLine(line.getId(), line.getItemId(), null, line.getUnitPrice().amountMinor(), line.getQty()));
    }

    long manualDiscountMinor = (request.discountMinor() != null) ? request.discountMinor() : 0L;
    EvalInput evalInput =
        new EvalInput(evalLines, cart.currencyCode(), cart.subtotal(), now, request.couponCode(), manualDiscountMinor);
    EvalResult evalResult = promotionEngine.evaluate(evalInput);

    PriceBreakdown breakdown = taxChargeService.resolve(cart.subtotal(), 0L, evalResult.totalDiscount(), now);

    // A zero (or negative) grand total must never record revenue: a fully-discounted or
    // zero-priced cart would otherwise emit a zero-amount SaleRecorded, which the finance journal
    // rejects (a balanced entry needs a positive total). 400 — review S1.
    if (breakdown.grandTotal().amountMinor() <= 0L) {
      throw new IllegalArgumentException("ticket grand total must be positive");
    }

    // Phase 3 (ADR 0026): coupon redemption — the atomic guard; 0 rows -> 409, whole tx rolls back.
    // Runs AFTER validation so a foreseeable rejection never wastes a redemption slot.
    UUID redeemedCouponId = redeemCouponIfApplicable(evalResult);

    CarwashTicket ticket =
        new CarwashTicket(
            ticketId,
            request.businessId(),
            request.bay(),
            request.vehiclePlate(),
            request.staffProfileId(),
            washerEmployeeId,
            breakdown,
            now,
            request.idempotencyKey());
    if (redeemedCouponId != null) {
      ticket.attachCoupon(redeemedCouponId);
    }
    ticket.setCompanyId(companyId);
    CarwashTicket savedTicket = ticketRepository.saveAndFlush(ticket);
    persistLines(ticketLines, companyId);

    TenderType tenderType = request.payment().tenderType();
    PaymentInstruction instruction =
        new PaymentInstruction(
            savedTicket.getId(),
            tenderType,
            breakdown.grandTotal(),
            request.payment().tenderedMinor(),
            request.idempotencyKey());
    TenderAuthorization auth =
        paymentProviderRegistry.providerFor(tenderType).authorize(instruction);

    CarwashPayment payment;
    if (!tenderType.isDigital()) {
      Money tendered = Money.ofMinor(request.payment().tenderedMinor(), cart.currencyCode());
      payment =
          CarwashPayment.capturedCash(
              savedTicket.getId(),
              request.businessId(),
              breakdown.grandTotal(),
              tendered,
              auth.change());
    } else {
      payment =
          CarwashPayment.pendingDigital(
              savedTicket.getId(),
              request.businessId(),
              tenderType,
              breakdown.grandTotal(),
              auth.providerRef());
    }
    payment.setCompanyId(companyId);
    paymentRepository.saveAndFlush(payment);

    if (!tenderType.isDigital()) {
      // CASH: revenue is recognised now (ADR 0006, preserved by ADR 0023) — the sale id IS the
      // ticket id (decision 2).
      savedTicket.linkSale(savedTicket.getId());
      ticketRepository.saveAndFlush(savedTicket);
      persistAppliedPromotions(savedTicket.getId(), savedTicket.getId(), evalResult, companyId);
      eventEmitter.recognizeRevenue(
          savedTicket,
          breakdown,
          washerEmployeeId,
          cart.addonTotal(),
          companyId,
          tenderType.name(),
          now);
      // Test seam: a no-op in production; a test can install a hook that throws here to prove the
      // ticket, its lines, its payment, AND the outbox rows roll back together (atomicity, rule 3).
      postOutboxHook.afterOutboxWrite(savedTicket);
    } else {
      // Digital (PENDING): no sale id, no events — deferred to TicketCaptureWriter. The
      // applied_promotion rows are written with sale_id = NULL now; TicketCaptureWriter stamps it
      // once the sale records at capture time (ADR 0006 revenue-at-capture).
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
   * Resolves the optional washer {@link StaffProfile}, validating it exists, is active, and belongs
   * to {@code businessId}, and returns its snapshotted employee link (may be {@code null}).
   *
   * @throws IllegalArgumentException if the profile is unknown, cross-business, or inactive (→ 400)
   */
  private UUID resolveWasher(UUID businessId, UUID staffProfileId) {
    if (staffProfileId == null) {
      return null;
    }
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
   * Builds the (not-yet-persisted) {@link CarwashTicketLine} entities for a resolved cart against a
   * pre-generated {@code ticketId} — each line's id is final at construction (Phase 3, ADR 0026: the
   * promotions engine needs it before the ticket itself exists; see class javadoc).
   */
  private List<CarwashTicketLine> buildLines(
      UUID ticketId, UUID businessId, List<TicketItemReader.ResolvedLine> lines) {
    List<CarwashTicketLine> result = new ArrayList<>(lines.size());
    for (TicketItemReader.ResolvedLine rl : lines) {
      Money unitPrice = Money.ofMinor(rl.priceMinor(), rl.currency());
      result.add(
          new CarwashTicketLine(ticketId, businessId, rl.itemType(), rl.itemId(), rl.name(), unitPrice, rl.qty()));
    }
    return result;
  }

  private void persistLines(List<CarwashTicketLine> lines, String companyId) {
    for (CarwashTicketLine line : lines) {
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
   * revenue to capture — {@code TicketCaptureWriter} stamps it in later). Does nothing when there is
   * nothing to persist (no rule-backed deduction — a ticket discounted ONLY by the manual layer
   * produces zero rows here, since the manual discount has no {@code ruleId}).
   */
  private void persistAppliedPromotions(UUID ticketId, UUID saleId, EvalResult evalResult, String companyId) {
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
    CarwashPaymentView payment = paymentRepository.findViewByTicketId(ticketId).orElse(null);
    return TicketResponseFactory.toResponse(view, lines, payment);
  }
}
