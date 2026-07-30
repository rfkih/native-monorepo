package id.co.nativeapp.restaurant.order.service;

import id.co.nativeapp.money.Money;
import id.co.nativeapp.restaurant.menu.projection.MenuItemView;
import id.co.nativeapp.restaurant.menu.projection.ModifierOptionView;
import id.co.nativeapp.restaurant.menu.repository.MenuItemRepository;
import id.co.nativeapp.restaurant.menu.service.StockDeductionWriter;
import id.co.nativeapp.restaurant.order.domain.Order;
import id.co.nativeapp.restaurant.order.domain.OrderLine;
import id.co.nativeapp.restaurant.order.domain.OrderLineModifier;
import id.co.nativeapp.restaurant.order.dto.CheckoutRequest;
import id.co.nativeapp.restaurant.order.dto.CheckoutResult;
import id.co.nativeapp.restaurant.order.dto.OrderLineModifierResponse;
import id.co.nativeapp.restaurant.order.dto.OrderLineRequest;
import id.co.nativeapp.restaurant.order.dto.OrderLineResponse;
import id.co.nativeapp.restaurant.order.dto.OrderResponse;
import id.co.nativeapp.restaurant.order.dto.ParkOrderRequest;
import id.co.nativeapp.restaurant.order.dto.PayParkedRequest;
import id.co.nativeapp.restaurant.order.projection.OrderLineModifierView;
import id.co.nativeapp.restaurant.order.projection.OrderLineView;
import id.co.nativeapp.restaurant.order.projection.OrderView;
import id.co.nativeapp.restaurant.order.repository.OrderLineModifierRepository;
import id.co.nativeapp.restaurant.order.repository.OrderLineRepository;
import id.co.nativeapp.restaurant.order.repository.OrderRepository;
import id.co.nativeapp.restaurant.outletref.service.OutletAccessGuard;
import id.co.nativeapp.restaurant.payment.dto.PaymentResponse;
import id.co.nativeapp.restaurant.payment.service.PaymentInstruction;
import id.co.nativeapp.restaurant.payment.service.PaymentWriter;
import id.co.nativeapp.restaurant.pricing.domain.PriceBreakdown;
import id.co.nativeapp.restaurant.pricing.service.TaxChargeService;
import id.co.nativeapp.restaurant.promotion.domain.AppliedPromotion;
import id.co.nativeapp.restaurant.promotion.domain.CouponExhaustedException;
import id.co.nativeapp.restaurant.promotion.domain.CouponInvalidException;
import id.co.nativeapp.restaurant.promotion.dto.AppliedDeduction;
import id.co.nativeapp.restaurant.promotion.dto.CouponOutcome;
import id.co.nativeapp.restaurant.promotion.dto.EvalInput;
import id.co.nativeapp.restaurant.promotion.dto.EvalLine;
import id.co.nativeapp.restaurant.promotion.dto.EvalResult;
import id.co.nativeapp.restaurant.promotion.repository.AppliedPromotionRepository;
import id.co.nativeapp.restaurant.promotion.repository.CouponRepository;
import id.co.nativeapp.restaurant.promotion.service.ManualDiscountGuard;
import id.co.nativeapp.restaurant.promotion.service.PromotionEngineService;
import id.co.nativeapp.restaurant.sale.dto.RecordSaleCommand;
import id.co.nativeapp.restaurant.sale.dto.RecordSaleResult;
import id.co.nativeapp.restaurant.sale.service.SaleWriter;
import id.co.nativeapp.restaurant.table.repository.RestaurantTableRepository;
import id.co.nativeapp.tenant.RlsAutoApplyAspect;
import id.co.nativeapp.tenant.TenantContext;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Currency;
import java.util.HashMap;
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
 * Owns the {@code @Transactional} write units of work for the order feature.
 *
 * <p>A distinct bean from {@link OrderService} so each transactional method is invoked through the
 * Spring proxy — self-invocation would bypass the {@code @Transactional} advice and the {@link
 * RlsAutoApplyAspect} that sets the tenant GUC (same pattern as {@code SaleWriter}).
 *
 * <p><strong>Checkout = one atomic unit of work.</strong> {@link #checkout} runs in its own {@code
 * REQUIRES_NEW} transaction and:
 *
 * <ol>
 *   <li>Idempotency short-circuit: returns the existing order if the key is already present — this
 *       fast path runs BEFORE the promotions engine, so a replay never re-evaluates promotions and
 *       never re-redeems a coupon.
 *   <li>Loads the requested menu items (RLS-scoped) and validates all are active, share the same
 *       currency (single-currency only — no mixed-currency orders, rule 8), and belong to the same
 *       business as the request ({@code businessId} check — W4).
 *   <li>Validates {@code tableId}: only allowed for DINE_IN; must belong to the same business.
 *   <li>Snapshots name + unit price, computes line totals and subtotal as integer minor units
 *       ({@link Math#multiplyExact} / {@link Math#addExact} — never a float).
 *   <li><strong>Phase 3 (ADR 0026):</strong> evaluates the {@link PromotionEngineService}
 *       (automatics + at most one coupon + the manual discount, all collapsed into ONE {@link
 *       Money} figure), then resolves effective tax + service-charge rules (Phase 2 pricing) with
 *       that figure as the fixed discount, producing a {@link PriceBreakdown} (subtotal → discount
 *       → taxableBase → serviceCharge → tax → grandTotal). Rejects a zero-or-negative grandTotal (a
 *       fully-comped order is not a sale → 400).
 *   <li>If a coupon was supplied: redeems it ATOMICALLY (409 on exhaustion — the whole transaction
 *       rolls back) and stamps {@code restaurant_order.coupon_id}.
 *   <li>Persists the {@link Order} + {@link OrderLine}s + the {@code applied_promotion} audit rows.
 *   <li>Calls {@link SaleWriter#recordInCurrentTx} with propagation {@code MANDATORY} so the sale
 *       row and its {@code SaleRecorded} outbox row join this same transaction (rule 3, C1 fix).
 * </ol>
 *
 * <p><strong>Park = saved cart, no revenue, no promotions yet.</strong> {@link #park} persists an
 * order in {@code PARKED} status — identical item/table validation to checkout, but writes NO sale,
 * NO payment, NO outbox row, and does NOT run the promotions engine (composition rule — ADR 0026:
 * promotions evaluate at PAY time, not park time). The stored {@code discountMinor} is the raw
 * manual discount only; {@link #payParked} re-evaluates the full engine (automatics + coupon +
 * manual) at pay time against whatever rules/coupons are effective THEN.
 *
 * <p>The {@code (company_id, idempotency_key)} UNIQUE constraint on {@code restaurant_order} (a
 * concurrent collision) is handled by the orchestrating {@link OrderService} via a separate-
 * transaction re-read — exactly the {@code SaleService} pattern.
 */
@Component
public class OrderWriter {

  private static final String DINE_IN = "DINE_IN";

  private final OrderRepository orderRepository;
  private final OrderLineRepository lineRepository;
  private final OrderLineModifierRepository modifierRepository;
  private final MenuItemRepository menuItemRepository;
  private final ModifierValidationReader modifierValidator;
  private final SaleWriter saleWriter;
  private final PaymentWriter paymentWriter;
  private final TaxChargeService taxChargeService;
  private final RestaurantTableRepository tableRepository;
  private final StockDeductionWriter stockDeductionWriter;
  private final OutletAccessGuard outletAccessGuard;
  private final PromotionEngineService promotionEngine;
  private final CouponRepository couponRepository;
  private final AppliedPromotionRepository appliedPromotionRepository;
  private final ManualDiscountGuard manualDiscountGuard;

  @SuppressWarnings("checkstyle:ParameterNumber")
  public OrderWriter(
      OrderRepository orderRepository,
      OrderLineRepository lineRepository,
      OrderLineModifierRepository modifierRepository,
      MenuItemRepository menuItemRepository,
      ModifierValidationReader modifierValidator,
      SaleWriter saleWriter,
      PaymentWriter paymentWriter,
      TaxChargeService taxChargeService,
      RestaurantTableRepository tableRepository,
      StockDeductionWriter stockDeductionWriter,
      OutletAccessGuard outletAccessGuard,
      PromotionEngineService promotionEngine,
      CouponRepository couponRepository,
      AppliedPromotionRepository appliedPromotionRepository,
      ManualDiscountGuard manualDiscountGuard) {
    this.orderRepository = orderRepository;
    this.lineRepository = lineRepository;
    this.modifierRepository = modifierRepository;
    this.menuItemRepository = menuItemRepository;
    this.modifierValidator = modifierValidator;
    this.saleWriter = saleWriter;
    this.paymentWriter = paymentWriter;
    this.taxChargeService = taxChargeService;
    this.tableRepository = tableRepository;
    this.stockDeductionWriter = stockDeductionWriter;
    this.outletAccessGuard = outletAccessGuard;
    this.promotionEngine = promotionEngine;
    this.couponRepository = couponRepository;
    this.appliedPromotionRepository = appliedPromotionRepository;
    this.manualDiscountGuard = manualDiscountGuard;
  }

  /**
   * Persists an order, its lines, and its {@code SaleRecorded} outbox row in ONE transaction.
   *
   * @throws org.springframework.dao.DataIntegrityViolationException if a concurrent racer already
   *     inserted the {@code (company_id, idempotency_key)} row
   * @throws IllegalArgumentException if any requested menu item is unknown/inactive, or if the
   *     items span more than one currency, or if tableId is supplied for a non-DINE_IN order, or if
   *     tableId does not belong to the request's business
   * @throws id.co.nativeapp.restaurant.promotion.domain.ManualDiscountForbiddenException if {@code
   *     discountMinor > 0} and the caller is not owner/manager
   * @throws CouponInvalidException if {@code couponCode} is supplied and does not resolve to a
   *     usable coupon
   * @throws CouponExhaustedException if {@code couponCode} resolves to a coupon whose redemption
   *     limit is reached
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public CheckoutResult checkout(CheckoutRequest request) {
    String companyId = TenantContext.require().companyId();

    // Phase 5 enforcement: cashier must be assigned to the outlet being rung (policy — owner/
    // manager bypass, cashier default-closed, zero-rows grandfather — lives in OutletAccessGuard).
    outletAccessGuard.enforce(request.businessId());

    // Idempotency fast path: return the existing order without a second SaleRecorded, WITHOUT
    // evaluating promotions or redeeming a coupon a second time (guard ordering is load-bearing).
    Optional<OrderView> existing =
        orderRepository.findViewByIdempotencyKey(request.idempotencyKey());
    if (existing.isPresent()) {
      return toIdempotentResult(existing.get(), companyId);
    }

    // Phase 3 (ADR 0026): a positive manual discount requires owner/manager.
    manualDiscountGuard.enforce(request.discountMinor());

    String orderType = resolveOrderType(request.orderType());
    UUID tableId = request.tableId();

    Instant now = Instant.now();

    // ------------------------------------------------------------------
    // 1. Load requested menu items, resolve modifiers, compute subtotal, evaluate promotions
    //    (ADR 0026), and price via TaxChargeService (RLS-scoped item loads; chunk IN by <=1000).
    // ------------------------------------------------------------------
    CartContext cart =
        buildCart(
            request.businessId(),
            request.lines(),
            request.discountMinor(),
            request.couponCode(),
            true,
            now);

    // ------------------------------------------------------------------
    // 2. Validate order type + table id (Phase 4).
    // ------------------------------------------------------------------
    validateTableId(orderType, tableId, request.businessId());

    // ------------------------------------------------------------------
    // 3. Coupon redemption (Phase 3, ADR 0026) — the atomic guard; 0 rows -> 409, whole tx rolls
    //    back. Runs AFTER validation so a foreseeable rejection never wastes a redemption slot.
    // ------------------------------------------------------------------
    UUID redeemedCouponId = redeemCouponIfApplicable(cart.evalResult());

    // ------------------------------------------------------------------
    // 4. Persist order + lines + modifier snapshots (total = GRAND TOTAL).
    // ------------------------------------------------------------------
    Order order =
        new Order(
            request.businessId(),
            cart.breakdown().grandTotal(),
            now,
            request.idempotencyKey(),
            orderType,
            tableId,
            request.discountMinor());
    if (redeemedCouponId != null) {
      order.attachCoupon(redeemedCouponId);
    }
    order.setCompanyId(companyId);
    persistLines(order, cart.linesToAdd(), companyId);
    Order saved = orderRepository.saveAndFlush(order);

    // ------------------------------------------------------------------
    // 5. Record the sale / payment based on tender type (ADR 0006).
    // ------------------------------------------------------------------
    OrderResponse response;
    boolean isDigitalPayment =
        request.payment() != null && request.payment().tenderType().isDigital();

    if (!isDigitalPayment) {
      // CASH or no-payment path: deduct stock (tracked items only) then record sale — all in this
      // transaction. A stock shortfall throws InsufficientStockException → rolls back everything.
      stockDeductionWriter.deductForLines(cart.linesToAdd(), cart.itemViews());

      String tenderTypeName =
          (request.payment() != null) ? request.payment().tenderType().name() : null;
      RecordSaleCommand saleCommand =
          new RecordSaleCommand(
              request.businessId(),
              cart.breakdown().grandTotal().amountMinor(),
              cart.currencyCode(),
              now,
              request.idempotencyKey(),
              tenderTypeName,
              cart.breakdown());
      RecordSaleResult saleResult = saleWriter.recordInCurrentTx(saleCommand);

      // Link the sale id back to the order → status COMPLETED.
      saved.linkSale(saleResult.sale().id());
      orderRepository.saveAndFlush(saved);

      // Immediate-cash path: the sale id is already known — stamp it on every applied_promotion
      // row.
      persistAppliedPromotions(saved.getId(), saleResult.sale().id(), cart.evalResult(), companyId);

      response = OrderResponse.from(saved, cart.breakdown());
      if (request.payment() != null) {
        PaymentInstruction instruction =
            new PaymentInstruction(
                saved.getId(),
                request.businessId(),
                request.payment().tenderType(),
                cart.breakdown().grandTotal(),
                request.payment().tenderedMinor(),
                request.idempotencyKey() + ":pay");
        PaymentResponse paymentResponse =
            paymentWriter.captureCashInCurrentTx(instruction, saleResult.sale().id(), now);
        response = response.withPayment(paymentResponse);
      }
    } else {
      // DIGITAL path: persist a PENDING payment; no sale, no SaleRecorded yet. The
      // applied_promotion
      // rows are written with sale_id = NULL now; PaymentCaptureWriter stamps it once the sale
      // records at capture time (ADR 0006 revenue-at-capture).
      saved.markAwaitingPayment();
      orderRepository.saveAndFlush(saved);

      persistAppliedPromotions(saved.getId(), null, cart.evalResult(), companyId);

      PaymentInstruction instruction =
          new PaymentInstruction(
              saved.getId(),
              request.businessId(),
              request.payment().tenderType(),
              cart.breakdown().grandTotal(),
              null,
              request.idempotencyKey() + ":pay");
      PaymentResponse paymentResponse =
          paymentWriter.recordPendingDigitalInCurrentTx(instruction, now);
      response = OrderResponse.from(saved, cart.breakdown()).withPayment(paymentResponse);
    }

    return new CheckoutResult(response, true);
  }

  /**
   * Parks a cart as a PARKED order — no sale, no payment, no outbox row, no promotions evaluation
   * (ADR 0026 — promotions evaluate at pay time). Idempotent on {@code (company_id,
   * idempotency_key)} like checkout.
   *
   * <p>Revenue is recognised ONLY when {@link #payParked} is called. This is the park invariant:
   * asserting that a PARKED order has no sale_id and no outbox row is the test oracle.
   *
   * @throws org.springframework.dao.DataIntegrityViolationException on concurrent collision
   * @throws IllegalArgumentException on invalid items, cross-business table, or tableId on
   *     non-DINE_IN
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public CheckoutResult park(ParkOrderRequest request) {
    String companyId = TenantContext.require().companyId();

    // Phase 5 enforcement: same guard as checkout — cashier must be assigned to this outlet.
    outletAccessGuard.enforce(request.businessId());

    // Idempotency fast path.
    Optional<OrderView> existing =
        orderRepository.findViewByIdempotencyKey(request.idempotencyKey());
    if (existing.isPresent()) {
      return toIdempotentResult(existing.get(), companyId);
    }

    String orderType = resolveOrderType(request.orderType());
    UUID tableId = request.tableId();

    // Validate items + pricing (same as checkout). No promotions engine — see class javadoc.
    CartContext cart =
        buildCart(
            request.businessId(),
            request.lines(),
            request.discountMinor(),
            null,
            false,
            Instant.now());

    // Validate order type + table id.
    validateTableId(orderType, tableId, request.businessId());

    Instant now = Instant.now();
    Order order =
        new Order(
            request.businessId(),
            cart.breakdown().grandTotal(),
            now,
            request.idempotencyKey(),
            orderType,
            tableId,
            request.discountMinor());
    order.markParked(); // PENDING → PARKED (no sale written here)
    order.setCompanyId(companyId);
    persistLines(order, cart.linesToAdd(), companyId);
    Order saved = orderRepository.saveAndFlush(order);

    // No SaleWriter call, no PaymentWriter call, no outbox row — park invariant.
    return new CheckoutResult(OrderResponse.from(saved, cart.breakdown()), true);
  }

  /**
   * Finalises a PARKED order: re-evaluates the promotions engine (ADR 0026 — against whatever
   * rules/ coupon are effective RIGHT NOW, not at park time), records the Sale + optional payment,
   * transitions PARKED → COMPLETED (cash / no-payment) or AWAITING_PAYMENT (digital). This is the
   * moment revenue AND promotions are recognised for a parked order.
   *
   * <p>Idempotent: re-paying a COMPLETED order returns the existing state without side effects (no
   * re-evaluation, no re-redemption); re-paying an AWAITING_PAYMENT order delegates idempotency to
   * the payment layer.
   *
   * @throws IllegalArgumentException if the order is not found, not PARKED, or already paid
   * @throws id.co.nativeapp.restaurant.promotion.domain.ManualDiscountForbiddenException if the
   *     order's stored {@code discountMinor > 0} and the caller is not owner/manager
   * @throws CouponInvalidException if {@code couponCode} is supplied and does not resolve to a
   *     usable coupon
   * @throws CouponExhaustedException if {@code couponCode} resolves to a coupon whose redemption
   *     limit is reached
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public OrderResponse payParked(UUID orderId, PayParkedRequest request) {
    String companyId = TenantContext.require().companyId();

    // Load the full aggregate (write path — needs all fields to check status + total).
    // Phase 5 enforcement: apply the outlet guard against the order's businessId, loaded once.
    Order order =
        orderRepository
            .findById(orderId)
            .orElseThrow(() -> new IllegalArgumentException("Order not found: " + orderId));

    // Phase 5 enforcement against the order's persisted businessId.
    outletAccessGuard.enforce(order.getBusinessId());

    if ("COMPLETED".equals(order.getStatus())) {
      // Idempotent: already paid — return the current state without side effects (no re-evaluation,
      // no re-redemption).
      return OrderResponse.from(order);
    }

    if (!"PARKED".equals(order.getStatus())) {
      throw new IllegalArgumentException(
          "Order "
              + orderId
              + " cannot be paid in its current status: "
              + order.getStatus()
              + ". Only PARKED orders can be finalised via /pay.");
    }

    // Phase 3 (ADR 0026): the manual discount was captured at park time; gate it HERE, at the
    // moment it actually charges the customer.
    manualDiscountGuard.enforce(order.getDiscountMinor());

    Instant now = Instant.now();
    String currencyCode = order.getTotal().currency().getCurrencyCode();
    String saleIdempotencyKey = orderId + ":park-sale";

    // Load persisted lines once — used for breakdown recomputation AND stock deduction.
    List<OrderLineView> parkedLineViews = lineRepository.findViewsByOrderId(order.getId());

    // Re-evaluate the promotions engine + recompute the price breakdown from the persisted lines
    // (H1 fix + ADR 0026). The subtotal is the sum of line totals; the manual-discount input is
    // whatever was stored on the order at park time; the coupon (if any) is supplied NOW.
    EngineRecompute er =
        recomputeWithPromotions(order, parkedLineViews, currencyCode, request.couponCode(), now);
    PriceBreakdown breakdown = er.breakdown();

    // The re-evaluated grand total is the TRUE final amount (ADR 0026) — every downstream use of
    // order.getTotal() (the sale amount, the payment instruction, the response) must agree with it.
    order.updateTotal(breakdown.grandTotal());

    UUID redeemedCouponId = redeemCouponIfApplicable(er.evalResult());
    if (redeemedCouponId != null) {
      order.attachCoupon(redeemedCouponId);
    }

    boolean isDigitalPayment =
        request.payment() != null && request.payment().tenderType().isDigital();

    if (!isDigitalPayment) {
      // Deduct stock for tracked items (same tx — roll back everything on shortfall).
      deductStockForParkedLines(parkedLineViews, currencyCode);

      // CASH or no-payment: record the sale now — revenue recognised here.
      String tenderTypeName =
          (request.payment() != null) ? request.payment().tenderType().name() : null;
      RecordSaleCommand saleCommand =
          new RecordSaleCommand(
              order.getBusinessId(),
              order.getTotal().amountMinor(),
              currencyCode,
              now,
              saleIdempotencyKey,
              tenderTypeName,
              breakdown);
      RecordSaleResult saleResult = saleWriter.recordInCurrentTx(saleCommand);

      order.linkSale(saleResult.sale().id()); // PARKED → COMPLETED
      orderRepository.saveAndFlush(order);

      persistAppliedPromotions(order.getId(), saleResult.sale().id(), er.evalResult(), companyId);

      OrderResponse response = OrderResponse.from(order, breakdown);
      if (request.payment() != null) {
        PaymentInstruction instruction =
            new PaymentInstruction(
                order.getId(),
                order.getBusinessId(),
                request.payment().tenderType(),
                order.getTotal(),
                request.payment().tenderedMinor(),
                saleIdempotencyKey + ":pay");
        PaymentResponse paymentResponse =
            paymentWriter.captureCashInCurrentTx(instruction, saleResult.sale().id(), now);
        response = response.withPayment(paymentResponse);
      }
      return response;
    } else {
      // DIGITAL: persist a PENDING payment; defer revenue to capture. applied_promotion rows are
      // written with sale_id = NULL; PaymentCaptureWriter stamps it at capture time.
      order.markAwaitingPayment(); // PARKED → AWAITING_PAYMENT
      orderRepository.saveAndFlush(order);

      persistAppliedPromotions(order.getId(), null, er.evalResult(), companyId);

      PaymentInstruction instruction =
          new PaymentInstruction(
              order.getId(),
              order.getBusinessId(),
              request.payment().tenderType(),
              order.getTotal(),
              null,
              saleIdempotencyKey + ":pay");
      PaymentResponse paymentResponse =
          paymentWriter.recordPendingDigitalInCurrentTx(instruction, now);
      return OrderResponse.from(order, breakdown).withPayment(paymentResponse);
    }
  }

  /**
   * Re-reads an order by idempotency key in a FRESH transaction, used to recover the loser of a
   * concurrent insert race after its own create transaction aborted.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
  public Optional<OrderResponse> findExistingByKey(String idempotencyKey) {
    return orderRepository
        .findViewByIdempotencyKey(idempotencyKey)
        .map(
            view -> {
              List<OrderLineView> lineViews = lineRepository.findViewsByOrderId(view.getId());
              List<OrderLineResponse> lines = buildLineResponses(lineViews);
              return toOrderResponse(view, lines);
            });
  }

  /**
   * Fetches a single order by id in a FRESH read-only transaction. Used for the GET /orders/{id}
   * resume path — returns the full order view including lines and modifiers.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
  public Optional<OrderResponse> findById(UUID orderId) {
    return orderRepository
        .findViewById(orderId)
        .map(
            view -> {
              List<OrderLineView> lineViews = lineRepository.findViewsByOrderId(view.getId());
              List<OrderLineResponse> lines = buildLineResponses(lineViews);
              return toOrderResponse(view, lines);
            });
  }

  // -------------------------------------------------------------------------
  // Shared helpers
  // -------------------------------------------------------------------------

  /**
   * Validates + builds the cart context (item loading, currency check, modifier resolution,
   * subtotal, pricing). Shared between checkout and park so they cannot diverge on item/modifier
   * validation.
   *
   * @param couponCode Phase 3 coupon code; only consulted when {@code applyPromotions} is {@code
   *     true}
   * @param applyPromotions {@code true} for checkout (evaluates {@link PromotionEngineService});
   *     {@code false} for park (ADR 0026 — promotions evaluate at pay time, not park time)
   * @param occurredAt the instant used for both the promotions engine's effective-window resolution
   *     and {@code TaxChargeService}'s effective-rule resolution — the SAME instant the caller uses
   *     for the order/sale timestamp
   */
  private CartContext buildCart(
      UUID businessId,
      List<OrderLineRequest> lineRequests,
      Long discountMinor,
      String couponCode,
      boolean applyPromotions,
      Instant occurredAt) {

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
      // Stock availability pre-check (fast fail before writing anything): if the item is tracked
      // and its current stock is 0, reject immediately. The definitive concurrency-safe check is
      // the atomic UPDATE in StockDeductionWriter; this is an early guard for the common case.
      Integer stock = view.getStockQuantity();
      if (stock != null && stock == 0) {
        throw new id.co.nativeapp.restaurant.menu.domain.InsufficientStockException(
            lineReq.menuItemId(), view.getName(), lineReq.qty(), 0);
      }
    }

    // 3. Currency homogeneity check.
    Set<String> currencies =
        itemViews.stream().map(v -> v.getCurrency().strip()).collect(Collectors.toSet());
    if (currencies.size() != 1) {
      throw new IllegalArgumentException(
          "All menu items in one order must share the same currency; found: " + currencies);
    }
    String currencyCode = currencies.iterator().next();
    Currency currency = Currency.getInstance(currencyCode);

    // 4. Modifier validation.
    ModifierValidationReader.ValidationContext modCtx = modifierValidator.loadContext(lineRequests);
    List<List<ModifierOptionView>> resolvedModifiersByLine = new ArrayList<>(lineRequests.size());
    for (OrderLineRequest lineReq : lineRequests) {
      MenuItemView view = itemMap.get(lineReq.menuItemId());
      resolvedModifiersByLine.add(
          modifierValidator.validateLine(lineReq, view.getPriceMinor(), modCtx));
    }

    // 5. Compute line totals + subtotal, and (Phase 3) each line's EvalLine.
    Money runningTotal = Money.zero(currency);
    List<OrderLine> linesToAdd = new ArrayList<>(lineRequests.size());
    List<EvalLine> evalLines = new ArrayList<>(lineRequests.size());
    for (int i = 0; i < lineRequests.size(); i++) {
      OrderLineRequest lineReq = lineRequests.get(i);
      List<ModifierOptionView> lineModifiers = resolvedModifiersByLine.get(i);
      MenuItemView view = itemMap.get(lineReq.menuItemId());
      Money unitPrice = Money.ofMinor(view.getPriceMinor(), view.getCurrency().strip());

      long modifierDelta = 0L;
      for (ModifierOptionView opt : lineModifiers) {
        modifierDelta = Math.addExact(modifierDelta, opt.getPriceDeltaMinor());
      }

      OrderLine line =
          new OrderLine(
              lineReq.menuItemId(), view.getName(), unitPrice, modifierDelta, lineReq.qty());
      for (ModifierOptionView opt : lineModifiers) {
        line.addModifier(
            new OrderLineModifier(opt.getId(), opt.getName(), opt.getPriceDeltaMinor()));
      }
      runningTotal = runningTotal.plus(Money.ofMinor(line.getLineTotalMinor(), currencyCode));
      linesToAdd.add(line);

      long effectiveUnitPrice = Math.addExact(unitPrice.amountMinor(), modifierDelta);
      evalLines.add(
          new EvalLine(
              line.getId(),
              lineReq.menuItemId(),
              view.getCategoryId(),
              effectiveUnitPrice,
              lineReq.qty()));
    }

    // 6. Phase 3 promotions engine (checkout only) + Phase 2 pricing.
    EvalResult evalResult = null;
    Money fixedDiscount;
    if (applyPromotions) {
      long manualDiscountMinor = (discountMinor != null) ? discountMinor : 0L;
      EvalInput evalInput =
          new EvalInput(
              evalLines, currencyCode, runningTotal, occurredAt, couponCode, manualDiscountMinor);
      evalResult = promotionEngine.evaluate(evalInput);
      fixedDiscount = evalResult.totalDiscount();
    } else {
      fixedDiscount = (discountMinor != null) ? Money.ofMinor(discountMinor, currencyCode) : null;
    }
    PriceBreakdown breakdown =
        taxChargeService.resolve(runningTotal, 0L, fixedDiscount, occurredAt);
    Money grandTotal = breakdown.grandTotal();
    if (!grandTotal.isPositive()) {
      throw new IllegalArgumentException(
          "Order grand total must be positive after discount/tax/service-charge; got "
              + grandTotal.amountMinor()
              + " "
              + grandTotal.currency().getCurrencyCode()
              + " — a fully-comped order is not a sale");
    }

    return new CartContext(currencyCode, linesToAdd, breakdown, itemViews, evalResult);
  }

  /**
   * Recomputes the promotions-aware {@link PriceBreakdown} for a parked order at pay time from
   * pre-loaded line views (ADR 0026). Loads fresh {@link MenuItemView}s for the parked lines (RLS-
   * scoped, chunked) so line-scope rule matching sees the item's CURRENT category assignment.
   */
  private EngineRecompute recomputeWithPromotions(
      Order order,
      List<OrderLineView> lineViews,
      String currencyCode,
      String couponCode,
      Instant occurredAt) {
    Currency currency = Currency.getInstance(currencyCode);

    List<UUID> menuItemIds = lineViews.stream().map(OrderLineView::getMenuItemId).toList();
    Map<UUID, MenuItemView> itemMap = new HashMap<>();
    for (int i = 0; i < menuItemIds.size(); i += 1000) {
      List<UUID> chunk = menuItemIds.subList(i, Math.min(i + 1000, menuItemIds.size()));
      menuItemRepository.findViewsByIds(chunk).forEach(v -> itemMap.put(v.getId(), v));
    }

    Money subtotal = Money.zero(currency);
    List<EvalLine> evalLines = new ArrayList<>(lineViews.size());
    for (OrderLineView lv : lineViews) {
      subtotal = subtotal.plus(Money.ofMinor(lv.getLineTotalMinor(), currencyCode));
      MenuItemView view = itemMap.get(lv.getMenuItemId());
      UUID categoryId = (view != null) ? view.getCategoryId() : null;
      // OrderLineView carries the BASE unit price only (modifiers are snapshotted separately);
      // recover the EFFECTIVE per-unit price from the persisted line total (exact by construction:
      // lineTotalMinor == effectiveUnitPriceMinor * qty).
      long effectiveUnitPrice = lv.getLineTotalMinor() / lv.getQty();
      evalLines.add(
          new EvalLine(
              lv.getId(), lv.getMenuItemId(), categoryId, effectiveUnitPrice, lv.getQty()));
    }

    long manualDiscountMinor = (order.getDiscountMinor() != null) ? order.getDiscountMinor() : 0L;
    EvalInput evalInput =
        new EvalInput(
            evalLines, currencyCode, subtotal, occurredAt, couponCode, manualDiscountMinor);
    EvalResult evalResult = promotionEngine.evaluate(evalInput);

    PriceBreakdown breakdown =
        taxChargeService.resolve(subtotal, 0L, evalResult.totalDiscount(), occurredAt);
    return new EngineRecompute(breakdown, evalResult);
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
    if (evalResult == null || evalResult.couponOutcome() == null) {
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
   * Persists the {@code applied_promotion} audit rows for a checkout/pay-parked call, same
   * transaction as the order/sale (ADR 0026). A {@code null} {@code saleId} is legal (the digital-
   * tender path defers revenue to capture — {@code PaymentCaptureWriter} stamps it in later). Does
   * nothing when there is nothing to persist (no engine result, or no rule-backed deduction — a
   * cart discounted ONLY by the manual layer produces zero rows here, since the manual discount has
   * no {@code ruleId}).
   */
  private void persistAppliedPromotions(
      UUID orderId, UUID saleId, EvalResult evalResult, String companyId) {
    if (evalResult == null || evalResult.deductions().isEmpty()) {
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
   * Validates that {@code tableId} is only supplied for DINE_IN orders and that the referenced
   * table belongs to the same business. RLS ensures the table is visible to the current tenant.
   */
  private void validateTableId(String orderType, UUID tableId, UUID businessId) {
    if (tableId == null) {
      return;
    }
    if (!DINE_IN.equals(orderType)) {
      throw new IllegalArgumentException(
          "tableId is only allowed for DINE_IN orders; orderType is " + orderType);
    }
    // Verify table exists and belongs to this business (RLS enforces tenant scope).
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

  /** Attaches lines (+ their modifier snapshots) to the order and sets company_id on each. */
  private void persistLines(Order order, List<OrderLine> linesToAdd, String companyId) {
    for (OrderLine line : linesToAdd) {
      line.setCompanyId(companyId);
      for (OrderLineModifier mod : line.getModifiers()) {
        mod.setCompanyId(companyId);
      }
      order.addLine(line);
    }
  }

  /** Resolves the effective order type, defaulting to DINE_IN when null. */
  private static String resolveOrderType(String orderType) {
    return (orderType == null || orderType.isBlank()) ? DINE_IN : orderType;
  }

  private CheckoutResult toIdempotentResult(OrderView view, String companyId) {
    List<OrderLineView> lineViews = lineRepository.findViewsByOrderId(view.getId());
    List<OrderLineResponse> lines = buildLineResponses(lineViews);
    return new CheckoutResult(toOrderResponse(view, lines), false);
  }

  /** Loads modifier snapshots for a batch of line views and builds {@link OrderLineResponse}s. */
  private List<OrderLineResponse> buildLineResponses(List<OrderLineView> lineViews) {
    if (lineViews.isEmpty()) {
      return List.of();
    }
    List<UUID> lineIds = lineViews.stream().map(OrderLineView::getId).toList();
    Map<UUID, List<OrderLineModifierView>> modByLine = new HashMap<>();
    for (int i = 0; i < lineIds.size(); i += 1000) {
      List<UUID> chunk = lineIds.subList(i, Math.min(i + 1000, lineIds.size()));
      modifierRepository
          .findViewsByOrderLineIds(chunk)
          .forEach(
              m -> modByLine.computeIfAbsent(m.getOrderLineId(), k -> new ArrayList<>()).add(m));
    }
    return lineViews.stream()
        .map(view -> toLineResponse(view, modByLine.getOrDefault(view.getId(), List.of())))
        .toList();
  }

  private static OrderLineResponse toLineResponse(
      OrderLineView view, List<OrderLineModifierView> modViews) {
    List<OrderLineModifierResponse> modResponses =
        modViews.stream()
            .map(
                m ->
                    new OrderLineModifierResponse(
                        m.getOptionId(), m.getNameSnapshot(), m.getPriceDeltaMinor()))
            .toList();
    return new OrderLineResponse(
        view.getMenuItemId(),
        view.getNameSnapshot(),
        view.getUnitPriceMinor(),
        view.getQty(),
        view.getLineTotalMinor(),
        modResponses);
  }

  private static OrderResponse toOrderResponse(OrderView view, List<OrderLineResponse> lines) {
    return new OrderResponse(
        view.getId(),
        view.getBusinessId(),
        view.getTotalMinor(),
        view.getCurrency().strip(),
        view.getSaleId(),
        lines,
        null,
        null,
        view.getStatus(),
        view.getOrderType(),
        view.getTableId());
  }

  /**
   * Deducts stock for tracked items when paying a parked order. Loads the current {@link
   * MenuItemView}s for the parked lines by their ids, then delegates to {@link
   * StockDeductionWriter#deductForLines} which runs in the caller's transaction ({@code
   * MANDATORY}).
   *
   * <p>The lines are represented as {@link OrderLineView}s at this point (the order's lines were
   * persisted at park time); we adapt them to the interface {@link StockDeductionWriter} expects by
   * constructing a list of lightweight adapters carrying only menuItemId and qty.
   */
  private void deductStockForParkedLines(List<OrderLineView> lineViews, String currencyCode) {
    if (lineViews.isEmpty()) {
      return;
    }
    // Load current item views to get stock_quantity state (RLS-scoped).
    List<UUID> menuItemIds = lineViews.stream().map(OrderLineView::getMenuItemId).toList();
    List<MenuItemView> menuItemViews = new ArrayList<>();
    for (int i = 0; i < menuItemIds.size(); i += 1000) {
      List<UUID> chunk = menuItemIds.subList(i, Math.min(i + 1000, menuItemIds.size()));
      menuItemViews.addAll(menuItemRepository.findViewsByIds(chunk));
    }

    // Build synthetic OrderLine-like objects for the deduction writer.
    // We use the domain OrderLine constructor but only need menuItemId + qty.
    // Instead, we pass the line view data via a list of transient OrderLine adapters.
    // Since StockDeductionWriter only calls getMenuItemId() and getQty(), we can build
    // real-enough OrderLine objects from the view data.
    List<OrderLine> adaptedLines = new ArrayList<>();
    for (OrderLineView lv : lineViews) {
      // Use Money.ofMinor with the order currency to satisfy the constructor.
      Money unitPrice = Money.ofMinor(lv.getUnitPriceMinor(), currencyCode);
      OrderLine adapted =
          new OrderLine(lv.getMenuItemId(), lv.getNameSnapshot(), unitPrice, lv.getQty());
      adaptedLines.add(adapted);
    }

    stockDeductionWriter.deductForLines(adaptedLines, menuItemViews);
  }

  /**
   * Immutable value holding the validated cart: resolved lines, breakdown, currency, the item views
   * used for stock-deduction tracking, and (checkout only) the promotions engine's result.
   */
  private record CartContext(
      String currencyCode,
      List<OrderLine> linesToAdd,
      PriceBreakdown breakdown,
      List<MenuItemView> itemViews,
      EvalResult evalResult) {}

  /**
   * The promotions-aware breakdown + engine result produced by {@link #recomputeWithPromotions}.
   */
  private record EngineRecompute(PriceBreakdown breakdown, EvalResult evalResult) {}
}
