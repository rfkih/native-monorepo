package id.co.nativeapp.restaurant.payment.service;

import id.co.nativeapp.money.Money;
import id.co.nativeapp.restaurant.menu.projection.MenuItemView;
import id.co.nativeapp.restaurant.menu.repository.MenuItemRepository;
import id.co.nativeapp.restaurant.menu.service.StockDeductionWriter;
import id.co.nativeapp.restaurant.order.domain.Order;
import id.co.nativeapp.restaurant.order.domain.OrderLine;
import id.co.nativeapp.restaurant.order.projection.OrderLineView;
import id.co.nativeapp.restaurant.order.repository.OrderLineRepository;
import id.co.nativeapp.restaurant.order.repository.OrderRepository;
import id.co.nativeapp.restaurant.payment.domain.Payment;
import id.co.nativeapp.restaurant.payment.dto.PaymentResponse;
import id.co.nativeapp.restaurant.payment.repository.PaymentRepository;
import id.co.nativeapp.restaurant.sale.dto.RecordSaleCommand;
import id.co.nativeapp.restaurant.sale.dto.RecordSaleResult;
import id.co.nativeapp.restaurant.sale.service.SaleWriter;
import id.co.nativeapp.tenant.TenantContext;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the single {@code @Transactional} unit of work that captures a PENDING digital payment,
 * records the Sale, emits {@code SaleRecorded}, and links the order — atomically (ADR 0006).
 *
 * <p>A distinct bean from {@link PaymentCaptureService} so the method is invoked through the Spring
 * proxy: the {@code @Transactional} advice and the {@code RlsAutoApplyAspect} both engage (same
 * pattern as {@code SaleWriter}/{@code OrderWriter}).
 *
 * <p><strong>Atomicity.</strong> All of the following commit (or all roll back) in ONE transaction:
 * the stock deduction for tracked order lines (via {@link StockDeductionWriter}), the {@link
 * Payment#capture(UUID, Instant)} state transition, the {@link SaleWriter#recordInCurrentTx} call
 * that writes the {@code sale} row + {@code SaleRecorded} outbox row, and the {@link
 * Order#linkSale(UUID)} update that moves the order to {@code COMPLETED}.
 *
 * <p><strong>Idempotency.</strong> The capture is idempotent: if the payment is already {@link
 * Payment.Status#CAPTURED} (re-delivery), the method returns immediately with no side effects — no
 * second stock deduction, no second sale, no second {@code SaleRecorded}. The early return happens
 * before any stock deduction, so re-delivering the capture cannot double-deduct stock.
 */
@Component
public class PaymentCaptureWriter {

  private final PaymentRepository paymentRepository;
  private final OrderRepository orderRepository;
  private final OrderLineRepository orderLineRepository;
  private final MenuItemRepository menuItemRepository;
  private final StockDeductionWriter stockDeductionWriter;
  private final SaleWriter saleWriter;

  public PaymentCaptureWriter(
      PaymentRepository paymentRepository,
      OrderRepository orderRepository,
      OrderLineRepository orderLineRepository,
      MenuItemRepository menuItemRepository,
      StockDeductionWriter stockDeductionWriter,
      SaleWriter saleWriter) {
    this.paymentRepository = paymentRepository;
    this.orderRepository = orderRepository;
    this.orderLineRepository = orderLineRepository;
    this.menuItemRepository = menuItemRepository;
    this.stockDeductionWriter = stockDeductionWriter;
    this.saleWriter = saleWriter;
  }

  /**
   * Captures a {@link Payment.Status#PENDING} digital payment: records the Sale, emits {@code
   * SaleRecorded}, transitions the payment to {@link Payment.Status#CAPTURED}, and links the order.
   * Runs in {@code REQUIRES_NEW} so it has its own transaction boundary (same pattern as {@link
   * SaleWriter#create}).
   *
   * @param paymentId the payment to capture (must be PENDING and belong to the current tenant)
   * @param capturedAt the moment of capture (server-side clock)
   * @return the captured payment response
   * @throws IllegalArgumentException if the payment is not found, not PENDING, or is not a digital
   *     tender
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public PaymentResponse capture(UUID paymentId, Instant capturedAt) {
    TenantContext.Tenant tenant = TenantContext.require();
    String companyId = tenant.companyId();

    // Load the full aggregate (write path).
    Payment payment =
        paymentRepository
            .findById(paymentId)
            .orElseThrow(() -> new IllegalArgumentException("Payment not found: " + paymentId));

    if (!payment.getTenderType().isDigital()) {
      throw new IllegalArgumentException(
          "Only digital payments can be captured via this endpoint; tender="
              + payment.getTenderType());
    }

    if (payment.getStatus() == Payment.Status.CAPTURED) {
      // Idempotent re-delivery: return the existing state without side effects.
      return PaymentResponse.from(payment);
    }

    // Payment.capture() enforces the PENDING guard — throws if already VOIDED/REFUNDED/etc.
    // The sale idempotency key is derived from the immutable payment id (a UUID), not the
    // client-supplied idempotency_key string. Using the payment id eliminates any risk of
    // collisions from client keys that happen to contain ":pay" as a substring, and makes the
    // capture idempotency self-contained: a re-delivered capture for the SAME payment id always
    // resolves to the same sale key — no second SaleRecorded (M3 / ADR 0006).
    String saleIdempotencyKey = payment.getId() + ":capture-sale";

    // Deduct stock for tracked order lines — same transaction as sale + payment state change.
    // An insufficient-stock shortfall throws InsufficientStockException, rolling back everything
    // (no sale, no SaleRecorded, no stock change, no status transition).
    deductStockForOrder(payment.getOrderId(), payment.getAmount().currency().getCurrencyCode());

    // Record the sale and emit SaleRecorded in THIS transaction (MANDATORY joins us).
    RecordSaleCommand saleCommand =
        new RecordSaleCommand(
            payment.getBusinessId(),
            payment.getAmount().amountMinor(),
            payment.getAmount().currency().getCurrencyCode(),
            capturedAt,
            saleIdempotencyKey,
            payment.getTenderType().name()); // tender_type for GL routing (ADR 0006 slice 2)
    RecordSaleResult saleResult = saleWriter.recordInCurrentTx(saleCommand);

    // Capture the payment aggregate: PENDING → CAPTURED, sets sale_id + captured_at.
    payment.capture(saleResult.sale().id(), capturedAt);
    paymentRepository.saveAndFlush(payment);

    // Link the sale to the order and move it to COMPLETED.
    Order order =
        orderRepository
            .findById(payment.getOrderId())
            .orElseThrow(
                () -> new IllegalArgumentException("Order not found for payment: " + paymentId));
    order.linkSale(saleResult.sale().id());
    orderRepository.saveAndFlush(order);

    return PaymentResponse.from(payment);
  }

  /**
   * Deducts stock for tracked items on the order lines of {@code orderId}. Mirrors the {@code
   * deductStockForParkedLines} helper in {@link
   * id.co.nativeapp.restaurant.order.service.OrderWriter OrderWriter}: loads the persisted lines
   * (RLS-scoped), loads current {@link MenuItemView}s for those items, builds transient {@link
   * OrderLine} adapters carrying only menuItemId and qty, then delegates to {@link
   * StockDeductionWriter#deductForLines} which runs in the caller's transaction ({@code
   * MANDATORY}).
   *
   * <p>Untracked items ({@code stock_quantity IS NULL}) are silently skipped. A tracked item with
   * insufficient stock causes {@link
   * id.co.nativeapp.restaurant.menu.domain.InsufficientStockException InsufficientStockException}
   * to be thrown, rolling back the entire capture transaction.
   *
   * @param orderId the order whose lines to deduct
   * @param currencyCode ISO-4217 code used to construct the transient {@link OrderLine} adapters
   */
  private void deductStockForOrder(UUID orderId, String currencyCode) {
    List<OrderLineView> lineViews = orderLineRepository.findViewsByOrderId(orderId);
    if (lineViews.isEmpty()) {
      return;
    }

    // Load current item views to get stock_quantity state (RLS-scoped, chunked to <=1000).
    List<UUID> menuItemIds = lineViews.stream().map(OrderLineView::getMenuItemId).toList();
    List<MenuItemView> menuItemViews = new ArrayList<>();
    for (int i = 0; i < menuItemIds.size(); i += 1000) {
      List<UUID> chunk = menuItemIds.subList(i, Math.min(i + 1000, menuItemIds.size()));
      menuItemViews.addAll(menuItemRepository.findViewsByIds(chunk));
    }

    // Build transient OrderLine adapters — StockDeductionWriter only needs getMenuItemId() +
    // getQty().
    List<OrderLine> adaptedLines = new ArrayList<>();
    for (OrderLineView lv : lineViews) {
      Money unitPrice = Money.ofMinor(lv.getUnitPriceMinor(), currencyCode);
      adaptedLines.add(
          new OrderLine(lv.getMenuItemId(), lv.getNameSnapshot(), unitPrice, lv.getQty()));
    }

    stockDeductionWriter.deductForLines(adaptedLines, menuItemViews);
  }
}
