package id.co.nativeapp.restaurant.order.controller;

import id.co.nativeapp.restaurant.order.dto.CheckoutRequest;
import id.co.nativeapp.restaurant.order.dto.CheckoutResult;
import id.co.nativeapp.restaurant.order.dto.OrderResponse;
import id.co.nativeapp.restaurant.order.dto.ParkOrderRequest;
import id.co.nativeapp.restaurant.order.dto.ParkedOrderSummary;
import id.co.nativeapp.restaurant.order.dto.PayParkedRequest;
import id.co.nativeapp.restaurant.order.dto.PriceBreakdownResponse;
import id.co.nativeapp.restaurant.order.dto.QuoteRequest;
import id.co.nativeapp.restaurant.order.service.OrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST endpoints for the order feature:
 *
 * <ul>
 *   <li>{@code POST /api/v1/orders} — checkout (place and pay in one call)
 *   <li>{@code POST /api/v1/orders/quote} — price a cart without persisting
 *   <li>{@code POST /api/v1/orders/park} — save a cart as a PARKED order (no revenue)
 *   <li>{@code GET /api/v1/orders?status=PARKED&businessId=...} — list parked orders
 *   <li>{@code GET /api/v1/orders/{id}} — fetch one order (resume / receipt)
 *   <li>{@code POST /api/v1/orders/{id}/pay} — finalise a PARKED order (revenue recognised here)
 * </ul>
 *
 * <p>The tenant ({@code company_id}) comes from the bound {@link
 * id.co.nativeapp.tenant.TenantContext TenantContext}, never from the body (rule 5).
 */
@Tag(name = "Orders", description = "POS order lifecycle: checkout, quote, park, pay, and retrieve")
@RestController
@RequestMapping("/api/v1/orders")
public class OrderController {

  private final OrderService orderService;

  public OrderController(OrderService orderService) {
    this.orderService = orderService;
  }

  /** Checks out an order and returns 201 (new) or 200 (idempotent retry). */
  @Operation(
      summary = "Checkout an order",
      description =
          "Checks out an order (place and pay in one call). Returns 201 Created (new) or 200 OK"
              + " (idempotent retry with the same idempotency key).")
  @PostMapping
  public ResponseEntity<OrderResponse> checkout(@Valid @RequestBody CheckoutRequest request) {
    CheckoutResult result = orderService.checkout(request);
    OrderResponse body = result.order();
    return result.created()
        ? ResponseEntity.created(URI.create("/api/v1/orders/" + body.orderId())).body(body)
        : ResponseEntity.ok(body);
  }

  /**
   * Computes a price breakdown for the given cart without persisting anything. Returns {@code 200
   * OK} with a {@link PriceBreakdownResponse}.
   */
  @Operation(
      summary = "Quote a cart price",
      description =
          "Computes a price breakdown for the given cart without persisting anything. Returns 200"
              + " OK with a PriceBreakdownResponse.")
  @PostMapping("/quote")
  public ResponseEntity<PriceBreakdownResponse> quote(@Valid @RequestBody QuoteRequest request) {
    return ResponseEntity.ok(orderService.quote(request));
  }

  /**
   * Parks a cart as a PARKED order — no sale, no payment, no revenue. Returns {@code 201 Created}
   * (new park) or {@code 200 OK} (idempotent retry). Revenue is recognised only when {@code /pay}
   * is called.
   */
  @Operation(
      summary = "Park a cart as a PARKED order",
      description =
          "Parks a cart as a PARKED order — no sale, no payment, no revenue. Returns 201 Created"
              + " (new park) or 200 OK (idempotent retry). Revenue is recognised only when /pay is"
              + " called.")
  @PostMapping("/park")
  public ResponseEntity<OrderResponse> park(@Valid @RequestBody ParkOrderRequest request) {
    CheckoutResult result = orderService.park(request);
    OrderResponse body = result.order();
    return result.created()
        ? ResponseEntity.created(URI.create("/api/v1/orders/" + body.orderId())).body(body)
        : ResponseEntity.ok(body);
  }

  /**
   * Lists orders by status. Currently only {@code status=PARKED} is supported (the parked-orders
   * tray). Returns {@code 200 OK} with the list.
   *
   * @param status filter (currently only PARKED is supported)
   * @param businessId the business unit whose orders to list
   */
  @Operation(
      summary = "List orders by status",
      description =
          "Lists orders by status. Currently only status=PARKED is supported (the parked-orders"
              + " tray). Returns 200 OK with the list.")
  @GetMapping
  public ResponseEntity<List<ParkedOrderSummary>> list(
      @RequestParam(defaultValue = "PARKED") String status, @RequestParam UUID businessId) {
    if (!"PARKED".equals(status)) {
      throw new IllegalArgumentException(
          "Unsupported status filter: " + status + ". Supported: PARKED");
    }
    return ResponseEntity.ok(orderService.listParked(businessId));
  }

  /**
   * Fetches a single order by id (with lines, modifiers, and breakdown). Used by the POS "resume
   * parked order" flow. Returns {@code 200 OK} or {@code 400} if not found.
   */
  @Operation(
      summary = "Fetch a single order",
      description =
          "Fetches a single order by id (with lines, modifiers, and breakdown). Used by the POS"
              + " resume parked order flow. Returns 200 OK or 400 if not found.")
  @GetMapping("/{id}")
  public ResponseEntity<OrderResponse> getOrder(@PathVariable UUID id) {
    return ResponseEntity.ok(orderService.getOrder(id));
  }

  /**
   * Finalises a PARKED order by recording the Sale and optional payment. This is the moment revenue
   * is recognised for a parked order. Returns {@code 200 OK} with the updated order.
   *
   * @param id the parked order to finalise
   */
  @Operation(
      summary = "Finalise a PARKED order",
      description =
          "Finalises a PARKED order by recording the Sale and optional payment. This is the moment"
              + " revenue is recognised for a parked order. Returns 200 OK with the updated order.")
  @PostMapping("/{id}/pay")
  public ResponseEntity<OrderResponse> payParked(
      @PathVariable UUID id, @Valid @RequestBody PayParkedRequest request) {
    return ResponseEntity.ok(orderService.payParked(id, request));
  }
}
