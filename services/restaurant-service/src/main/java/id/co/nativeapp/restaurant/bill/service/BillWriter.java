package id.co.nativeapp.restaurant.bill.service;

import id.co.nativeapp.money.Money;
import id.co.nativeapp.restaurant.bill.domain.Bill;
import id.co.nativeapp.restaurant.bill.domain.BillLine;
import id.co.nativeapp.restaurant.bill.domain.BillLineModifier;
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
  private final RestaurantTableRepository tableRepository;
  private final OutletAccessGuard outletAccessGuard;
  private final PromotionEngineService promotionEngine;
  private final AppliedPromotionRepository appliedPromotionRepository;
  private final ManualDiscountGuard manualDiscountGuard;

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
      RestaurantTableRepository tableRepository,
      OutletAccessGuard outletAccessGuard,
      PromotionEngineService promotionEngine,
      AppliedPromotionRepository appliedPromotionRepository,
      ManualDiscountGuard manualDiscountGuard) {
    this.billRepository = billRepository;
    this.lineRepository = lineRepository;
    this.modifierRepository = modifierRepository;
    this.menuItemRepository = menuItemRepository;
    this.modifierValidator = modifierValidator;
    this.taxChargeService = taxChargeService;
    this.saleWriter = saleWriter;
    this.stockDeductionWriter = stockDeductionWriter;
    this.tableRepository = tableRepository;
    this.outletAccessGuard = outletAccessGuard;
    this.promotionEngine = promotionEngine;
    this.appliedPromotionRepository = appliedPromotionRepository;
    this.manualDiscountGuard = manualDiscountGuard;
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
   * happens only at pay time.
   *
   * @throws BillNotFoundException if the bill is not found
   * @throws BillNotOpenException if the bill is not OPEN
   * @throws IllegalArgumentException if the line does not belong to this bill
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void removeLine(UUID billId, UUID lineId) {
    Bill bill =
        billRepository.findById(billId).orElseThrow(() -> new BillNotFoundException(billId));

    if (!"OPEN".equals(bill.getStatus())) {
      throw new BillNotOpenException(billId, bill.getStatus());
    }

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

    // Separate unpaid from paid line views.
    List<BillLineView> unpaidLineViews = allLineViews.stream().filter(v -> !v.isPaid()).toList();

    Set<UUID> unpaidIds =
        unpaidLineViews.stream().map(BillLineView::getId).collect(Collectors.toSet());

    List<BillLineView> targetLineViews;
    if (requestedLineIds != null) {
      // Validate: every requested id must belong to this bill and must be unpaid.
      for (UUID lineId : requestedLineIds) {
        if (!lineViewById.containsKey(lineId)) {
          throw new IllegalArgumentException(
              "Line " + lineId + " does not belong to bill " + billId);
        }
        if (!unpaidIds.contains(lineId)) {
          throw new IllegalArgumentException(
              "Line " + lineId + " is already paid on bill " + billId);
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
    // Evaluate the promotions engine for THIS check's subtotal (ADR 0026 — automatics + the manual
    // discount only; coupons are NOT supported on bills this phase — follow-up). The collapsed
    // discount feeds TaxChargeService as the fixed discount, replacing the raw request discount.
    // -----------------------------------------------------------------------
    Instant now = Instant.now();
    Money subtotal = sumLineTotals(targetLineViews, currency);
    List<EvalLine> evalLines = toEvalLines(targetLineViews, currency);
    long manualDiscountMinor = (request.discountMinor() != null) ? request.discountMinor() : 0L;
    EvalInput evalInput =
        new EvalInput(evalLines, currency, subtotal, now, null, manualDiscountMinor);
    EvalResult evalResult = promotionEngine.evaluate(evalInput);

    PriceBreakdown breakdown =
        taxChargeService.resolve(subtotal, 0L, evalResult.totalDiscount(), now);
    Money grandTotal = breakdown.grandTotal();

    if (!grandTotal.isPositive()) {
      throw new IllegalArgumentException(
          "Check grand total must be positive after discount/tax/service-charge; got "
              + grandTotal.amountMinor()
              + " "
              + currency);
    }

    // -----------------------------------------------------------------------
    // Deduct stock for tracked lines in this check — same tx, rolls back on
    // shortfall. Lines already paid in previous checks are unaffected.
    // -----------------------------------------------------------------------
    deductStock(targetLineViews, currency);

    // -----------------------------------------------------------------------
    // Record ONE sale for this check — one SaleRecorded outbox event.
    // -----------------------------------------------------------------------
    String tenderTypeName =
        (request.payment() != null) ? request.payment().tenderType().name() : null;
    RecordSaleCommand saleCommand =
        new RecordSaleCommand(
            bill.getBusinessId(),
            grandTotal.amountMinor(),
            currency,
            now,
            saleIdempotencyKey,
            tenderTypeName,
            breakdown);
    RecordSaleResult saleResult = saleWriter.recordInCurrentTx(saleCommand);
    UUID checkSaleId = saleResult.sale().id();

    // Same tx as the sale — the applied_promotion audit rows for THIS check (empty when only the
    // manual layer discounted, since the manual discount carries no ruleId). The "order_id" column
    // holds this BILL's id — see AppliedPromotion's class javadoc for the per-vertical column
    // reuse.
    persistAppliedPromotions(
        bill.getId(), checkSaleId, evalResult, TenantContext.require().companyId());

    // -----------------------------------------------------------------------
    // Mark the target lines as paid (bulk UPDATE via repository).
    // -----------------------------------------------------------------------
    List<UUID> targetIds = targetLineViews.stream().map(BillLineView::getId).toList();
    // Chunk in case there are many lines (unlikely for a bill, but follow the house rule <=1000).
    for (int i = 0; i < targetIds.size(); i += 1000) {
      List<UUID> chunk = targetIds.subList(i, Math.min(i + 1000, targetIds.size()));
      lineRepository.markLinesPaid(chunk, checkSaleId, actor);
    }

    // -----------------------------------------------------------------------
    // If ALL lines are now paid, transition the bill to PAID.
    // -----------------------------------------------------------------------
    boolean allPaid = (unpaidIds.size() == targetLineViews.size());
    if (allPaid) {
      bill.markPaid(checkSaleId);
      billRepository.saveAndFlush(bill);
    }

    // Re-load all line views after the bulk UPDATE so the response shows current paid flags.
    List<BillLineView> updatedLineViews = lineRepository.findViewsByBillId(bill.getId());
    List<BillLineResponse> lineResponses = buildLineResponses(updatedLineViews);
    PriceBreakdown fullBreakdown =
        computeBreakdown(updatedLineViews, currency, bill.getDiscountMinor());
    return toBillResponse(bill, lineResponses, fullBreakdown, currency);
  }

  // -------------------------------------------------------------------------
  // Cancel a bill
  // -------------------------------------------------------------------------

  /**
   * Cancels an OPEN bill (no sale, no stock change).
   *
   * @throws BillNotFoundException if the bill is not found
   * @throws BillNotOpenException if the bill is not OPEN
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void cancelBill(UUID billId) {
    Bill bill =
        billRepository.findById(billId).orElseThrow(() -> new BillNotFoundException(billId));

    if (!"OPEN".equals(bill.getStatus())) {
      throw new BillNotOpenException(billId, bill.getStatus());
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
}
