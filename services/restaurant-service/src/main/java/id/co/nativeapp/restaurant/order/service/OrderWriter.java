package id.co.nativeapp.restaurant.order.service;

import id.co.nativeapp.money.Money;
import id.co.nativeapp.restaurant.menu.projection.MenuItemView;
import id.co.nativeapp.restaurant.menu.repository.MenuItemRepository;
import id.co.nativeapp.restaurant.order.domain.Order;
import id.co.nativeapp.restaurant.order.domain.OrderLine;
import id.co.nativeapp.restaurant.order.dto.CheckoutRequest;
import id.co.nativeapp.restaurant.order.dto.CheckoutResult;
import id.co.nativeapp.restaurant.order.dto.OrderLineRequest;
import id.co.nativeapp.restaurant.order.dto.OrderLineResponse;
import id.co.nativeapp.restaurant.order.dto.OrderResponse;
import id.co.nativeapp.restaurant.order.projection.OrderLineView;
import id.co.nativeapp.restaurant.order.projection.OrderView;
import id.co.nativeapp.restaurant.order.repository.OrderLineRepository;
import id.co.nativeapp.restaurant.order.repository.OrderRepository;
import id.co.nativeapp.restaurant.payment.dto.PaymentResponse;
import id.co.nativeapp.restaurant.payment.service.PaymentInstruction;
import id.co.nativeapp.restaurant.payment.service.PaymentWriter;
import id.co.nativeapp.restaurant.sale.dto.RecordSaleCommand;
import id.co.nativeapp.restaurant.sale.dto.RecordSaleResult;
import id.co.nativeapp.restaurant.sale.service.SaleWriter;
import id.co.nativeapp.tenant.RlsAutoApplyAspect;
import id.co.nativeapp.tenant.TenantContext;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Currency;
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
 *   <li>Snapshots name + unit price, computes line totals and order total as integer minor units
 *       ({@link Math#multiplyExact} / {@link Math#addExact} — never a float).
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
  private final MenuItemRepository menuItemRepository;
  private final SaleWriter saleWriter;
  private final PaymentWriter paymentWriter;

  public OrderWriter(
      OrderRepository orderRepository,
      OrderLineRepository lineRepository,
      MenuItemRepository menuItemRepository,
      SaleWriter saleWriter,
      PaymentWriter paymentWriter) {
    this.orderRepository = orderRepository;
    this.lineRepository = lineRepository;
    this.menuItemRepository = menuItemRepository;
    this.saleWriter = saleWriter;
    this.paymentWriter = paymentWriter;
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
    // 2. Validate: all requested items must exist, be active, belong to
    //    the request's business, and share the SAME currency (single-
    //    currency only — no mixing, rule 8).
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
    // 3. Compute line totals + order total (integer minor units, exact).
    // ------------------------------------------------------------------
    Money runningTotal = Money.zero(currency);
    List<OrderLine> linesToAdd = new ArrayList<>(request.lines().size());

    for (OrderLineRequest lineReq : request.lines()) {
      MenuItemView view = itemMap.get(lineReq.menuItemId());
      // Strip CHAR(3) padding from the DB.
      Money unitPrice = Money.ofMinor(view.getPriceMinor(), view.getCurrency().strip());
      OrderLine line =
          new OrderLine(lineReq.menuItemId(), view.getName(), unitPrice, lineReq.qty());
      runningTotal = runningTotal.plus(Money.ofMinor(line.getLineTotalMinor(), currencyCode));
      linesToAdd.add(line);
    }

    // ------------------------------------------------------------------
    // 4. Persist order + lines.
    // ------------------------------------------------------------------
    Instant now = Instant.now();
    Order order = new Order(request.businessId(), runningTotal, now, request.idempotencyKey());
    order.setCompanyId(companyId);
    for (OrderLine line : linesToAdd) {
      line.setCompanyId(companyId);
      order.addLine(line);
    }
    Order saved = orderRepository.saveAndFlush(order);

    // ------------------------------------------------------------------
    // 5. Record the sale by reusing SaleWriter — exactly one SaleRecorded
    //    outbox row emitted (rule 3), atomically within THIS transaction.
    //    recordInCurrentTx has propagation MANDATORY: it joins our
    //    REQUIRES_NEW transaction, so order + lines + sale + outbox row
    //    all commit (or roll back) as one physical unit (C1 fix).
    // ------------------------------------------------------------------
    // Thread the tender type from the checkout request so the SaleRecorded outbox event
    // carries it (ADR 0006 slice 2 — finance routes GL clearing account by tender).
    // null when no payment block is present (legacy / direct-sale path; finance defaults to
    // CASH_CLEARING, keeping the carwash / SaleService paths unchanged).
    String tenderTypeName =
        (request.payment() != null) ? request.payment().tenderType().name() : null;
    RecordSaleCommand saleCommand =
        new RecordSaleCommand(
            request.businessId(),
            runningTotal.amountMinor(),
            currencyCode,
            now,
            request.idempotencyKey(),
            tenderTypeName);
    RecordSaleResult saleResult = saleWriter.recordInCurrentTx(saleCommand);

    // Link the sale id back to the order (triggers an UPDATE on restaurant_order).
    saved.linkSale(saleResult.sale().id());
    orderRepository.saveAndFlush(saved);

    // ------------------------------------------------------------------
    // 6. Optional payment (ADR 0006). Cash captures synchronously, in THIS
    //    transaction, against the sale just recorded — so order + lines +
    //    sale + outbox + payment all commit (or roll back) together.
    //    Digital tenders are not captured at checkout (they are PENDING
    //    until capture) — that flow lands with the capture endpoint.
    // ------------------------------------------------------------------
    OrderResponse response = OrderResponse.from(saved);
    if (request.payment() != null) {
      PaymentInstruction instruction =
          new PaymentInstruction(
              saved.getId(),
              request.businessId(),
              request.payment().tenderType(),
              runningTotal,
              request.payment().tenderedMinor(),
              request.idempotencyKey() + ":pay");
      PaymentResponse paymentResponse =
          paymentWriter.captureCashInCurrentTx(instruction, saleResult.sale().id(), now);
      response = response.withPayment(paymentResponse);
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
              List<OrderLineResponse> lines =
                  lineRepository.findViewsByOrderId(view.getId()).stream()
                      .map(OrderWriter::toLineResponse)
                      .toList();
              return toOrderResponse(view, lines);
            });
  }

  private CheckoutResult toIdempotentResult(OrderView view, String companyId) {
    List<OrderLineResponse> lines =
        lineRepository.findViewsByOrderId(view.getId()).stream()
            .map(OrderWriter::toLineResponse)
            .toList();
    return new CheckoutResult(toOrderResponse(view, lines), false);
  }

  /**
   * Maps a read-path {@link OrderLineView} projection to the response shape. Lives in the service
   * layer so the ArchUnit rule ({@code projection} accessed only by {@code service} and {@code
   * repository}) is respected — the {@code dto} layer must not depend on {@code projection}.
   */
  private static OrderLineResponse toLineResponse(OrderLineView view) {
    return new OrderLineResponse(
        view.getMenuItemId(),
        view.getNameSnapshot(),
        view.getUnitPriceMinor(),
        view.getQty(),
        view.getLineTotalMinor());
  }

  private static OrderResponse toOrderResponse(OrderView view, List<OrderLineResponse> lines) {
    // An existing-order re-read (idempotent retry / conflict recovery) returns the order without a
    // payment block — the payment was returned on the original successful checkout response.
    return new OrderResponse(
        view.getId(),
        view.getBusinessId(),
        view.getTotalMinor(),
        view.getCurrency().strip(),
        view.getSaleId(),
        lines,
        null);
  }
}
