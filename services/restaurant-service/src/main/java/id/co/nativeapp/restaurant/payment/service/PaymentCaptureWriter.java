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
import id.co.nativeapp.restaurant.pricing.domain.PriceBreakdown;
import id.co.nativeapp.restaurant.pricing.service.TaxChargeService;
import id.co.nativeapp.restaurant.promotion.projection.AppliedPromotionView;
import id.co.nativeapp.restaurant.promotion.repository.AppliedPromotionRepository;
import id.co.nativeapp.restaurant.sale.dto.RecordSaleCommand;
import id.co.nativeapp.restaurant.sale.dto.RecordSaleResult;
import id.co.nativeapp.restaurant.sale.service.SaleWriter;
import id.co.nativeapp.tenant.TenantContext;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 *
 * <p><strong>Phase 3 (ADR 0026).</strong> A digital-tender checkout writes its {@code
 * applied_promotion} audit rows at checkout time with {@code sale_id = NULL} (no sale exists yet —
 * revenue is deferred to capture). Once the sale records here, every such row for this order is
 * stamped with the new {@code sale_id} in the SAME transaction ({@link
 * id.co.nativeapp.restaurant.promotion.repository.AppliedPromotionRepository#stampSaleId}) — the
 * idempotent re-delivery early return means this never double-stamps.
 *
 * <p><strong>Phase 3 review fix (W1).</strong> A digital-tender checkout defers the sale (and thus
 * the {@code SaleRecorded} event) to this capture — but it does NOT defer pricing: the order was
 * already fully priced by {@code OrderWriter.checkout} (subtotal, discount, service charge, tax all
 * resolved then). {@link #capture} reconstructs that same {@link PriceBreakdown} deterministically
 * from the order's persisted lines + {@code applied_promotion} audit rows (see {@link
 * #reconstructBreakdown}) so the emitted {@code SaleRecorded} carries the real subtotal/discount/
 * tax/service-charge legs instead of leaving them {@code null} (which made finance fall back to
 * {@code subtotal == amount_minor}, silently dropping the discount AND tax/SC legs).
 */
@Component
public class PaymentCaptureWriter {

  private static final Logger log = LoggerFactory.getLogger(PaymentCaptureWriter.class);

  private final PaymentRepository paymentRepository;
  private final OrderRepository orderRepository;
  private final OrderLineRepository orderLineRepository;
  private final MenuItemRepository menuItemRepository;
  private final StockDeductionWriter stockDeductionWriter;
  private final SaleWriter saleWriter;
  private final AppliedPromotionRepository appliedPromotionRepository;
  private final TaxChargeService taxChargeService;

  @SuppressWarnings("checkstyle:ParameterNumber")
  public PaymentCaptureWriter(
      PaymentRepository paymentRepository,
      OrderRepository orderRepository,
      OrderLineRepository orderLineRepository,
      MenuItemRepository menuItemRepository,
      StockDeductionWriter stockDeductionWriter,
      SaleWriter saleWriter,
      AppliedPromotionRepository appliedPromotionRepository,
      TaxChargeService taxChargeService) {
    this.paymentRepository = paymentRepository;
    this.orderRepository = orderRepository;
    this.orderLineRepository = orderLineRepository;
    this.menuItemRepository = menuItemRepository;
    this.stockDeductionWriter = stockDeductionWriter;
    this.saleWriter = saleWriter;
    this.appliedPromotionRepository = appliedPromotionRepository;
    this.taxChargeService = taxChargeService;
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

    // Load the order once, up front — needed both to reconstruct the price breakdown (its lines,
    // occurredAt, and discountMinor) and, later, to link the sale + move it to COMPLETED.
    Order order =
        orderRepository
            .findById(payment.getOrderId())
            .orElseThrow(
                () -> new IllegalArgumentException("Order not found for payment: " + paymentId));

    // Deduct stock for tracked order lines — same transaction as sale + payment state change.
    // An insufficient-stock shortfall throws InsufficientStockException, rolling back everything
    // (no sale, no SaleRecorded, no stock change, no status transition).
    deductStockForOrder(payment.getOrderId(), payment.getAmount().currency().getCurrencyCode());

    // Phase 3 review fix (W1): reconstruct the Phase-2 price breakdown this order was actually
    // priced with at checkout (see reconstructBreakdown javadoc) — null on a reconciliation
    // mismatch, in which case the pre-existing no-breakdown command below is used, exactly as
    // before this fix.
    //
    // Phase 4 review fix (ADR 0027): the sale's amount_minor MUST be the order's GRAND TOTAL —
    // NOT payment.getAmount(), which since Phase 4 is the RESIDUAL after a gift-card redemption
    // (a tender amount, always <= the grand total). Using payment.getAmount() here would silently
    // under-record revenue whenever a gift card partially settled a digital-tender order.
    // order.getTotal() is the checkout-time grand total, immutable since (never mutated on this
    // digital-capture path).
    Money grandTotal = order.getTotal();
    PriceBreakdown breakdown = reconstructBreakdown(order);

    // order.giftCardId is ALREADY null whenever giftCardRedeemedMinor is null (OrderWriter stamps
    // the pair together, never one without the other) — no extra conditional needed here.
    Long loyaltyRedeemedMinor = order.getLoyaltyRedeemedMinor();

    // Record the sale and emit SaleRecorded in THIS transaction (MANDATORY joins us).
    RecordSaleCommand saleCommand =
        (breakdown != null)
            ? new RecordSaleCommand(
                payment.getBusinessId(),
                grandTotal.amountMinor(),
                grandTotal.currency().getCurrencyCode(),
                capturedAt,
                saleIdempotencyKey,
                payment.getTenderType().name(), // tender_type for GL routing (ADR 0006 slice 2)
                breakdown,
                order.getLoyaltyMemberId(),
                loyaltyRedeemedMinor,
                loyaltyRedeemedMinor,
                order.getGiftCardId(),
                order.getGiftCardRedeemedMinor(),
                // ADR 0036 Phase B2: this path is the flagged-pending digital (QRIS/CARD)
                // capture — ONLINE never goes through it (it captures synchronously, see
                // PaymentWriter), so no channel ever rides here.
                null)
            : new RecordSaleCommand(
                payment.getBusinessId(),
                grandTotal.amountMinor(),
                grandTotal.currency().getCurrencyCode(),
                capturedAt,
                saleIdempotencyKey,
                payment.getTenderType().name());
    RecordSaleResult saleResult = saleWriter.recordInCurrentTx(saleCommand);

    // Capture the payment aggregate: PENDING → CAPTURED, sets sale_id + captured_at.
    payment.capture(saleResult.sale().id(), capturedAt);
    paymentRepository.saveAndFlush(payment);

    // Link the sale to the order and move it to COMPLETED.
    order.linkSale(saleResult.sale().id());
    orderRepository.saveAndFlush(order);

    // Phase 3 (ADR 0026): stamp sale_id onto every applied_promotion row this order wrote at
    // checkout time (sale_id was NULL then — no sale existed yet for a digital tender).
    appliedPromotionRepository.stampSaleId(payment.getOrderId(), saleResult.sale().id());

    return PaymentResponse.from(payment);
  }

  /**
   * Phase 3 review fix (W1): rebuilds the Phase-2 {@link PriceBreakdown} a digital-tender capture
   * never had — {@code OrderWriter.checkout} already priced this order (subtotal, discount, service
   * charge, tax) but a digital tender defers the {@code SaleRecorded} emission to capture, and
   * until this fix that emission always carried a {@code null} breakdown (finance fell back to
   * {@code subtotal == amount_minor}, silently losing the discount AND tax/service-charge legs).
   *
   * <p><strong>Deterministic reconstruction.</strong> An order's lines never change after checkout
   * (line items are immutable once persisted), so their sum reproduces the EXACT subtotal checkout
   * priced from. The discount is reconstructed as the sum of this order's {@code applied_promotion}
   * audit rows (the rule/coupon-backed deductions — each row's {@code amount_minor} is already the
   * CLAMPED amount that deduction actually contributed, V16 migration comment) PLUS the order's raw
   * manual discount ({@link Order#getDiscountMinor()}). {@link TaxChargeService#resolve} re-clamps
   * that sum to the subtotal with {@code Money#min} — which, because every deduction is
   * non-negative, is mathematically IDENTICAL to the sequential per-deduction clamp {@code
   * PromotionEngineService} applied at checkout (clamping a running sum to a cap always yields
   * exactly {@code min(Σ terms, cap)}, regardless of the order the terms were added in). So this
   * reproduces the checkout-time discount exactly, provided the effective-dated tax/service-charge
   * rule for the order's date has not changed since.
   *
   * <p><strong>The SAME pricing instant checkout used.</strong> {@link TaxChargeService#resolve} is
   * called with {@link Order#getOccurredAt()} — the exact instant {@code OrderWriter.checkout}
   * persisted on the order, NOT {@code Instant.now()} at capture time — so the effective-dated
   * tax/service-charge rule resolution reproduces identically to what checkout resolved, even if a
   * new rule version (a later {@code effective_from}) has since been added.
   *
   * <p><strong>Safety guard.</strong> If the reconstructed {@link PriceBreakdown#grandTotal()} does
   * not exactly equal {@link Order#getTotal()} (the order's checkout-time GRAND TOTAL — never
   * {@code payment.getAmount()}, which since Phase 4/ADR 0027 is only the tender RESIDUAL after a
   * gift-card redemption), this NEVER distorts money on the wire: it logs a WARN (order id +
   * expected/actual minor amounts only — no PII) and returns {@code null} so the caller falls back
   * to the pre-existing no-breakdown {@link RecordSaleCommand} — identical to the behaviour before
   * this fix.
   *
   * <p><strong>Phase 4 (ADR 0027).</strong> The reconstructed {@code fixedDiscount} fed into {@link
   * TaxChargeService#resolve} is the COMBINED deduction — the promo-only sum ({@code
   * ruleDiscountMinor + manualDiscountMinor}, unchanged from the Phase-3 reconstruction) PLUS
   * {@link Order#getLoyaltyRedeemedMinor()} (persisted at checkout) — so a capture reproduces the
   * EXACT checkout-time grand total even when a points redemption applied. The gift-card TENDER
   * settlement never enters this discount math (ADR 0027 decision 4 — it is not a deduction).
   *
   * @param order the order being captured (its lines, {@code occurredAt}, {@code discountMinor},
   *     and Phase-4 redemption columns are read; not mutated)
   * @return the reconstructed breakdown, or {@code null} if it does not reconcile to {@link
   *     Order#getTotal()}
   */
  private PriceBreakdown reconstructBreakdown(Order order) {
    Money orderTotal = order.getTotal();
    String currencyCode = orderTotal.currency().getCurrencyCode();

    List<OrderLineView> lineViews = orderLineRepository.findViewsByOrderId(order.getId());
    Money subtotal = Money.zero(orderTotal.currency());
    for (OrderLineView lv : lineViews) {
      subtotal = subtotal.plus(Money.ofMinor(lv.getLineTotalMinor(), currencyCode));
    }

    long ruleDiscountMinor = 0L;
    for (AppliedPromotionView row : appliedPromotionRepository.findViewsByOrderId(order.getId())) {
      ruleDiscountMinor = Math.addExact(ruleDiscountMinor, row.getAmountMinor());
    }
    long manualDiscountMinor = (order.getDiscountMinor() != null) ? order.getDiscountMinor() : 0L;
    long loyaltyRedeemedMinor =
        (order.getLoyaltyRedeemedMinor() != null) ? order.getLoyaltyRedeemedMinor() : 0L;
    long combinedDiscountMinor =
        Math.addExact(Math.addExact(ruleDiscountMinor, manualDiscountMinor), loyaltyRedeemedMinor);
    Money fixedDiscount = Money.ofMinor(combinedDiscountMinor, currencyCode);

    PriceBreakdown breakdown =
        taxChargeService.resolve(subtotal, 0L, fixedDiscount, order.getOccurredAt());

    if (!breakdown.grandTotal().equals(orderTotal)) {
      log.warn(
          "Digital capture breakdown reconstruction mismatch — falling back to the no-breakdown"
              + " SaleRecorded (money is NOT distorted). orderId={} expectedMinor={}"
              + " reconstructedMinor={}",
          order.getId(),
          orderTotal.amountMinor(),
          breakdown.grandTotal().amountMinor());
      return null;
    }
    return breakdown;
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
