package id.co.nativeapp.restaurant.bill.service;

import id.co.nativeapp.money.Money;
import id.co.nativeapp.restaurant.bill.domain.Bill;
import id.co.nativeapp.restaurant.bill.domain.BillLine;
import id.co.nativeapp.restaurant.bill.domain.BillLineModifier;
import id.co.nativeapp.restaurant.bill.domain.BillLineReservationConflictException;
import id.co.nativeapp.restaurant.bill.domain.BillMutationForbiddenException;
import id.co.nativeapp.restaurant.bill.domain.BillNotFoundException;
import id.co.nativeapp.restaurant.bill.domain.BillNotOpenException;
import id.co.nativeapp.restaurant.bill.dto.AppendLinesRequest;
import id.co.nativeapp.restaurant.bill.dto.BillLineModifierResponse;
import id.co.nativeapp.restaurant.bill.dto.BillLineResponse;
import id.co.nativeapp.restaurant.bill.dto.BillResponse;
import id.co.nativeapp.restaurant.bill.dto.OpenBillRequest;
import id.co.nativeapp.restaurant.bill.dto.PayBillRequest;
import id.co.nativeapp.restaurant.bill.projection.BillLineModifierView;
import id.co.nativeapp.restaurant.bill.projection.BillLineView;
import id.co.nativeapp.restaurant.bill.repository.BillLineModifierRepository;
import id.co.nativeapp.restaurant.bill.repository.BillLineRepository;
import id.co.nativeapp.restaurant.bill.repository.BillRepository;
import id.co.nativeapp.restaurant.channel.repository.SalesChannelRepository;
import id.co.nativeapp.restaurant.config.ActorRolesProvider;
import id.co.nativeapp.restaurant.menu.domain.InsufficientStockException;
import id.co.nativeapp.restaurant.menu.projection.MenuItemView;
import id.co.nativeapp.restaurant.menu.projection.ModifierOptionView;
import id.co.nativeapp.restaurant.menu.repository.MenuItemRepository;
import id.co.nativeapp.restaurant.menu.service.StockDeductionWriter;
import id.co.nativeapp.restaurant.order.domain.OrderLine;
import id.co.nativeapp.restaurant.order.dto.OrderLineRequest;
import id.co.nativeapp.restaurant.order.dto.PriceBreakdownResponse;
import id.co.nativeapp.restaurant.order.service.ModifierValidationReader;
import id.co.nativeapp.restaurant.outletref.service.OutletAccessGuard;
import id.co.nativeapp.restaurant.payment.domain.TenderType;
import id.co.nativeapp.restaurant.payment.dto.PaymentRequest;
import id.co.nativeapp.restaurant.payment.dto.PaymentResponse;
import id.co.nativeapp.restaurant.payment.repository.PaymentRepository;
import id.co.nativeapp.restaurant.payment.service.PaymentWriter;
import id.co.nativeapp.restaurant.pricing.domain.PriceBreakdown;
import id.co.nativeapp.restaurant.pricing.service.TaxChargeService;
import id.co.nativeapp.restaurant.promotion.domain.AppliedPromotion;
import id.co.nativeapp.restaurant.promotion.dto.AppliedDeduction;
import id.co.nativeapp.restaurant.promotion.dto.EvalInput;
import id.co.nativeapp.restaurant.promotion.dto.EvalLine;
import id.co.nativeapp.restaurant.promotion.dto.EvalResult;
import id.co.nativeapp.restaurant.promotion.repository.AppliedPromotionRepository;
import id.co.nativeapp.restaurant.promotion.service.ManualDiscountGuard;
import id.co.nativeapp.restaurant.promotion.service.PromotionEngineService;
import id.co.nativeapp.restaurant.recipe.service.IngredientDepletionWriter;
import id.co.nativeapp.restaurant.register.service.CashWindowLock;
import id.co.nativeapp.restaurant.sale.dto.RecordSaleCommand;
import id.co.nativeapp.restaurant.sale.dto.RecordSaleResult;
import id.co.nativeapp.restaurant.sale.service.SaleWriter;
import id.co.nativeapp.restaurant.table.repository.RestaurantTableRepository;
import id.co.nativeapp.tenant.RlsAutoApplyAspect;
import id.co.nativeapp.tenant.TenantContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Currency;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns all {@code @Transactional} write units of work for the bill feature.
 *
 * <p>A distinct bean from {@link BillService} so each transactional method is invoked through the
 * Spring proxy — self-invocation would bypass the {@code @Transactional} advice and the {@link
 * RlsAutoApplyAspect} that sets the tenant GUC (same pattern as {@code OrderWriter}).
 *
 * <p><strong>Open model.</strong> A bill is opened empty with currency placeholder "XXX". On the
 * first {@link #appendLines} call the currency is established from the items and the bill row is
 * updated. Subsequent appends must match the established currency (single-currency per bill, same
 * as orders).
 *
 * <p><strong>Pay idempotency.</strong> The sale idempotency key is {@code billId + ":bill-sale"}.
 * Re-paying an already-PAID bill returns the current state without side effects (no second {@code
 * SaleRecorded}, no second stock deduction).
 *
 * <p><strong>Cart building.</strong> {@link #buildCart} mirrors {@code OrderWriter.buildCart}
 * exactly, sharing the same {@link ModifierValidationReader} and {@link TaxChargeService} beans so
 * the two paths cannot silently diverge.
 *
 * <p><strong>CashWindowLock (verified HIGH race fix).</strong> {@link #payBill} and {@code
 * BillPaymentCaptureWriter#capture} each acquire the per-business {@link CashWindowLock} SHARED
 * ({@link CashWindowLock#acquireForCommit}) as the FIRST lock-acquiring statement in THEIR OWN
 * transaction — strictly BEFORE the {@code Instant now} that becomes the check's sale {@code
 * occurred_at} is captured — mirroring {@code OrderWriter.checkout}/{@code payParked}. SHARED
 * holders never block each other, only a concurrent register close's EXCLUSIVE mode. See {@code
 * RegisterSessionWriter} class javadoc for the full contract. {@link #recordCheck} — the shared
 * sale-recording core both callers delegate to — does NOT re-acquire the lock itself: it mirrors
 * {@code SaleWriter#recordInCurrentTx}'s documented contract that a {@code MANDATORY}-joining
 * helper trusts its caller to already hold it (a PostgreSQL advisory xact lock is re-entrant within
 * one transaction, but re-acquiring inside the helper would defeat the "before {@code now}"
 * ordering for whichever caller captures {@code now} first).
 *
 * <p><strong>V38 — dynamic QRIS/CARD gateway (full-bill only).</strong> {@link
 * #initiatePendingPayment} mirrors {@code OrderWriter.checkout}'s digital-tender two-step: it mints
 * a PENDING {@code payment} row (via {@link PaymentWriter#recordPendingBillDigital}) for the
 * check's grand total, RESERVES every unpaid line ({@code bill_line.pending_payment_id}) against
 * it, and records NO sale. Revenue is recognised only when {@code BillPaymentCaptureWriter#capture}
 * runs (via the SAME {@code PaymentChargeSucceeded} consumer plumbing the order path already uses —
 * payment-service needs zero changes). {@link #recordCheck} is the sale-recording core shared by
 * {@link #payBill} (cash/manual/static, unchanged behaviour) and the gateway capture path.
 */
@Component
public class BillWriter {

  private final BillRepository billRepository;
  private final BillLineRepository lineRepository;
  private final BillLineModifierRepository modifierRepository;
  private final MenuItemRepository menuItemRepository;
  private final ModifierValidationReader modifierValidator;
  private final TaxChargeService taxChargeService;
  private final SaleWriter saleWriter;
  private final StockDeductionWriter stockDeductionWriter;
  private final IngredientDepletionWriter ingredientDepletionWriter;
  private final RestaurantTableRepository tableRepository;
  private final OutletAccessGuard outletAccessGuard;
  private final PromotionEngineService promotionEngine;
  private final AppliedPromotionRepository appliedPromotionRepository;
  private final ManualDiscountGuard manualDiscountGuard;
  private final ActorRolesProvider actorRoles;
  private final SalesChannelRepository salesChannelRepository;
  private final CashWindowLock cashWindowLock;
  private final PaymentWriter paymentWriter;
  private final BillPaymentWriter billPaymentWriter;
  private final PaymentRepository paymentRepository;

  @SuppressWarnings("checkstyle:ParameterNumber")
  public BillWriter(
      BillRepository billRepository,
      BillLineRepository lineRepository,
      BillLineModifierRepository modifierRepository,
      MenuItemRepository menuItemRepository,
      ModifierValidationReader modifierValidator,
      TaxChargeService taxChargeService,
      SaleWriter saleWriter,
      StockDeductionWriter stockDeductionWriter,
      IngredientDepletionWriter ingredientDepletionWriter,
      RestaurantTableRepository tableRepository,
      OutletAccessGuard outletAccessGuard,
      PromotionEngineService promotionEngine,
      AppliedPromotionRepository appliedPromotionRepository,
      ManualDiscountGuard manualDiscountGuard,
      SalesChannelRepository salesChannelRepository,
      CashWindowLock cashWindowLock,
      PaymentWriter paymentWriter,
      BillPaymentWriter billPaymentWriter,
      PaymentRepository paymentRepository,
      ActorRolesProvider actorRoles) {
    this.billRepository = billRepository;
    this.lineRepository = lineRepository;
    this.modifierRepository = modifierRepository;
    this.menuItemRepository = menuItemRepository;
    this.modifierValidator = modifierValidator;
    this.taxChargeService = taxChargeService;
    this.saleWriter = saleWriter;
    this.stockDeductionWriter = stockDeductionWriter;
    this.ingredientDepletionWriter = ingredientDepletionWriter;
    this.tableRepository = tableRepository;
    this.outletAccessGuard = outletAccessGuard;
    this.promotionEngine = promotionEngine;
    this.appliedPromotionRepository = appliedPromotionRepository;
    this.manualDiscountGuard = manualDiscountGuard;
    this.salesChannelRepository = salesChannelRepository;
    this.cashWindowLock = cashWindowLock;
    this.paymentWriter = paymentWriter;
    this.billPaymentWriter = billPaymentWriter;
    this.paymentRepository = paymentRepository;
    this.actorRoles = actorRoles;
  }

  /**
   * Open-bill lockdown (owner rule): destructive open-bill mutations require {@code
   * owner}/{@code manager}. Empty-roles-pass semantics shared with {@link
   * id.co.nativeapp.restaurant.promotion.service.ManualDiscountGuard} — a headerless caller
   * (gateway-less dev recipe / direct service-layer test) is trusted; a real cashier token is
   * denied.
   */
  private void requireOwnerOrManager(String action, UUID billId) {
    if (!actorRoles.currentRoles().isEmpty() && !actorRoles.isOwnerOrManager()) {
      throw new BillMutationForbiddenException(billId, action);
    }
  }

  // -------------------------------------------------------------------------
  // Open a new bill
  // -------------------------------------------------------------------------

  /**
   * Opens a new OPEN bill. Validates that the tableId (if supplied) belongs to the same business.
   * Multiple OPEN bills per table are explicitly allowed.
   *
   * @throws IllegalArgumentException on invalid tableId
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public BillResponse open(OpenBillRequest request) {
    String companyId = TenantContext.require().companyId();

    // Phase 5 enforcement: a cashier may only open a bill at an outlet they are assigned to —
    // the same OutletAccessGuard as OrderWriter, so bills cannot sidestep the order-path guard.
    outletAccessGuard.enforce(request.businessId());

    if (request.tableId() != null) {
      validateTableId(request.tableId(), request.businessId());
    }

    // Bill is opened empty with a currency placeholder "XXX" (ISO 4217 "no currency").
    // The real currency is established on the first appendLines call from the menu items.
    Bill bill = new Bill(request.businessId(), request.tableId(), request.guestLabel(), "XXX");
    bill.setCompanyId(companyId);
    Bill saved = billRepository.saveAndFlush(bill);

    return toBillResponse(saved, List.of(), null, "XXX");
  }

  // -------------------------------------------------------------------------
  // Append a round of lines
  // -------------------------------------------------------------------------

  /**
   * Appends a round of items to an OPEN bill. Validates items, currency homogeneity (must match the
   * bill's established currency after first round), modifiers, and stock pre-check.
   *
   * @throws BillNotFoundException if the bill is not found
   * @throws BillNotOpenException if the bill is not OPEN
   * @throws IllegalArgumentException on invalid items, cross-business items, or currency mismatch
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public BillResponse appendLines(UUID billId, AppendLinesRequest request) {
    String companyId = TenantContext.require().companyId();

    Bill bill =
        billRepository.findById(billId).orElseThrow(() -> new BillNotFoundException(billId));

    if (!"OPEN".equals(bill.getStatus())) {
      throw new BillNotOpenException(billId, bill.getStatus());
    }

    CartContext cart = buildCart(bill.getBusinessId(), request.lines());

    // Currency consistency: on first append establish the bill's currency from the items.
    // On subsequent appends, enforce currency matches.
    String existingCurrency = bill.getCurrency().strip();
    if ("XXX".equals(existingCurrency)) {
      bill.establishCurrency(cart.currencyCode());
    } else if (!existingCurrency.equals(cart.currencyCode())) {
      throw new IllegalArgumentException(
          "Cannot append items with currency "
              + cart.currencyCode()
              + " to a bill denominated in "
              + existingCurrency);
    }

    // Persist the new lines.
    for (int i = 0; i < request.lines().size(); i++) {
      OrderLineRequest lineReq = request.lines().get(i);
      List<ModifierOptionView> lineModifiers = cart.resolvedModifiersByLine().get(i);
      MenuItemView view = cart.itemMap().get(lineReq.menuItemId());

      Money unitPrice = Money.ofMinor(view.getPriceMinor(), cart.currencyCode());
      long modifierDelta = 0L;
      for (ModifierOptionView opt : lineModifiers) {
        modifierDelta = Math.addExact(modifierDelta, opt.getPriceDeltaMinor());
      }

      BillLine line =
          new BillLine(
              lineReq.menuItemId(), view.getName(), unitPrice, modifierDelta, lineReq.qty());
      line.setCompanyId(companyId);
      for (ModifierOptionView opt : lineModifiers) {
        BillLineModifier modifier =
            new BillLineModifier(opt.getId(), opt.getName(), opt.getPriceDeltaMinor());
        modifier.setCompanyId(companyId);
        line.addModifier(modifier);
      }
      bill.addLine(line);
    }

    Bill saved = billRepository.saveAndFlush(bill);

    // Load all lines + modifiers for the response.
    List<BillLineView> lineViews = lineRepository.findViewsByBillId(saved.getId());
    List<BillLineResponse> lineResponses = buildLineResponses(lineViews);

    // Recompute live breakdown from ALL lines on the bill.
    String currency = saved.getCurrency().strip();
    PriceBreakdown breakdown = computeBreakdown(lineViews, currency, saved.getDiscountMinor());

    return toBillResponse(saved, lineResponses, breakdown, currency);
  }

  // -------------------------------------------------------------------------
  // Remove a line
  // -------------------------------------------------------------------------

  /**
   * Removes a single line from an OPEN bill. Stock is not adjusted at remove time — deduction
   * happens only at pay time. Open-bill lockdown (owner rule): removing lines requires {@code
   * owner}/{@code manager} — a cashier cannot trim a bill after items were served. The domain
   * additionally refuses removing a PAID or payment-reserved line (see {@link
   * Bill#removeLine(BillLine)}).
   *
   * @throws BillNotFoundException if the bill is not found
   * @throws BillNotOpenException if the bill is not OPEN
   * @throws BillMutationForbiddenException if the actor is not owner/manager
   * @throws id.co.nativeapp.restaurant.bill.domain.BillLinePaidException if the line is paid
   * @throws IllegalArgumentException if the line does not belong to this bill
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void removeLine(UUID billId, UUID lineId) {
    Bill bill =
        billRepository.findById(billId).orElseThrow(() -> new BillNotFoundException(billId));

    if (!"OPEN".equals(bill.getStatus())) {
      throw new BillNotOpenException(billId, bill.getStatus());
    }

    requireOwnerOrManager("removeLine", billId);

    BillLine lineToRemove =
        bill.getLines().stream()
            .filter(l -> lineId.equals(l.getId()))
            .findFirst()
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "Line " + lineId + " does not belong to bill " + billId));

    bill.removeLine(lineToRemove);
    billRepository.saveAndFlush(bill);
  }

  // -------------------------------------------------------------------------
  // Pay a bill
  // -------------------------------------------------------------------------

  /**
   * Pays a SUBSET or ALL remaining unpaid lines on an OPEN bill as one check.
   *
   * <p><strong>Full-bill pay (existing behaviour):</strong> when {@code request.lineIds()} is null
   * or empty, every still-unpaid line is included in this check. This is exactly the
   * pre-Increment-3 behaviour; all existing tests continue to pass.
   *
   * <p><strong>Split check (Increment 3):</strong> when {@code request.lineIds()} is non-empty,
   * only those lines (intersected with the bill's unpaid lines) are included. The bill stays OPEN
   * until all its lines are paid, then transitions to PAID automatically.
   *
   * <p><strong>Idempotency:</strong>
   *
   * <ul>
   *   <li>If the bill is already PAID — return the current state without side effects.
   *   <li>If a caller-supplied {@code idempotencyKey} matches a sale already recorded —
   *       short-circuit (no second sale, no second stock deduction). The lines that were marked
   *       paid in the original call retain their {@code paid=true} state.
   *   <li>If no explicit key is supplied, a deterministic key is derived from the bill id and the
   *       sorted set of target line ids so retries of the same logical check are safe.
   * </ul>
   *
   * @throws BillNotFoundException if the bill is not found
   * @throws BillNotOpenException if the bill is not OPEN (and not PAID)
   * @throws InsufficientStockException on stock shortfall (422; rolls back this check's lines)
   * @throws IllegalArgumentException if the target set is empty, a requested line id is unknown, or
   *     a requested line is already paid
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public BillResponse payBill(UUID billId, PayBillRequest request) {
    String actor = TenantContext.require().actor();
    Bill bill =
        billRepository.findById(billId).orElseThrow(() -> new BillNotFoundException(billId));

    // Phase 5 enforcement at the money moment: even if the bill was opened by someone else (or the
    // cashier's assignment was revoked mid-shift), paying it requires outlet access.
    outletAccessGuard.enforce(bill.getBusinessId());

    // Idempotent: already PAID — return current state without side effects.
    if ("PAID".equals(bill.getStatus())) {
      List<BillLineView> lineViews = lineRepository.findViewsByBillId(bill.getId());
      List<BillLineResponse> lineResponses = buildLineResponses(lineViews);
      String currency = bill.getCurrency().strip();
      PriceBreakdown breakdown =
          lineViews.isEmpty()
              ? null
              : computeBreakdown(lineViews, currency, bill.getDiscountMinor());
      return toBillResponse(bill, lineResponses, breakdown, currency);
    }

    if (!"OPEN".equals(bill.getStatus())) {
      throw new BillNotOpenException(billId, bill.getStatus());
    }

    List<BillLineView> allLineViews = lineRepository.findViewsByBillId(bill.getId());
    if (allLineViews.isEmpty()) {
      throw new IllegalArgumentException(
          "Cannot pay a bill with no lines; add items first (bill id: " + billId + ")");
    }

    String currency = bill.getCurrency().strip();
    if ("XXX".equals(currency)) {
      throw new IllegalArgumentException(
          "Bill " + billId + " has no items yet; cannot pay an empty bill");
    }

    // -----------------------------------------------------------------------
    // Idempotency short-circuit for caller-supplied keys.
    // When the caller provides an explicit idempotencyKey we check it BEFORE
    // validating the line set so that a retry with the same key is safe even
    // if those lines are already marked paid by the first call.
    // -----------------------------------------------------------------------
    if (request.idempotencyKey() != null && !request.idempotencyKey().isBlank()) {
      Optional<id.co.nativeapp.restaurant.sale.dto.SaleResponse> existingByCallerKey =
          saleWriter.findExistingByKey(request.idempotencyKey());
      if (existingByCallerKey.isPresent()) {
        return idempotentResult(bill, currency, existingByCallerKey.get().id());
      }
    }

    // -----------------------------------------------------------------------
    // Resolve the target lines for THIS check.
    // -----------------------------------------------------------------------
    List<UUID> requestedLineIds =
        (request.lineIds() != null && !request.lineIds().isEmpty()) ? request.lineIds() : null;

    // Build an index of all line views by id for fast lookup.
    Map<UUID, BillLineView> lineViewById =
        allLineViews.stream().collect(Collectors.toMap(BillLineView::getId, Function.identity()));

    // Separate unpaid+unreserved ("payable") from paid/reserved line views. V38: a line currently
    // RESERVED for an in-flight gateway payment (pending_payment_id IS NOT NULL) is excluded here
    // even though it is not yet `paid` — the reservation holds until that gateway payment is either
    // captured or abandoned, so a concurrent cash check can never claim the SAME line (no
    // double-revenue race between a gateway capture and a cash payBill).
    List<BillLineView> unpaidLineViews =
        allLineViews.stream().filter(v -> !v.isPaid() && v.getPendingPaymentId() == null).toList();

    Set<UUID> unpaidIds =
        unpaidLineViews.stream().map(BillLineView::getId).collect(Collectors.toSet());

    List<BillLineView> targetLineViews;
    if (requestedLineIds != null) {
      // Validate: every requested id must belong to this bill and must be payable (unpaid AND not
      // reserved by an in-flight gateway payment).
      for (UUID lineId : requestedLineIds) {
        if (!lineViewById.containsKey(lineId)) {
          throw new IllegalArgumentException(
              "Line " + lineId + " does not belong to bill " + billId);
        }
        if (!unpaidIds.contains(lineId)) {
          throw new IllegalArgumentException(
              "Line "
                  + lineId
                  + " is already paid, or reserved by an in-flight gateway payment, on bill "
                  + billId);
        }
      }
      Set<UUID> requestedSet = Set.copyOf(requestedLineIds);
      targetLineViews =
          unpaidLineViews.stream().filter(v -> requestedSet.contains(v.getId())).toList();
    } else {
      // Full-bill pay — all unpaid lines.
      targetLineViews = unpaidLineViews;
    }

    if (targetLineViews.isEmpty()) {
      throw new IllegalArgumentException(
          "Nothing to pay on bill "
              + billId
              + ": the requested line set is empty or all lines are already paid");
    }

    // -----------------------------------------------------------------------
    // Idempotency key for this check's sale.
    // -----------------------------------------------------------------------
    String saleIdempotencyKey;
    if (request.idempotencyKey() != null && !request.idempotencyKey().isBlank()) {
      saleIdempotencyKey = request.idempotencyKey();
    } else if (requestedLineIds == null) {
      // Full-bill pay — use the legacy key so existing idempotency behaviour is preserved.
      saleIdempotencyKey = billId + ":bill-sale";
    } else {
      // Split check — derive a deterministic key from billId + sorted target line ids.
      saleIdempotencyKey = deriveCheckIdempotencyKey(billId, targetLineViews);
    }

    // -----------------------------------------------------------------------
    // Idempotency short-circuit for derived keys (full-bill or line-set hash).
    // At this point the caller did NOT supply an explicit key. The derived key
    // is stable for the same logical check, so a retry with the same target
    // line set hits the same key.
    // -----------------------------------------------------------------------
    Optional<id.co.nativeapp.restaurant.sale.dto.SaleResponse> existingSale =
        saleWriter.findExistingByKey(saleIdempotencyKey);
    if (existingSale.isPresent()) {
      return idempotentResult(bill, currency, existingSale.get().id());
    }

    // -----------------------------------------------------------------------
    // Phase 3 (ADR 0026): a positive manual discount requires owner/manager. Checked here — after
    // every idempotent-replay short-circuit above — so a safe replay never re-triggers the guard.
    // -----------------------------------------------------------------------
    manualDiscountGuard.enforce(request.discountMinor());

    // -----------------------------------------------------------------------
    // ADR 0036 Phase B2: an ONLINE tender requires a known+active channel. Checked here — after
    // every idempotent-replay short-circuit above — so a safe replay never re-triggers the guard.
    // -----------------------------------------------------------------------
    String onlineChannel =
        validateOnlineTenderAndNormalize(request.payment(), request.channelCode());

    // -----------------------------------------------------------------------
    // CashWindowLock (verified HIGH race fix) — SHARED, FIRST lock-acquiring statement in this
    // transaction, BEFORE the now capture below (same contract as OrderWriter.checkout/
    // payParked; see RegisterSessionWriter class javadoc). Everything above this point is a plain
    // read or an idempotent-replay short-circuit — no DB lock taken yet. recordCheck (below) does
    // NOT re-acquire this lock itself — see this class's javadoc.
    // -----------------------------------------------------------------------
    cashWindowLock.acquireForCommit(bill.getBusinessId());
    Instant now = Instant.now();

    String tenderTypeName =
        (request.payment() != null) ? request.payment().tenderType().name() : null;
    // ADR 0036 Phase B2: the channel rides the sale ONLY for an ONLINE tender (validated above);
    // null for every other tender.
    String onlineChannelForSale = isOnline(tenderTypeName) ? onlineChannel : null;

    UUID checkSaleId =
        recordCheck(
            bill,
            targetLineViews,
            request.discountMinor(),
            saleIdempotencyKey,
            tenderTypeName,
            onlineChannelForSale,
            now,
            now,
            null, // capturingPaymentId — cash/manual/static/online path (C1 fix): mark-paid is
            // guarded "AND paid = false AND pending_payment_id IS NULL" instead
            null); // precomputedPricing — not yet computed for this (cash) path; recordCheck
    // resolves it itself

    // Re-load all line views after recordCheck's bulk UPDATE so the response shows current paid
    // flags.
    List<BillLineView> updatedLineViews = lineRepository.findViewsByBillId(bill.getId());
    List<BillLineResponse> lineResponses = buildLineResponses(updatedLineViews);
    PriceBreakdown fullBreakdown =
        computeBreakdown(updatedLineViews, currency, bill.getDiscountMinor());
    return toBillResponse(bill, lineResponses, fullBreakdown, currency);
  }

  // -------------------------------------------------------------------------
  // V38 — dynamic QRIS/CARD gateway (full-bill only): initiate a PENDING payment
  // -------------------------------------------------------------------------

  /**
   * Mints a PENDING gateway (QRIS/CARD) {@code payment} for the FULL bill — every currently unpaid
   * line — and RESERVES those lines against it. Records NO sale; revenue is recognised only when
   * {@code BillPaymentCaptureWriter#capture} runs (mirrors {@code OrderWriter.checkout}'s
   * digital-tender two-step — see this class's javadoc).
   *
   * <p><strong>Self-heal.</strong> If the bill already carries a live PENDING gateway payment (a
   * refreshed QR, an abandoned app, a retried request), it is abandoned — and its reservation
   * released — FIRST, in the SAME transaction as the fresh mint (via {@link
   * BillPaymentWriter#abandonInCurrentTx}), so a stale attempt never leaves two live reservations
   * in flight for one bill.
   *
   * @param billId the bill to initiate a gateway payment against
   * @param request carries the digital {@code payment.tenderType} (QRIS/CARD) and the optional
   *     manual {@code discountMinor} for this check; {@code lineIds}/{@code channelCode} are
   *     ignored (full-bill only this phase; split checks are a follow-up)
   * @return the PENDING payment (status = PENDING, amount = this check's grand total)
   * @throws BillNotFoundException if the bill is not found
   * @throws BillNotOpenException if the bill is not OPEN
   * @throws IllegalArgumentException if the bill has no items / no unpaid lines, {@code
   *     request.payment()} is missing or not a digital (QRIS/CARD) tender, or the computed grand
   *     total is not positive
   * @throws id.co.nativeapp.restaurant.bill.domain.BillLineReservationConflictException if a
   *     concurrent payment claims one or more of the bill's unpaid lines between this method's read
   *     and its reservation UPDATE (409; the whole transaction — including the freshly-minted
   *     payment — rolls back; safe to retry)
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public PaymentResponse initiatePendingPayment(UUID billId, PayBillRequest request) {
    String actor = TenantContext.require().actor();

    Bill bill =
        billRepository.findById(billId).orElseThrow(() -> new BillNotFoundException(billId));

    // Phase 5 enforcement — same guard as payBill.
    outletAccessGuard.enforce(bill.getBusinessId());

    if (!"OPEN".equals(bill.getStatus())) {
      throw new BillNotOpenException(billId, bill.getStatus());
    }

    String currency = bill.getCurrency().strip();
    if ("XXX".equals(currency)) {
      throw new IllegalArgumentException(
          "Bill " + billId + " has no items yet; cannot pay an empty bill");
    }

    TenderType tenderType = (request.payment() != null) ? request.payment().tenderType() : null;
    if (tenderType == null || !tenderType.isDigital()) {
      throw new IllegalArgumentException(
          "initiatePendingPayment requires a digital (QRIS/CARD) payment.tenderType; got "
              + tenderType);
    }

    // Phase 3 (ADR 0026): a positive manual discount requires owner/manager — same guard payBill
    // enforces, checked before any state-mutating step below.
    manualDiscountGuard.enforce(request.discountMinor());

    // Self-heal: a stale live PENDING gateway payment for this bill is abandoned (and its
    // reservation released) BEFORE minting a new one, in THIS SAME transaction — either both the
    // abandon and the fresh mint commit together, or neither does.
    //
    // W2 fix (code review): the payment found "live PENDING" a moment ago may have been CAPTURED
    // (or already abandoned by a concurrent racer) by the time this abandon actually runs — a
    // benign, harmless race, NOT a failure: the fresh mint below simply proceeds against whatever
    // bill_line state exists now (if that prior payment just captured, its lines are already paid
    // and this method correctly rejects below with "no unpaid lines" instead of a 500).
    paymentRepository
        .findLivePendingBillPaymentId(billId)
        .ifPresent(
            stalePaymentId -> {
              try {
                billPaymentWriter.abandonInCurrentTx(stalePaymentId);
              } catch (IllegalStateException alreadySettled) {
                // Payment#abandon's PENDING guard fired — it settled (captured/abandoned) between
                // our read and this call. Nothing to self-heal; not an error for THIS request.
              }
            });

    List<BillLineView> allLineViews = lineRepository.findViewsByBillId(bill.getId());
    List<BillLineView> unpaidLineViews = allLineViews.stream().filter(v -> !v.isPaid()).toList();
    if (unpaidLineViews.isEmpty()) {
      throw new IllegalArgumentException(
          "Cannot pay a bill with no unpaid lines (bill id: " + billId + ")");
    }

    // Compute the check breakdown EXACTLY as payBill does (promotions + tax/service + discount) —
    // reuse the same computation (priceCheck), pricing AT this mint instant.
    Instant now = Instant.now();
    CheckPricing pricing = priceCheck(unpaidLineViews, currency, request.discountMinor(), now);
    Money grandTotal = pricing.breakdown().grandTotal();
    if (!grandTotal.isPositive()) {
      throw new IllegalArgumentException(
          "Check grand total must be positive after discount/tax/service-charge; got "
              + grandTotal.amountMinor()
              + " "
              + currency);
    }

    // This PENDING payment's OWN idempotency key — distinct per mint attempt (a self-healed retry
    // must never collide with the just-ABANDONED row under uq_payment_company_idempotency). Kept
    // SHORT (a bare UUID, not billId-prefixed): DigitalProvider#authorize builds provider_ref as
    // "PENDING-<this key>-<uuid>" and provider_ref is VARCHAR(128) — a long key here would overflow
    // it (the order path's client-supplied keys are always short; this one is server-generated, so
    // it must stay short by construction instead).
    //
    // HIGH fix (code review): this is NOT the eventual check's sale idempotency key — there is no
    // longer a shared "<bill_id>:bill-sale"-style key minted here at all. BillPaymentCaptureWriter
    // derives the sale key on-the-fly from the payment's OWN id at capture time (mirroring the
    // order path exactly), so it is unique per payment and can never collide with the cash path's
    // (or an earlier gateway check's) sale on the same bill.
    String paymentIdempotencyKey = "bill-pay-" + UUID.randomUUID();

    // S2 (code review): request.idempotencyKey() is intentionally NOT consulted here — pay-pending
    // is not itself idempotent (retries self-heal by abandoning the prior attempt, see above); the
    // field is inert on this endpoint. The frontend may still send it harmlessly.
    PaymentResponse paymentResponse =
        paymentWriter.recordPendingBillDigital(
            billId,
            bill.getBusinessId(),
            tenderType,
            grandTotal,
            request.discountMinor(),
            now,
            paymentIdempotencyKey);

    // RESERVE every unpaid line against the freshly-minted PENDING payment. A concurrent cash
    // payBill against the SAME lines is blocked from here on (its own unpaid-line read excludes
    // any row this UPDATE just claimed).
    int reserved = lineRepository.reserveUnpaidLines(billId, paymentResponse.paymentId(), actor);
    if (reserved != unpaidLineViews.size()) {
      throw new BillLineReservationConflictException(billId, unpaidLineViews.size(), reserved);
    }

    return paymentResponse;
  }

  // -------------------------------------------------------------------------
  // Cancel a bill
  // -------------------------------------------------------------------------

  /**
   * Cancels an OPEN bill (no sale, no stock change). Open-bill lockdown (owner rule): a bill that
   * holds lines must end in payment unless an {@code owner}/{@code manager} intervenes — only an
   * EMPTY bill (wrong table opened) may be cancelled by anyone. The domain additionally refuses a
   * cancel while any line is PAID or reserved by an in-flight payment (see {@link Bill#cancel()}).
   *
   * @throws BillNotFoundException if the bill is not found
   * @throws BillNotOpenException if the bill is not OPEN
   * @throws BillMutationForbiddenException if the bill has lines and the actor is not
   *     owner/manager
   * @throws id.co.nativeapp.restaurant.bill.domain.BillHasPaidLinesException if any line is paid
   * @throws id.co.nativeapp.restaurant.bill.domain.BillLineReservedException if any line is
   *     reserved
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void cancelBill(UUID billId) {
    Bill bill =
        billRepository.findById(billId).orElseThrow(() -> new BillNotFoundException(billId));

    if (!"OPEN".equals(bill.getStatus())) {
      throw new BillNotOpenException(billId, bill.getStatus());
    }

    if (!bill.getLines().isEmpty()) {
      requireOwnerOrManager("cancel", billId);
    }

    bill.cancel();
    billRepository.saveAndFlush(bill);
  }

  // -------------------------------------------------------------------------
  // Read helper
  // -------------------------------------------------------------------------

  /**
   * Fetches a single bill by id (full detail: header + lines + modifiers + live breakdown). Runs in
   * a REQUIRES_NEW read-only transaction so RLS is active.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
  public Optional<BillResponse> findById(UUID billId) {
    return billRepository
        .findById(billId)
        .map(
            bill -> {
              List<BillLineView> lineViews = lineRepository.findViewsByBillId(bill.getId());
              List<BillLineResponse> lineResponses = buildLineResponses(lineViews);
              String currency = bill.getCurrency().strip();
              PriceBreakdown breakdown =
                  (lineViews.isEmpty() || "XXX".equals(currency))
                      ? null
                      : computeBreakdown(lineViews, currency, bill.getDiscountMinor());
              return toBillResponse(bill, lineResponses, breakdown, currency);
            });
  }

  // -------------------------------------------------------------------------
  // Private helpers
  // -------------------------------------------------------------------------

  /**
   * Returns the current bill state for an idempotent replay. If all lines are now paid but the bill
   * aggregate is not yet marked PAID (can happen if the bill transition failed and then the client
   * retries), marks the bill PAID using the provided sale id.
   */
  private BillResponse idempotentResult(Bill bill, String currency, UUID saleId) {
    List<BillLineView> currentLineViews = lineRepository.findViewsByBillId(bill.getId());
    List<BillLineResponse> lineResponses = buildLineResponses(currentLineViews);
    boolean allPaid =
        !currentLineViews.isEmpty() && currentLineViews.stream().allMatch(BillLineView::isPaid);
    if (allPaid && !"PAID".equals(bill.getStatus())) {
      bill.markPaid(saleId);
      billRepository.saveAndFlush(bill);
    }
    PriceBreakdown breakdown =
        currentLineViews.isEmpty()
            ? null
            : computeBreakdown(currentLineViews, currency, bill.getDiscountMinor());
    return toBillResponse(bill, lineResponses, breakdown, currency);
  }

  /**
   * The sale-recording core shared by {@link #payBill} (cash/manual/static/online, unchanged
   * behaviour) and {@code BillPaymentCaptureWriter#capture} (the V38 gateway path): computes the
   * check's price breakdown ({@link #priceCheck}, unless {@code precomputedPricing} is supplied),
   * deducts stock + depletes ingredients, records ONE sale ({@code SaleRecorded}), persists the
   * {@code applied_promotion} audit rows, marks {@code lines} paid, and transitions {@code bill} to
   * PAID once every line on the WHOLE bill is paid.
   *
   * <p><strong>C1 fix (code review — CRITICAL concurrency double-settle).</strong> The mark-paid
   * UPDATE is guarded and its affected-row count is VERIFIED against {@code lines.size()}:
   *
   * <ul>
   *   <li>{@code capturingPaymentId == null} (the cash/manual/static/online {@link #payBill} path)
   *       — {@link BillLineRepository#markLinesPaidForCash} guards {@code AND paid = false AND
   *       pending_payment_id IS NULL}, RE-VALIDATING at write time that no gateway payment reserved
   *       these lines in the window between {@code payBill}'s initial unpaid-line read and this
   *       write. Without this, a concurrent {@link #initiatePendingPayment} reservation committing
   *       inside that window would be silently clobbered — the line ends up {@code paid = true} AND
   *       the customer double-charged (cash here, the still-live QRIS/CARD payment later captures
   *       too).
   *   <li>{@code capturingPaymentId != null} (the gateway {@code BillPaymentCaptureWriter#capture}
   *       path) — {@link BillLineRepository#markLinesPaidForCapture} guards {@code AND
   *       pending_payment_id = :paymentId}, so the UPDATE only ever touches lines THIS SPECIFIC
   *       payment still holds — a stale/duplicate capture, or one racing a concurrent {@code
   *       abandon}, can never blindly re-mark a line some OTHER payment or a cash check has since
   *       claimed.
   * </ul>
   *
   * <p>A shortfall on EITHER guard throws {@link BillLineReservationConflictException} (409, or —
   * being an {@link IllegalStateException} — auto-parked by {@code PaymentChargeSucceededWriter} on
   * the capture path), rolling back the WHOLE transaction: the sale and outbox row already written
   * in THIS SAME transaction roll back too, so a loser never leaves a phantom {@code SaleRecorded}.
   *
   * <p><strong>Does NOT acquire {@link CashWindowLock} itself</strong> — see this class's javadoc
   * (mirrors {@code SaleWriter#recordInCurrentTx}'s documented contract). The caller MUST already
   * hold it and have captured {@code now}/{@code pricingInstant} strictly after doing so, in the
   * SAME transaction, before calling this method.
   *
   * @param bill the bill being paid
   * @param lines the lines THIS check settles — already resolved unpaid (and, on the gateway path,
   *     reserved against the capturing payment)
   * @param discountMinor the RAW manual discount requested for this check, minor units, or {@code
   *     null} — fed into the SAME promotions-engine computation every caller uses (only when {@code
   *     precomputedPricing} is {@code null})
   * @param checkIdempotencyKey the deterministic sale idempotency key this check records under —
   *     for the gateway path this MUST be derived from the payment's OWN id (HIGH fix — see {@code
   *     BillPaymentCaptureWriter}), never a value shared across a bill's multiple checks
   * @param tenderTypeName the wire tender-type name ({@code CASH}/{@code QRIS}/{@code CARD}/{@code
   *     ONLINE}), or {@code null}
   * @param onlineChannel the sales-channel code, ONLY when {@code tenderTypeName} is {@code
   *     "ONLINE"} (validated by the caller); {@code null} otherwise
   * @param pricingInstant the instant effective-dated tax/service-charge rules resolve against —
   *     for {@link #payBill} this IS {@code now} (mint and settle are the same moment); for a
   *     gateway capture this is the ORIGINAL pay-pending mint instant ({@code
   *     payment.getOccurredAt()}), so a later capture reproduces the SAME breakdown even if a newer
   *     rule version has since become effective (mirrors {@code
   *     PaymentCaptureWriter#reconstructBreakdown}'s rationale for the order path)
   * @param now the instant recorded as the sale's {@code occurred_at} — for a gateway capture this
   *     is the CAPTURE instant (when revenue is actually recognised), which may be later than
   *     {@code pricingInstant}
   * @param capturingPaymentId {@code null} for the cash/manual/static/online {@link #payBill} path;
   *     the gateway payment's id for the {@code BillPaymentCaptureWriter#capture} path — selects
   *     which guarded mark-paid UPDATE runs (see above)
   * @param precomputedPricing when non-{@code null}, REUSES this already-computed {@link
   *     CheckPricing} instead of calling {@link #priceCheck} again (S3, code review — {@code
   *     BillPaymentCaptureWriter} already computed it for its recompute-and-assert guard; avoids a
   *     redundant second promotions-engine evaluation for the SAME check)
   * @return the recorded sale id
   * @throws IllegalArgumentException if the computed grand total is not positive
   * @throws InsufficientStockException on a tracked-item stock shortfall (rolls back this check)
   * @throws BillLineReservationConflictException if the guarded mark-paid UPDATE affects fewer rows
   *     than {@code lines.size()} — a concurrent racer claimed one or more of them first
   */
  // Package-private (not private): BillPaymentCaptureWriter (same bill.service package) calls this
  // as a plain method on the injected BillWriter bean from within its OWN already-open
  // REQUIRES_NEW transaction — mirrors OrderWriter calling PaymentWriter#
  // recordPendingDigitalInCurrentTx, a normal cross-bean call, not a self-invocation.
  @SuppressWarnings("checkstyle:ParameterNumber")
  UUID recordCheck(
      Bill bill,
      List<BillLineView> lines,
      Long discountMinor,
      String checkIdempotencyKey,
      String tenderTypeName,
      String onlineChannel,
      Instant pricingInstant,
      Instant now,
      UUID capturingPaymentId,
      CheckPricing precomputedPricing) {
    String currency = bill.getCurrency().strip();
    String companyId = TenantContext.require().companyId();
    String actor = TenantContext.require().actor();

    CheckPricing pricing =
        (precomputedPricing != null)
            ? precomputedPricing
            : priceCheck(lines, currency, discountMinor, pricingInstant);
    PriceBreakdown breakdown = pricing.breakdown();
    Money grandTotal = breakdown.grandTotal();
    if (!grandTotal.isPositive()) {
      throw new IllegalArgumentException(
          "Check grand total must be positive after discount/tax/service-charge; got "
              + grandTotal.amountMinor()
              + " "
              + currency);
    }

    // Deduct stock for tracked lines in this check — rolls back on shortfall. Lines already paid
    // in a previous check are unaffected.
    deductStock(lines, currency);

    // ADR 0050: recipe-driven ingredient depletion for THIS check's lines only — per-check by
    // design (same tx + idempotency short-circuit as the check's sale, so a replay can never
    // double-deplete). Floors at 0, never blocks the payment. ADR 0067 Phase C: the SAME call folds
    // COGS (null when nothing costed) — threaded into the sale command below.
    IngredientDepletionWriter.CogsResult cogs =
        ingredientDepletionWriter.depleteForLines(toDepletionLines(lines));

    // Record ONE sale for this check — one SaleRecorded outbox event.
    RecordSaleCommand saleCommand =
        new RecordSaleCommand(
            bill.getBusinessId(),
            grandTotal.amountMinor(),
            currency,
            now,
            checkIdempotencyKey,
            tenderTypeName,
            breakdown,
            onlineChannel,
            cogs != null ? cogs.cogsMinor() : null,
            cogs != null ? cogs.currency() : null);
    RecordSaleResult saleResult = saleWriter.recordInCurrentTx(saleCommand);
    UUID checkSaleId = saleResult.sale().id();

    // Same tx as the sale — the applied_promotion audit rows for THIS check (empty when only the
    // manual layer discounted, since the manual discount carries no ruleId). The "order_id" column
    // holds this BILL's id — see AppliedPromotion's class javadoc for the per-vertical column
    // reuse.
    persistAppliedPromotions(bill.getId(), checkSaleId, pricing.evalResult(), companyId);

    // C1 fix (code review): mark the target lines as paid via the GUARDED UPDATE matching this
    // check's origin, and verify the affected-row count — see this method's javadoc.
    List<UUID> targetIds = lines.stream().map(BillLineView::getId).toList();
    int totalUpdated = 0;
    // Chunk in case there are many lines (unlikely for a bill, but follow the house rule <=1000).
    for (int i = 0; i < targetIds.size(); i += 1000) {
      List<UUID> chunk = targetIds.subList(i, Math.min(i + 1000, targetIds.size()));
      totalUpdated +=
          (capturingPaymentId == null)
              ? lineRepository.markLinesPaidForCash(chunk, checkSaleId, actor)
              : lineRepository.markLinesPaidForCapture(
                  chunk, capturingPaymentId, checkSaleId, actor);
    }
    if (totalUpdated != targetIds.size()) {
      throw new BillLineReservationConflictException(bill.getId(), targetIds.size(), totalUpdated);
    }

    // If EVERY line on the WHOLE bill is now paid, transition it to PAID. Re-queries DB truth
    // (rather than comparing against a stale pre-fetch count) so this is correct even when a NEW
    // round was appended to the bill after `lines` was resolved/reserved (e.g. while a gateway
    // payment was in flight) — that new, still-unpaid line correctly keeps the bill OPEN.
    List<BillLineView> refreshedAll = lineRepository.findViewsByBillId(bill.getId());
    boolean allPaid =
        !refreshedAll.isEmpty() && refreshedAll.stream().allMatch(BillLineView::isPaid);
    if (allPaid) {
      bill.markPaid(checkSaleId);
      billRepository.saveAndFlush(bill);
    }

    return checkSaleId;
  }

  /**
   * Computes ONE check's price breakdown — the promotions-engine + tax computation {@link #payBill}
   * always used (ADR 0026 — automatics + the manual discount only; coupons are NOT supported on
   * bills this phase), now shared with the V38 gateway mint ({@link #initiatePendingPayment}) and
   * {@code BillPaymentCaptureWriter#capture}'s recompute-and-assert guard.
   *
   * @param lineViews the check's lines (subtotal = Σ line totals)
   * @param currency the bill's ISO-4217 currency
   * @param discountMinor the RAW manual discount, minor units, or {@code null}
   * @param pricingInstant the instant promotions/tax rules resolve against
   */
  // Package-private — see recordCheck's comment; BillPaymentCaptureWriter also calls this directly
  // for its capture-time recompute-and-assert guard.
  CheckPricing priceCheck(
      List<BillLineView> lineViews, String currency, Long discountMinor, Instant pricingInstant) {
    Money subtotal = sumLineTotals(lineViews, currency);
    List<EvalLine> evalLines = toEvalLines(lineViews, currency);
    long manualDiscountMinor = (discountMinor != null) ? discountMinor : 0L;
    EvalInput evalInput =
        new EvalInput(evalLines, currency, subtotal, pricingInstant, null, manualDiscountMinor);
    EvalResult evalResult = promotionEngine.evaluate(evalInput);
    PriceBreakdown breakdown =
        taxChargeService.resolve(subtotal, 0L, evalResult.totalDiscount(), pricingInstant);
    return new CheckPricing(breakdown, evalResult);
  }

  /**
   * One check's promotions+tax pricing outcome — {@code breakdown} for the recorded sale, {@code
   * evalResult} for the {@code applied_promotion} audit rows. Package-private: read by {@code
   * BillPaymentCaptureWriter}'s capture-time guard.
   */
  record CheckPricing(PriceBreakdown breakdown, EvalResult evalResult) {}

  /**
   * Validates that a tableId exists and belongs to the given business. RLS enforces tenant scope.
   */
  private void validateTableId(UUID tableId, UUID businessId) {
    tableRepository
        .findById(tableId)
        .filter(t -> businessId.equals(t.getBusinessId()))
        .orElseThrow(
            () ->
                new IllegalArgumentException(
                    "Table "
                        + tableId
                        + " not found or does not belong to business "
                        + businessId));
  }

  /**
   * Validates + builds the cart context (item loading, currency check, modifier resolution).
   * Mirrors {@code OrderWriter.buildCart} exactly — shared via the same service beans so the two
   * paths cannot silently diverge.
   */
  private CartContext buildCart(UUID businessId, List<OrderLineRequest> lineRequests) {

    // 1. Load requested menu items (RLS-scoped; chunk IN by <=1000).
    List<UUID> requestedIds = lineRequests.stream().map(OrderLineRequest::menuItemId).toList();
    List<MenuItemView> itemViews = new ArrayList<>();
    for (int i = 0; i < requestedIds.size(); i += 1000) {
      List<UUID> chunk = requestedIds.subList(i, Math.min(i + 1000, requestedIds.size()));
      itemViews.addAll(menuItemRepository.findViewsByIds(chunk));
    }
    Map<UUID, MenuItemView> itemMap =
        itemViews.stream().collect(Collectors.toMap(MenuItemView::getId, Function.identity()));

    // 2. Validate items.
    Set<UUID> foundIds = itemMap.keySet();
    for (OrderLineRequest lineReq : lineRequests) {
      if (!foundIds.contains(lineReq.menuItemId())) {
        throw new IllegalArgumentException(
            "Menu item not found or not visible: " + lineReq.menuItemId());
      }
      MenuItemView view = itemMap.get(lineReq.menuItemId());
      if (!view.isActive()) {
        throw new IllegalArgumentException("Menu item is inactive: " + lineReq.menuItemId());
      }
      if (!view.isAvailable()) {
        throw new IllegalArgumentException(
            "Menu item is not available (86'd): " + lineReq.menuItemId());
      }
      if (!businessId.equals(view.getBusinessId())) {
        throw new IllegalArgumentException(
            "Menu item "
                + lineReq.menuItemId()
                + " belongs to business "
                + view.getBusinessId()
                + ", not "
                + businessId);
      }
      Integer stock = view.getStockQuantity();
      if (stock != null && stock == 0) {
        throw new InsufficientStockException(
            lineReq.menuItemId(), view.getName(), lineReq.qty(), 0);
      }
    }

    // 3. Currency homogeneity check.
    Set<String> currencies =
        itemViews.stream().map(v -> v.getCurrency().strip()).collect(Collectors.toSet());
    if (currencies.size() != 1) {
      throw new IllegalArgumentException(
          "All menu items in one round must share the same currency; found: " + currencies);
    }
    String currencyCode = currencies.iterator().next();

    // 4. Modifier validation.
    ModifierValidationReader.ValidationContext modCtx = modifierValidator.loadContext(lineRequests);
    List<List<ModifierOptionView>> resolvedModifiersByLine = new ArrayList<>(lineRequests.size());
    for (OrderLineRequest lineReq : lineRequests) {
      MenuItemView view = itemMap.get(lineReq.menuItemId());
      resolvedModifiersByLine.add(
          modifierValidator.validateLine(lineReq, view.getPriceMinor(), modCtx));
    }

    return new CartContext(currencyCode, itemMap, resolvedModifiersByLine);
  }

  /**
   * Computes the {@link PriceBreakdown} from a list of line views. The subtotal is the sum of
   * persisted line totals; the optional discount is whatever is stored on the bill.
   */
  private PriceBreakdown computeBreakdown(
      List<BillLineView> lineViews, String currencyCode, Long discountMinor) {
    Money subtotal = sumLineTotals(lineViews, currencyCode);
    Money fixedDiscount =
        (discountMinor != null) ? Money.ofMinor(discountMinor, currencyCode) : null;
    return taxChargeService.resolve(subtotal, 0L, fixedDiscount, Instant.now());
  }

  /** Sums a set of bill lines' persisted totals into one {@link Money} subtotal. */
  private static Money sumLineTotals(List<BillLineView> lineViews, String currencyCode) {
    Currency currency = Currency.getInstance(currencyCode);
    Money subtotal = Money.zero(currency);
    for (BillLineView lv : lineViews) {
      subtotal = subtotal.plus(Money.ofMinor(lv.getLineTotalMinor(), currencyCode));
    }
    return subtotal;
  }

  /**
   * Builds the promotions engine's {@link EvalLine}s for a set of bill lines (ADR 0026) — loads
   * fresh {@link MenuItemView}s (RLS-scoped, chunked) so line-scope rule matching sees the item's
   * CURRENT category assignment, mirroring {@code OrderWriter.recomputeWithPromotions}.
   */
  private List<EvalLine> toEvalLines(List<BillLineView> lineViews, String currencyCode) {
    List<UUID> menuItemIds = lineViews.stream().map(BillLineView::getMenuItemId).toList();
    Map<UUID, MenuItemView> itemMap = new HashMap<>();
    for (int i = 0; i < menuItemIds.size(); i += 1000) {
      List<UUID> chunk = menuItemIds.subList(i, Math.min(i + 1000, menuItemIds.size()));
      menuItemRepository.findViewsByIds(chunk).forEach(v -> itemMap.put(v.getId(), v));
    }
    List<EvalLine> result = new ArrayList<>(lineViews.size());
    for (BillLineView lv : lineViews) {
      MenuItemView view = itemMap.get(lv.getMenuItemId());
      UUID categoryId = (view != null) ? view.getCategoryId() : null;
      long effectiveUnitPrice = lv.getUnitPriceMinor() + lv.getModifierDeltaMinor();
      result.add(
          new EvalLine(
              lv.getId(), lv.getMenuItemId(), categoryId, effectiveUnitPrice, lv.getQty()));
    }
    return result;
  }

  /**
   * Persists the {@code applied_promotion} audit rows for one check, same transaction as the sale
   * (ADR 0026). Does nothing when there is nothing to persist (no rule-backed deduction — a check
   * discounted ONLY by the manual layer produces zero rows, since the manual discount has no {@code
   * ruleId}). {@code orderId} here holds the {@code bill.id} (see {@code AppliedPromotion}'s class
   * javadoc for the per-vertical column reuse).
   */
  private void persistAppliedPromotions(
      UUID orderId, UUID saleId, EvalResult evalResult, String companyId) {
    if (evalResult.deductions().isEmpty()) {
      return;
    }
    List<AppliedPromotion> rows = new ArrayList<>(evalResult.deductions().size());
    for (AppliedDeduction d : evalResult.deductions()) {
      AppliedPromotion row =
          new AppliedPromotion(
              orderId,
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
   * Loads current item views for the bill lines and deducts stock for tracked items. Runs in the
   * caller's transaction (propagation MANDATORY via {@link StockDeductionWriter}).
   */
  private void deductStock(List<BillLineView> lineViews, String currencyCode) {
    if (lineViews.isEmpty()) {
      return;
    }
    List<UUID> menuItemIds = lineViews.stream().map(BillLineView::getMenuItemId).toList();
    List<MenuItemView> menuItemViews = new ArrayList<>();
    for (int i = 0; i < menuItemIds.size(); i += 1000) {
      List<UUID> chunk = menuItemIds.subList(i, Math.min(i + 1000, menuItemIds.size()));
      menuItemViews.addAll(menuItemRepository.findViewsByIds(chunk));
    }

    // Build synthetic OrderLine adapters carrying only menuItemId + qty (StockDeductionWriter
    // only calls getMenuItemId() and getQty() on them).
    List<OrderLine> adaptedLines = new ArrayList<>();
    for (BillLineView lv : lineViews) {
      Money unitPrice = Money.ofMinor(lv.getUnitPriceMinor(), currencyCode);
      OrderLine adapted =
          new OrderLine(lv.getMenuItemId(), lv.getNameSnapshot(), unitPrice, lv.getQty());
      adaptedLines.add(adapted);
    }

    stockDeductionWriter.deductForLines(adaptedLines, menuItemViews);
  }

  /**
   * ADR 0050: depletion input for a check's lines — the synthetic adapters in {@link #deductStock}
   * deliberately drop modifiers, so the selected option ids are re-read from the persisted {@code
   * bill_line_modifier} snapshots (chunked ≤ 1000).
   */
  private List<IngredientDepletionWriter.DepletionLine> toDepletionLines(
      List<BillLineView> lineViews) {
    List<UUID> lineIds = lineViews.stream().map(BillLineView::getId).toList();
    Map<UUID, List<UUID>> optionIdsByLine = new HashMap<>();
    for (int i = 0; i < lineIds.size(); i += 1000) {
      List<UUID> chunk = lineIds.subList(i, Math.min(i + 1000, lineIds.size()));
      for (BillLineModifierView mv : modifierRepository.findViewsByBillLineIds(chunk)) {
        optionIdsByLine
            .computeIfAbsent(mv.getBillLineId(), id -> new ArrayList<>())
            .add(mv.getOptionId());
      }
    }
    return lineViews.stream()
        .map(
            lv ->
                new IngredientDepletionWriter.DepletionLine(
                    lv.getMenuItemId(),
                    lv.getQty(),
                    optionIdsByLine.getOrDefault(lv.getId(), List.of())))
        .toList();
  }

  /** Loads modifier snapshots for a batch of line views and builds {@link BillLineResponse}s. */
  private List<BillLineResponse> buildLineResponses(List<BillLineView> lineViews) {
    if (lineViews.isEmpty()) {
      return List.of();
    }
    List<UUID> lineIds = lineViews.stream().map(BillLineView::getId).toList();
    Map<UUID, List<BillLineModifierView>> modByLine = new HashMap<>();
    for (int i = 0; i < lineIds.size(); i += 1000) {
      List<UUID> chunk = lineIds.subList(i, Math.min(i + 1000, lineIds.size()));
      modifierRepository
          .findViewsByBillLineIds(chunk)
          .forEach(
              m -> modByLine.computeIfAbsent(m.getBillLineId(), k -> new ArrayList<>()).add(m));
    }
    return lineViews.stream()
        .map(view -> toLineResponse(view, modByLine.getOrDefault(view.getId(), List.of())))
        .toList();
  }

  private static BillLineResponse toLineResponse(
      BillLineView view, List<BillLineModifierView> modViews) {
    List<BillLineModifierResponse> modResponses =
        modViews.stream()
            .map(
                m ->
                    new BillLineModifierResponse(
                        m.getOptionId(), m.getNameSnapshot(), m.getPriceDeltaMinor()))
            .toList();
    return new BillLineResponse(
        view.getId(),
        view.getMenuItemId(),
        view.getNameSnapshot(),
        view.getUnitPriceMinor(),
        view.getModifierDeltaMinor(),
        view.getQty(),
        view.getLineTotalMinor(),
        modResponses,
        view.isPaid());
  }

  /**
   * Derives a deterministic idempotency key for a split check from the bill id and the sorted set
   * of target line ids. Using SHA-256 keeps the key short regardless of line count.
   */
  private static String deriveCheckIdempotencyKey(UUID billId, List<BillLineView> targetLines) {
    List<String> sortedIds = targetLines.stream().map(v -> v.getId().toString()).sorted().toList();
    String raw = billId + ":check:" + String.join(",", sortedIds);
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
      return billId + ":check:" + HexFormat.of().formatHex(hash).substring(0, 16);
    } catch (NoSuchAlgorithmException e) {
      // SHA-256 is guaranteed by the JDK — should never happen.
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }

  private static BillResponse toBillResponse(
      Bill bill, List<BillLineResponse> lines, PriceBreakdown breakdown, String currency) {
    PriceBreakdownResponse bdResponse =
        breakdown != null ? PriceBreakdownResponse.from(breakdown) : null;
    String displayCurrency = "XXX".equals(currency) ? null : currency;
    return new BillResponse(
        bill.getId(),
        bill.getBusinessId(),
        bill.getTableId(),
        bill.getGuestLabel(),
        bill.getStatus(),
        displayCurrency,
        bill.getDiscountMinor(),
        bill.getSaleId(),
        lines,
        bdResponse);
  }

  /** Immutable cart context: resolved currency, item map, and per-line resolved modifier lists. */
  private record CartContext(
      String currencyCode,
      Map<UUID, MenuItemView> itemMap,
      List<List<ModifierOptionView>> resolvedModifiersByLine) {}

  // -------------------------------------------------------------------------
  // ADR 0036 Phase B2: ONLINE tender (company-managed sales channels)
  // -------------------------------------------------------------------------

  /**
   * Validates an ONLINE-tender payment request BEFORE any DB write and returns the normalized
   * channel code (or {@code null} for every other tender / no payment at all). {@code
   * PayBillRequest} carries no loyalty/gift-card fields (bills do not support that redemption yet),
   * so the ONLY checks here are (a) {@code channelCode} required and (b) the channel must exist AND
   * be active for the tenant — mirrors {@code OrderWriter.validateOnlineTender}'s (a)/(b) checks
   * exactly; its (c)/(d) gift-card/loyalty checks are vacuously satisfied on this path.
   *
   * @throws IllegalArgumentException if {@code payment} tenders ONLINE and (a) {@code channelCode}
   *     is missing/blank, or (b) the (normalized) channel is unknown or inactive for the tenant
   */
  private String validateOnlineTenderAndNormalize(PaymentRequest payment, String channelCode) {
    if (payment == null || payment.tenderType() != TenderType.ONLINE) {
      return null;
    }
    String normalized = normalizeChannelCode(channelCode);
    if (normalized == null || normalized.isBlank()) {
      throw new IllegalArgumentException("channelCode is required when tenderType is ONLINE");
    }
    if (!salesChannelRepository.existsActiveByCode(normalized)) {
      throw new IllegalArgumentException(
          "Unknown or inactive sales channel for tenderType ONLINE: " + channelCode);
    }
    return normalized;
  }

  /** {@code true} when the (nullable) wire tender-type name is exactly {@code "ONLINE"}. */
  private static boolean isOnline(String tenderTypeName) {
    return TenderType.ONLINE.name().equals(tenderTypeName);
  }

  /** Uppercase/trim normalization, matching {@code SalesChannelWriter.create}'s convention. */
  private static String normalizeChannelCode(String channelCode) {
    return channelCode == null ? null : channelCode.strip().toUpperCase(Locale.ROOT);
  }
}
