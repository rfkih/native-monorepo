package id.co.nativeapp.restaurant.order.service;

import id.co.nativeapp.money.Money;
import id.co.nativeapp.restaurant.menu.projection.MenuItemView;
import id.co.nativeapp.restaurant.menu.projection.ModifierGroupView;
import id.co.nativeapp.restaurant.menu.projection.ModifierOptionView;
import id.co.nativeapp.restaurant.menu.repository.MenuItemRepository;
import id.co.nativeapp.restaurant.menu.repository.ModifierGroupRepository;
import id.co.nativeapp.restaurant.menu.repository.ModifierOptionRepository;
import id.co.nativeapp.restaurant.order.domain.Order;
import id.co.nativeapp.restaurant.order.domain.OrderLine;
import id.co.nativeapp.restaurant.order.domain.OrderLineModifier;
import id.co.nativeapp.restaurant.order.dto.CheckoutRequest;
import id.co.nativeapp.restaurant.order.dto.CheckoutResult;
import id.co.nativeapp.restaurant.order.dto.OrderLineModifierResponse;
import id.co.nativeapp.restaurant.order.dto.OrderLineRequest;
import id.co.nativeapp.restaurant.order.dto.OrderLineResponse;
import id.co.nativeapp.restaurant.order.dto.OrderResponse;
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
 *   <li>Snapshots name + unit price, computes line totals and subtotal as integer minor units
 *       ({@link Math#multiplyExact} / {@link Math#addExact} — never a float).
 *   <li>Resolves effective tax + service-charge rules (Phase 2 pricing) and computes a {@link
 *       PriceBreakdown} (subtotal → discount → taxableBase → serviceCharge → tax → grandTotal).
 *       Rejects a zero-or-negative grandTotal (a fully-comped order is not a sale → 400). The order
 *       total is stored as the grandTotal (the customer-pays amount).
 *   <li>Persists the {@link Order} + {@link OrderLine}s.
 *   <li>Calls {@link SaleWriter#recordInCurrentTx} with propagation {@code MANDATORY} so the sale
 *       row and its {@code SaleRecorded} outbox row join this same transaction. Order rows + sale
 *       row + outbox row all commit (or all roll back) as one physical transaction (rule 3, C1
 *       fix). The {@link id.co.nativeapp.restaurant.sale.service.PostOutboxHook PostOutboxHook}
 *       test seam fires inside this transaction, so a throwing hook proves atomicity.
 * </ol>
 *
 * <p>The {@code (company_id, idempotency_key)} UNIQUE constraint on {@code restaurant_order} (a
 * concurrent collision) is handled by the orchestrating {@link OrderService} via a separate-
 * transaction re-read — exactly the {@code SaleService} pattern.
 */
@Component
public class OrderWriter {

  private final OrderRepository orderRepository;
  private final OrderLineRepository lineRepository;
  private final OrderLineModifierRepository modifierRepository;
  private final MenuItemRepository menuItemRepository;
  private final ModifierGroupRepository modifierGroupRepository;
  private final ModifierOptionRepository modifierOptionRepository;
  private final SaleWriter saleWriter;
  private final PaymentWriter paymentWriter;
  private final TaxChargeService taxChargeService;

  public OrderWriter(
      OrderRepository orderRepository,
      OrderLineRepository lineRepository,
      OrderLineModifierRepository modifierRepository,
      MenuItemRepository menuItemRepository,
      ModifierGroupRepository modifierGroupRepository,
      ModifierOptionRepository modifierOptionRepository,
      SaleWriter saleWriter,
      PaymentWriter paymentWriter,
      TaxChargeService taxChargeService) {
    this.orderRepository = orderRepository;
    this.lineRepository = lineRepository;
    this.modifierRepository = modifierRepository;
    this.menuItemRepository = menuItemRepository;
    this.modifierGroupRepository = modifierGroupRepository;
    this.modifierOptionRepository = modifierOptionRepository;
    this.saleWriter = saleWriter;
    this.paymentWriter = paymentWriter;
    this.taxChargeService = taxChargeService;
  }

  /**
   * Persists an order, its lines, and its {@code SaleRecorded} outbox row in ONE transaction.
   *
   * @throws org.springframework.dao.DataIntegrityViolationException if a concurrent racer already
   *     inserted the {@code (company_id, idempotency_key)} row
   * @throws IllegalArgumentException if any requested menu item is unknown/inactive, or if the
   *     items span more than one currency
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

    // ------------------------------------------------------------------
    // 1. Load requested menu items (RLS-scoped; chunk IN by <=1000).
    // ------------------------------------------------------------------
    List<UUID> requestedIds = request.lines().stream().map(OrderLineRequest::menuItemId).toList();

    // Chunk IN clauses at <=1000 per the house convention (CLAUDE.md).
    List<MenuItemView> itemViews = new ArrayList<>();
    for (int i = 0; i < requestedIds.size(); i += 1000) {
      List<UUID> chunk = requestedIds.subList(i, Math.min(i + 1000, requestedIds.size()));
      itemViews.addAll(menuItemRepository.findViewsByIds(chunk));
    }

    Map<UUID, MenuItemView> itemMap =
        itemViews.stream().collect(Collectors.toMap(MenuItemView::getId, Function.identity()));

    // ------------------------------------------------------------------
    // 2. Validate: all requested items must exist, be active, available,
    //    belong to the request's business, and share the SAME currency
    //    (single-currency only — no mixing, rule 8).
    // ------------------------------------------------------------------
    Set<UUID> foundIds = itemMap.keySet();
    for (OrderLineRequest lineReq : request.lines()) {
      if (!foundIds.contains(lineReq.menuItemId())) {
        throw new IllegalArgumentException(
            "Menu item not found or not visible: " + lineReq.menuItemId());
      }
      MenuItemView view = itemMap.get(lineReq.menuItemId());
      if (!view.isActive()) {
        throw new IllegalArgumentException("Menu item is inactive: " + lineReq.menuItemId());
      }
      // Phase 3: reject unavailable (86'd) items.
      if (!view.isAvailable()) {
        throw new IllegalArgumentException(
            "Menu item is not available (86'd): " + lineReq.menuItemId());
      }
      // W4: reject items whose business_id does not match the checkout's businessId.
      // A cross-business item arriving here means the caller forged or mis-routed the
      // request — reject it as a domain error (mapped to 400 by ApiExceptionHandler).
      if (!request.businessId().equals(view.getBusinessId())) {
        throw new IllegalArgumentException(
            "Menu item "
                + lineReq.menuItemId()
                + " belongs to business "
                + view.getBusinessId()
                + ", not "
                + request.businessId());
      }
    }

    // Currency homogeneity check: all items must share one currency.
    Set<String> currencies =
        itemViews.stream().map(v -> v.getCurrency().strip()).collect(Collectors.toSet());
    if (currencies.size() != 1) {
      throw new IllegalArgumentException(
          "All menu items in one order must share the same currency; found: " + currencies);
    }
    String currencyCode = currencies.iterator().next();
    Currency currency = Currency.getInstance(currencyCode);

    // ------------------------------------------------------------------
    // 3. Validate + resolve modifier options (Phase 3).
    //    For each line that has selectedOptionIds:
    //    a) Load all requested option ids (chunk at <=1000).
    //    b) Verify each option exists (RLS-scoped), is available, and
    //       its group belongs to THIS menu item.
    //    c) Enforce group required/min_select/max_select constraints.
    //    d) Compute the modifier price delta for the line.
    // ------------------------------------------------------------------
    // Collect ALL distinct option ids across all lines for a bulk load.
    List<UUID> allOptionIds =
        request.lines().stream().flatMap(lr -> lr.selectedOptionIds().stream()).distinct().toList();

    Map<UUID, ModifierOptionView> optionMap = new HashMap<>();
    if (!allOptionIds.isEmpty()) {
      for (int i = 0; i < allOptionIds.size(); i += 1000) {
        List<UUID> chunk = allOptionIds.subList(i, Math.min(i + 1000, allOptionIds.size()));
        modifierOptionRepository.findViewsByIds(chunk).forEach(o -> optionMap.put(o.getId(), o));
      }
    }

    // Collect ALL distinct group ids referenced by the loaded options for a bulk load.
    List<UUID> allGroupIds =
        optionMap.values().stream().map(ModifierOptionView::getGroupId).distinct().toList();

    Map<UUID, ModifierGroupView> groupMap = new HashMap<>();
    if (!allGroupIds.isEmpty()) {
      for (int i = 0; i < allGroupIds.size(); i += 1000) {
        List<UUID> chunk = allGroupIds.subList(i, Math.min(i + 1000, allGroupIds.size()));
        modifierGroupRepository.findViewsByIds(chunk).forEach(g -> groupMap.put(g.getId(), g));
      }
    }

    // Per-line modifier validation and delta computation.
    // modifiersByLine[i] = list of (optionId, nameSnapshot, priceDeltaMinor) for line i.
    List<List<ModifierOptionView>> resolvedModifiersByLine =
        new ArrayList<>(request.lines().size());

    for (OrderLineRequest lineReq : request.lines()) {
      List<UUID> selectedIds = lineReq.selectedOptionIds();
      List<ModifierOptionView> resolved = new ArrayList<>(selectedIds.size());

      if (!selectedIds.isEmpty()) {
        // Load required groups for this item to enforce required/min/max constraints.
        List<ModifierGroupView> requiredGroups =
            modifierGroupRepository.findRequiredViewsByMenuItemId(lineReq.menuItemId());

        // Validate each selected option.
        Map<UUID, List<UUID>> selectionsByGroup = new HashMap<>();
        for (UUID optionId : selectedIds) {
          ModifierOptionView option = optionMap.get(optionId);
          if (option == null) {
            throw new IllegalArgumentException(
                "Modifier option not found or not visible: " + optionId);
          }
          if (!option.isAvailable()) {
            throw new IllegalArgumentException(
                "Modifier option is not available: " + optionId + " (" + option.getName() + ")");
          }
          ModifierGroupView group = groupMap.get(option.getGroupId());
          if (group == null || !group.getMenuItemId().equals(lineReq.menuItemId())) {
            throw new IllegalArgumentException(
                "Modifier option "
                    + optionId
                    + " does not belong to menu item "
                    + lineReq.menuItemId());
          }
          selectionsByGroup
              .computeIfAbsent(option.getGroupId(), k -> new ArrayList<>())
              .add(optionId);
          resolved.add(option);
        }

        // Enforce max_select per group.
        for (Map.Entry<UUID, List<UUID>> entry : selectionsByGroup.entrySet()) {
          ModifierGroupView group = groupMap.get(entry.getKey());
          if (group != null && entry.getValue().size() > group.getMaxSelect()) {
            throw new IllegalArgumentException(
                "Too many selections for modifier group '"
                    + group.getName()
                    + "': max "
                    + group.getMaxSelect()
                    + ", got "
                    + entry.getValue().size());
          }
        }

        // Enforce required groups / min_select.
        for (ModifierGroupView rg : requiredGroups) {
          List<UUID> chosen = selectionsByGroup.getOrDefault(rg.getId(), List.of());
          if (chosen.size() < rg.getMinSelect()) {
            throw new IllegalArgumentException(
                "Required modifier group '"
                    + rg.getName()
                    + "' needs at least "
                    + rg.getMinSelect()
                    + " selection(s), got "
                    + chosen.size());
          }
        }
      } else {
        // No options selected — still enforce required groups.
        List<ModifierGroupView> requiredGroups =
            modifierGroupRepository.findRequiredViewsByMenuItemId(lineReq.menuItemId());
        for (ModifierGroupView rg : requiredGroups) {
          if (rg.getMinSelect() > 0) {
            throw new IllegalArgumentException(
                "Required modifier group '"
                    + rg.getName()
                    + "' needs at least "
                    + rg.getMinSelect()
                    + " selection(s), got 0");
          }
        }
      }
      resolvedModifiersByLine.add(resolved);
    }

    // ------------------------------------------------------------------
    // 3b. Compute line totals + subtotal (integer minor units, exact).
    //     effectiveUnitPrice = baseUnitPrice + Σ priceDeltaMinor (modifiers)
    //     lineTotal = effectiveUnitPrice × qty
    // ------------------------------------------------------------------
    Money runningTotal = Money.zero(currency);
    List<OrderLine> linesToAdd = new ArrayList<>(request.lines().size());

    for (int i = 0; i < request.lines().size(); i++) {
      OrderLineRequest lineReq = request.lines().get(i);
      List<ModifierOptionView> lineModifiers = resolvedModifiersByLine.get(i);
      MenuItemView view = itemMap.get(lineReq.menuItemId());
      // Strip CHAR(3) padding from the DB.
      Money unitPrice = Money.ofMinor(view.getPriceMinor(), view.getCurrency().strip());

      // Sum modifier deltas (exact integer math, no float).
      long modifierDelta = 0L;
      for (ModifierOptionView opt : lineModifiers) {
        modifierDelta = Math.addExact(modifierDelta, opt.getPriceDeltaMinor());
      }

      OrderLine line =
          new OrderLine(
              lineReq.menuItemId(), view.getName(), unitPrice, modifierDelta, lineReq.qty());

      // Attach modifier snapshots (receipt-reproducible).
      for (ModifierOptionView opt : lineModifiers) {
        OrderLineModifier snap =
            new OrderLineModifier(opt.getId(), opt.getName(), opt.getPriceDeltaMinor());
        line.addModifier(snap);
      }

      runningTotal = runningTotal.plus(Money.ofMinor(line.getLineTotalMinor(), currencyCode));
      linesToAdd.add(line);
    }

    // ------------------------------------------------------------------
    // 3c. Phase 2 pricing: resolve tax + service charge, compute breakdown.
    //
    //     The breakdown resolves effective tax_charge_rule rows (RLS-scoped to this
    //     tenant) at the order's occurred-at date. If any resolved rule is
    //     ILLUSTRATIVE_PLACEHOLDER, usesIllustrativeRules=true is set on SaleRecorded.
    //
    //     Discount: currently no order-level discount request field; pass 0 bp and no
    //     fixed discount (zero discount, clamp has no effect). A future CheckoutRequest
    //     field can wire discountBp / fixedDiscountMinor here without changing the formula.
    //
    //     grandTotal is the customer-pays amount (taxableBase + serviceCharge + tax).
    //     Reject a zero-or-negative grandTotal: a fully-comped order is not a sale.
    // ------------------------------------------------------------------
    Instant now = Instant.now();
    // Thread the optional order-level discount into the pricing formula.
    // A non-null discountMinor is a fixed discount (overrides the percent discount).
    // A null discountMinor means no discount (0 bp, no fixed discount).
    Money fixedDiscount =
        (request.discountMinor() != null)
            ? Money.ofMinor(request.discountMinor(), currencyCode)
            : null;
    long discountBp = 0L; // no percent discount from the API; only fixed-amount supported
    PriceBreakdown breakdown =
        taxChargeService.resolve(runningTotal, discountBp, fixedDiscount, now);
    Money grandTotal = breakdown.grandTotal();
    if (!grandTotal.isPositive()) {
      throw new IllegalArgumentException(
          "Order grand total must be positive after discount/tax/service-charge; got "
              + grandTotal.amountMinor()
              + " "
              + grandTotal.currency().getCurrencyCode()
              + " — a fully-comped order is not a sale");
    }

    // ------------------------------------------------------------------
    // 4. Persist order + lines + modifier snapshots (total = GRAND TOTAL).
    // ------------------------------------------------------------------
    Order order = new Order(request.businessId(), grandTotal, now, request.idempotencyKey());
    order.setCompanyId(companyId);
    for (OrderLine line : linesToAdd) {
      line.setCompanyId(companyId);
      // Set company_id on each modifier snapshot (Auditable + RLS rule 4/5).
      for (OrderLineModifier mod : line.getModifiers()) {
        mod.setCompanyId(companyId);
      }
      order.addLine(line);
    }
    Order saved = orderRepository.saveAndFlush(order);

    // ------------------------------------------------------------------
    // 5. Record the sale / payment based on tender type (ADR 0006).
    //
    //    CASH (or no payment): record the sale synchronously — exactly one
    //    SaleRecorded outbox row is emitted in THIS transaction.  Revenue
    //    is recognised immediately together with the order.
    //
    //    DIGITAL (QRIS / CARD): do NOT record a sale at checkout.  Revenue
    //    is deferred to the explicit capture call (revenue-at-capture
    //    invariant, ADR 0006).  Only a PENDING payment row is written here;
    //    the order stays without a sale_id until capture commits it.
    //    SaleRecorded is emitted only at capture time.
    // ------------------------------------------------------------------
    OrderResponse response;
    boolean isDigitalPayment =
        request.payment() != null && request.payment().tenderType().isDigital();

    if (!isDigitalPayment) {
      // CASH or no-payment path: record sale synchronously in this transaction.
      // Thread the tender type so finance can route the GL clearing account (ADR 0006, slice 2).
      // Thread the Phase 2 breakdown so finance can build the multi-line GL journal entry.
      // amountMinor = grandTotal (the customer-pays amount; @Positive validates this).
      String tenderTypeName =
          (request.payment() != null) ? request.payment().tenderType().name() : null;
      RecordSaleCommand saleCommand =
          new RecordSaleCommand(
              request.businessId(),
              grandTotal.amountMinor(),
              currencyCode,
              now,
              request.idempotencyKey(),
              tenderTypeName,
              breakdown);
      RecordSaleResult saleResult = saleWriter.recordInCurrentTx(saleCommand);

      // Link the sale id back to the order → status COMPLETED.
      saved.linkSale(saleResult.sale().id());
      orderRepository.saveAndFlush(saved);

      response = OrderResponse.from(saved, breakdown);
      if (request.payment() != null) {
        // CASH: capture synchronously against the just-recorded sale.
        // The payment amount is the grandTotal (what the customer owes).
        PaymentInstruction instruction =
            new PaymentInstruction(
                saved.getId(),
                request.businessId(),
                request.payment().tenderType(),
                grandTotal,
                request.payment().tenderedMinor(),
                request.idempotencyKey() + ":pay");
        PaymentResponse paymentResponse =
            paymentWriter.captureCashInCurrentTx(instruction, saleResult.sale().id(), now);
        response = response.withPayment(paymentResponse);
      }
    } else {
      // DIGITAL path: persist a PENDING payment; no sale, no SaleRecorded yet.
      // The order stays in AWAITING_PAYMENT status (no sale_id until capture).
      saved.markAwaitingPayment();
      orderRepository.saveAndFlush(saved);

      PaymentInstruction instruction =
          new PaymentInstruction(
              saved.getId(),
              request.businessId(),
              request.payment().tenderType(),
              grandTotal,
              null, // digital tenders carry no tendered amount
              request.idempotencyKey() + ":pay");
      PaymentResponse paymentResponse =
          paymentWriter.recordPendingDigitalInCurrentTx(instruction, now);
      response = OrderResponse.from(saved, breakdown).withPayment(paymentResponse);
    }

    return new CheckoutResult(response, true);
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

  private CheckoutResult toIdempotentResult(OrderView view, String companyId) {
    List<OrderLineView> lineViews = lineRepository.findViewsByOrderId(view.getId());
    List<OrderLineResponse> lines = buildLineResponses(lineViews);
    return new CheckoutResult(toOrderResponse(view, lines), false);
  }

  /**
   * Loads modifier snapshots for a batch of line views and builds {@link OrderLineResponse}s. Lives
   * in the service layer so the ArchUnit rule ({@code projection} accessed only by {@code service}
   * and {@code repository}) is respected.
   */
  private List<OrderLineResponse> buildLineResponses(List<OrderLineView> lineViews) {
    if (lineViews.isEmpty()) {
      return List.of();
    }
    List<UUID> lineIds = lineViews.stream().map(OrderLineView::getId).toList();
    // Chunk modifier load at <=1000.
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

  /**
   * Maps a read-path {@link OrderLineView} projection (plus its modifier projections) to the
   * response shape. Lives in the service layer so the ArchUnit rule ({@code projection} accessed
   * only by {@code service} and {@code repository}) is respected — the {@code dto} layer must not
   * depend on {@code projection}.
   */
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
    // An existing-order re-read (idempotent retry / conflict recovery) returns the order without a
    // payment block or breakdown — the breakdown was returned on the original checkout response.
    return new OrderResponse(
        view.getId(),
        view.getBusinessId(),
        view.getTotalMinor(),
        view.getCurrency().strip(),
        view.getSaleId(),
        lines,
        null,
        null);
  }
}
