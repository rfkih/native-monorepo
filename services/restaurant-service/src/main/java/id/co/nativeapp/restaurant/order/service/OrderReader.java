package id.co.nativeapp.restaurant.order.service;

import id.co.nativeapp.money.Money;
import id.co.nativeapp.restaurant.menu.projection.MenuItemView;
import id.co.nativeapp.restaurant.menu.repository.MenuItemRepository;
import id.co.nativeapp.restaurant.order.dto.OrderLineRequest;
import id.co.nativeapp.restaurant.order.dto.PriceBreakdownResponse;
import id.co.nativeapp.restaurant.order.dto.QuoteRequest;
import id.co.nativeapp.restaurant.pricing.domain.PriceBreakdown;
import id.co.nativeapp.restaurant.pricing.service.TaxChargeService;
import id.co.nativeapp.tenant.RlsAutoApplyAspect;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the read-only {@code @Transactional} unit of work for the order price-quote feature.
 *
 * <p>A distinct bean from {@link OrderService} so the {@link RlsAutoApplyAspect} engages via the
 * Spring proxy — self-invocation would bypass the advice and leave the tenant GUC unset (same
 * pattern as {@code SaleWriter} / {@code OrderWriter}).
 *
 * <p><strong>Quote = read-only.</strong> {@link #computeQuote} runs in a {@code readOnly = true}
 * transaction and:
 *
 * <ol>
 *   <li>Loads requested menu items (RLS-scoped, same chunked IN as checkout — ≤ 1000 per batch).
 *   <li>Validates all items are active, belong to the request's business, and share the same
 *       currency (identical rules to checkout).
 *   <li>Computes line totals + subtotal (integer minor units, exact).
 *   <li>Resolves effective tax + service-charge rules via {@link TaxChargeService} and computes a
 *       {@link PriceBreakdown}.
 *   <li>Returns the breakdown as a {@link PriceBreakdownResponse}. Nothing is persisted.
 * </ol>
 *
 * <p>The quote deliberately does NOT reject a zero grand-total (unlike checkout). The caller is
 * just pricing the cart; a fully-comped quote is informational and not an error.
 */
@Component
public class OrderReader {

  private final MenuItemRepository menuItemRepository;
  private final TaxChargeService taxChargeService;

  public OrderReader(MenuItemRepository menuItemRepository, TaxChargeService taxChargeService) {
    this.menuItemRepository = menuItemRepository;
    this.taxChargeService = taxChargeService;
  }

  /**
   * Computes the price breakdown for a cart without persisting anything.
   *
   * @param request the quote request (businessId + lines + optional discount)
   * @return the fully computed breakdown, never {@code null}
   * @throws IllegalArgumentException if any item is unknown/inactive/cross-business, or if items
   *     span more than one currency
   */
  @Transactional(readOnly = true)
  public PriceBreakdownResponse computeQuote(QuoteRequest request) {
    // ------------------------------------------------------------------
    // 1. Load requested menu items (RLS-scoped; chunk IN by ≤ 1000).
    // ------------------------------------------------------------------
    List<UUID> requestedIds = request.lines().stream().map(OrderLineRequest::menuItemId).toList();

    List<MenuItemView> itemViews = new ArrayList<>();
    for (int i = 0; i < requestedIds.size(); i += 1000) {
      List<UUID> chunk = requestedIds.subList(i, Math.min(i + 1000, requestedIds.size()));
      itemViews.addAll(menuItemRepository.findViewsByIds(chunk));
    }

    Map<UUID, MenuItemView> itemMap =
        itemViews.stream().collect(Collectors.toMap(MenuItemView::getId, Function.identity()));

    // ------------------------------------------------------------------
    // 2. Validate: all requested items must exist, be active, belong to
    //    the request's business, and share the SAME currency.
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

    Set<String> currencies =
        itemViews.stream().map(v -> v.getCurrency().strip()).collect(Collectors.toSet());
    if (currencies.size() != 1) {
      throw new IllegalArgumentException(
          "All menu items in one quote must share the same currency; found: " + currencies);
    }
    String currencyCode = currencies.iterator().next();
    Currency currency = Currency.getInstance(currencyCode);

    // ------------------------------------------------------------------
    // 3. Compute line totals + subtotal (integer minor units, exact).
    // ------------------------------------------------------------------
    Money runningTotal = Money.zero(currency);
    for (OrderLineRequest lineReq : request.lines()) {
      MenuItemView view = itemMap.get(lineReq.menuItemId());
      Money unitPrice = Money.ofMinor(view.getPriceMinor(), view.getCurrency().strip());
      long lineTotal = Math.multiplyExact(unitPrice.amountMinor(), (long) lineReq.qty());
      runningTotal = runningTotal.plus(Money.ofMinor(lineTotal, currencyCode));
    }

    // ------------------------------------------------------------------
    // 4. Apply Phase 2 pricing formula via TaxChargeService.
    // ------------------------------------------------------------------
    Instant now = Instant.now();
    Money fixedDiscount =
        (request.discountMinor() != null)
            ? Money.ofMinor(request.discountMinor(), currencyCode)
            : null;
    long discountBp = 0L;
    PriceBreakdown breakdown =
        taxChargeService.resolve(runningTotal, discountBp, fixedDiscount, now);

    return PriceBreakdownResponse.from(breakdown);
  }
}
