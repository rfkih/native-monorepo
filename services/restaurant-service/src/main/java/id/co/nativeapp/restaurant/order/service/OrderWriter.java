package id.co.nativeapp.restaurant.order.service;

import id.co.nativeapp.money.Money;
import id.co.nativeapp.restaurant.menu.projection.MenuItemView;
import id.co.nativeapp.restaurant.menu.projection.ModifierOptionView;
import id.co.nativeapp.restaurant.menu.repository.MenuItemRepository;
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
import id.co.nativeapp.restaurant.payment.dto.PaymentResponse;
import id.co.nativeapp.restaurant.payment.service.PaymentInstruction;
import id.co.nativeapp.restaurant.payment.service.PaymentWriter;
import id.co.nativeapp.restaurant.pricing.domain.PriceBreakdown;
import id.co.nativeapp.restaurant.pricing.service.TaxChargeService;
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
 *   <li>Idempotency short-circuit: returns the existing order if the key is already present.
 *   <li>Loads the requested menu items (RLS-scoped) and validates all are active, share the same
 *       currency (single-currency only — no mixed-currency orders, rule 8), and belong to the same
 *       business as the request ({@code businessId} check — W4).
 *   <li>Validates {@code tableId}: only allowed for DINE_IN; must belong to the same business.
 *   <li>Snapshots name + unit price, computes line totals and subtotal as integer minor units
 *       ({@link Math#multiplyExact} / {@link Math#addExact} — never a float).
 *   <li>Resolves effective tax + service-charge rules (Phase 2 pricing) and computes a {@link
 *       PriceBreakdown} (subtotal → discount → taxableBase → serviceCharge → tax → grandTotal).
 *       Rejects a zero-or-negative grandTotal (a fully-comped order is not a sale → 400).
 *   <li>Persists the {@link Order} + {@link OrderLine}s.
 *   <li>Calls {@link SaleWriter#recordInCurrentTx} with propagation {@code MANDATORY} so the sale
 *       row and its {@code SaleRecorded} outbox row join this same transaction (rule 3, C1 fix).
 * </ol>
 *
 * <p><strong>Park = saved cart, no revenue.</strong> {@link #park} persists an order in {@code
 * PARKED} status — identical validation to checkout, but writes NO sale, NO payment, NO outbox row.
 * Revenue is recognised only when {@link #payParked} finalises the order.
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

  public OrderWriter(
      OrderRepository orderRepository,
      OrderLineRepository lineRepository,
      OrderLineModifierRepository modifierRepository,
      MenuItemRepository menuItemRepository,
      ModifierValidationReader modifierValidator,
      SaleWriter saleWriter,
      PaymentWriter paymentWriter,
      TaxChargeService taxChargeService,
      RestaurantTableRepository tableRepository) {
    this.orderRepository = orderRepository;
    this.lineRepository = lineRepository;
    this.modifierRepository = modifierRepository;
    this.menuItemRepository = menuItemRepository;
    this.modifierValidator = modifierValidator;
    this.saleWriter = saleWriter;
    this.paymentWriter = paymentWriter;
    this.taxChargeService = taxChargeService;
    this.tableRepository = tableRepository;
  }

  /**
   * Persists an order, its lines, and its {@code SaleRecorded} outbox row in ONE transaction.
   *
   * @throws org.springframework.dao.DataIntegrityViolationException if a concurrent racer already
   *     inserted the {@code (company_id, idempotency_key)} row
   * @throws IllegalArgumentException if any requested menu item is unknown/inactive, or if the
   *     items span more than one currency, or if tableId is supplied for a non-DINE_IN order, or if
   *     tableId does not belong to the request's business
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public CheckoutResult checkout(CheckoutRequest request) {
    String companyId = TenantContext.require().companyId();

    // Idempotency fast path: return the existing order without a second SaleRecorded.
    Optional<OrderView> existing =
        orderRepository.findViewByIdempotencyKey(request.idempotencyKey());
    if (existing.isPresent()) {
      return toIdempotentResult(existing.get(), companyId);
    }

    String orderType = resolveOrderType(request.orderType());
    UUID tableId = request.tableId();

    // ------------------------------------------------------------------
    // 1. Load requested menu items (RLS-scoped; chunk IN by <=1000).
    // ------------------------------------------------------------------
    CartContext cart = buildCart(request.businessId(), request.lines(), request.discountMinor());

    // ------------------------------------------------------------------
    // 2. Validate order type + table id (Phase 4).
    // ------------------------------------------------------------------
    validateTableId(orderType, tableId, request.businessId());

    // ------------------------------------------------------------------
    // 3. Persist order + lines + modifier snapshots (total = GRAND TOTAL).
    // ------------------------------------------------------------------
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
    order.setCompanyId(companyId);
    persistLines(order, cart.linesToAdd(), companyId);
    Order saved = orderRepository.saveAndFlush(order);

    // ------------------------------------------------------------------
    // 4. Record the sale / payment based on tender type (ADR 0006).
    // ------------------------------------------------------------------
    OrderResponse response;
    boolean isDigitalPayment =
        request.payment() != null && request.payment().tenderType().isDigital();

    if (!isDigitalPayment) {
      // CASH or no-payment path: record sale synchronously in this transaction.
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
      // DIGITAL path: persist a PENDING payment; no sale, no SaleRecorded yet.
      saved.markAwaitingPayment();
      orderRepository.saveAndFlush(saved);

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
   * Parks a cart as a PARKED order — no sale, no payment, no outbox row. Idempotent on {@code
   * (company_id, idempotency_key)} like checkout.
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

    // Idempotency fast path.
    Optional<OrderView> existing =
        orderRepository.findViewByIdempotencyKey(request.idempotencyKey());
    if (existing.isPresent()) {
      return toIdempotentResult(existing.get(), companyId);
    }

    String orderType = resolveOrderType(request.orderType());
    UUID tableId = request.tableId();

    // Validate items + pricing (same as checkout).
    CartContext cart = buildCart(request.businessId(), request.lines(), request.discountMinor());

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
   * Finalises a PARKED order: records the Sale + optional payment, transitions PARKED → COMPLETED
   * (cash / no-payment) or AWAITING_PAYMENT (digital). This is the moment revenue is recognised for
   * a parked order.
   *
   * <p>Idempotent: re-paying a COMPLETED order returns the existing state without side effects;
   * re-paying an AWAITING_PAYMENT order delegates idempotency to the payment layer.
   *
   * @throws IllegalArgumentException if the order is not found, not PARKED, or already paid
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public OrderResponse payParked(UUID orderId, PayParkedRequest request) {
    String companyId = TenantContext.require().companyId();

    // Load the full aggregate (write path — needs all fields to check status + total).
    Order order =
        orderRepository
            .findById(orderId)
            .orElseThrow(() -> new IllegalArgumentException("Order not found: " + orderId));

    if ("COMPLETED".equals(order.getStatus())) {
      // Idempotent: already paid — return the current state without side effects.
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

    Instant now = Instant.now();
    String currencyCode = order.getTotal().currency().getCurrencyCode();
    String saleIdempotencyKey = orderId + ":park-sale";

    // Recompute the price breakdown from the persisted lines (H1 fix).
    // The subtotal is the sum of line totals; the discount is whatever was stored on the order at
    // park time. This reproduces the same breakdown shape as the equivalent direct checkout.
    PriceBreakdown breakdown = recomputeBreakdown(order, currencyCode, now);

    boolean isDigitalPayment =
        request.payment() != null && request.payment().tenderType().isDigital();

    if (!isDigitalPayment) {
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
      // DIGITAL: persist a PENDING payment; defer revenue to capture.
      order.markAwaitingPayment(); // PARKED → AWAITING_PAYMENT
      orderRepository.saveAndFlush(order);

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
   * subtotal, pricing). Shared between checkout and park so they cannot diverge.
   */
  private CartContext buildCart(
      UUID businessId, List<OrderLineRequest> lineRequests, Long discountMinor) {

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

    // 5. Compute line totals + subtotal.
    Money runningTotal = Money.zero(currency);
    List<OrderLine> linesToAdd = new ArrayList<>(lineRequests.size());
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
    }

    // 6. Phase 2 pricing.
    Money fixedDiscount =
        (discountMinor != null) ? Money.ofMinor(discountMinor, currencyCode) : null;
    PriceBreakdown breakdown =
        taxChargeService.resolve(runningTotal, 0L, fixedDiscount, Instant.now());
    Money grandTotal = breakdown.grandTotal();
    if (!grandTotal.isPositive()) {
      throw new IllegalArgumentException(
          "Order grand total must be positive after discount/tax/service-charge; got "
              + grandTotal.amountMinor()
              + " "
              + grandTotal.currency().getCurrencyCode()
              + " — a fully-comped order is not a sale");
    }

    return new CartContext(currencyCode, linesToAdd, breakdown);
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
   * Recomputes the {@link PriceBreakdown} for a parked order at pay time. The subtotal is the sum
   * of the persisted line totals; the fixed discount is whatever was stored on the order at park
   * time. This produces the same breakdown shape as an equivalent direct checkout of the same cart
   * (H1 fix — parked-then-paid SaleRecorded carries identical breakdown legs).
   */
  private PriceBreakdown recomputeBreakdown(Order order, String currencyCode, Instant now) {
    List<OrderLineView> lineViews = lineRepository.findViewsByOrderId(order.getId());
    Money subtotal = Money.zero(Currency.getInstance(currencyCode));
    for (OrderLineView lv : lineViews) {
      subtotal = subtotal.plus(Money.ofMinor(lv.getLineTotalMinor(), currencyCode));
    }
    Money fixedDiscount =
        (order.getDiscountMinor() != null)
            ? Money.ofMinor(order.getDiscountMinor(), currencyCode)
            : null;
    return taxChargeService.resolve(subtotal, 0L, fixedDiscount, now);
  }

  /** Immutable value holding the validated cart: resolved lines, breakdown, and currency. */
  private record CartContext(
      String currencyCode, List<OrderLine> linesToAdd, PriceBreakdown breakdown) {}
}
