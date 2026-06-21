package id.co.nativeapp.restaurant.order.controller;

import id.co.nativeapp.restaurant.order.dto.CheckoutRequest;
import id.co.nativeapp.restaurant.order.dto.CheckoutResult;
import id.co.nativeapp.restaurant.order.dto.OrderResponse;
import id.co.nativeapp.restaurant.order.dto.PriceBreakdownResponse;
import id.co.nativeapp.restaurant.order.dto.QuoteRequest;
import id.co.nativeapp.restaurant.order.service.OrderService;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /api/v1/orders} — place an order (checkout). {@code POST /api/v1/orders/quote} —
 * compute a live price breakdown without persisting anything.
 *
 * <p>The tenant ({@code company_id}) and actor come from the bound {@link
 * id.co.nativeapp.tenant.TenantContext TenantContext} (set at the request edge by {@link
 * id.co.nativeapp.restaurant.config.DevTenantFilter DevTenantFilter}), never from the body (rule
 * 5).
 *
 * <p>Returns {@code 201 Created} (+ {@code Location}) when a new order was placed and exactly one
 * {@code SaleRecorded} emitted, and {@code 200 OK} when a retry with the same {@code
 * idempotencyKey} returned the pre-existing order (no second event, no {@code Location}).
 *
 * <p>The quote endpoint returns {@code 200 OK} with the {@link PriceBreakdownResponse}. It is
 * idempotent and writes nothing — safe to call from the POS live-total display.
 */
@RestController
@RequestMapping("/api/v1/orders")
public class OrderController {

  private final OrderService orderService;

  public OrderController(OrderService orderService) {
    this.orderService = orderService;
  }

  @PostMapping
  public ResponseEntity<OrderResponse> checkout(@Valid @RequestBody CheckoutRequest request) {
    CheckoutResult result = orderService.checkout(request);
    OrderResponse body = result.order();
    return result.created()
        ? ResponseEntity.created(URI.create("/api/v1/orders/" + body.orderId())).body(body)
        : ResponseEntity.ok(body);
  }

  /**
   * Computes a price breakdown (subtotal, discount, service charge, tax, grand total) for the given
   * cart without persisting any order, sale, payment, or outbox row.
   *
   * <p>Returns {@code 200 OK} with a {@link PriceBreakdownResponse}. The {@code
   * usesIllustrativeRules} flag is {@code true} when the resolved pricing rules are illustrative
   * placeholders — the POS must badge the tax / SC lines accordingly.
   *
   * <p>Validation failures return {@code 400 application/problem+json} (unknown/inactive/
   * cross-business item, mixed-currency cart, or bean-validation failures on the body).
   */
  @PostMapping("/quote")
  public ResponseEntity<PriceBreakdownResponse> quote(@Valid @RequestBody QuoteRequest request) {
    PriceBreakdownResponse breakdown = orderService.quote(request);
    return ResponseEntity.ok(breakdown);
  }
}
