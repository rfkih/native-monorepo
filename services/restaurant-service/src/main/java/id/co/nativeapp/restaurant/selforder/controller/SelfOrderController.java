package id.co.nativeapp.restaurant.selforder.controller;

import id.co.nativeapp.restaurant.menu.dto.MenuItemResponse;
import id.co.nativeapp.restaurant.order.dto.OrderResponse;
import id.co.nativeapp.restaurant.selforder.dto.SelfOrderCreateRequest;
import id.co.nativeapp.restaurant.selforder.service.SelfOrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The ANONYMOUS self-order surface (Phase 6, ADR 0029) — a diner's phone talks to exactly these two
 * endpoints, authenticated ONLY by {@link id.co.nativeapp.restaurant.config.SelfOrderTokenFilter}
 * (registered solely for {@code /api/v1/self-order/**}; no JWT, no dashboard session). Both paths
 * are declared {@code native.security.public-paths} so the shared {@code libs/security} JWT chain
 * {@code permitAll}s them and never demands a bearer token (see {@code application.yml}) — the
 * token filter is the ONLY gate here.
 *
 * <ul>
 *   <li>{@code GET /api/v1/self-order/menu} — the outlet's active menu (ungated read).
 *   <li>{@code POST /api/v1/self-order/orders} — creates a PARKED {@code source=SELF_ORDER} order;
 *       writes NO sale, NO payment, NO outbox row, NO stock movement. Entitlement-gated ({@code
 *       self_order}); capped at 50 unconfirmed rows per outlet (429 over cap).
 * </ul>
 *
 * <p>The outlet/table come from the verified token (never the request body — rule 5's client-never-
 * supplies-the-tenant/scope principle applied to the anonymous surface too).
 */
@Tag(
    name = "Self-Order",
    description = "Anonymous QR self-ordering: menu read + parked-order create")
@RestController
@RequestMapping("/api/v1/self-order")
public class SelfOrderController {

  private final SelfOrderService service;

  public SelfOrderController(SelfOrderService service) {
    this.service = service;
  }

  @Operation(
      summary = "Read the scanned outlet's menu",
      description = "Returns active menu items for the verified token's outlet. Ungated.")
  @GetMapping("/menu")
  public ResponseEntity<List<MenuItemResponse>> menu() {
    return ResponseEntity.ok(service.menu());
  }

  @Operation(
      summary = "Create a self-order parked cart",
      description =
          "Creates a PARKED source=SELF_ORDER order for the verified token's outlet/table. Writes"
              + " no sale, payment, outbox row, or stock movement. 403 if the company is not"
              + " entitled to self_order; 429 if the outlet's unconfirmed self-order count is at"
              + " the cap.")
  @PostMapping("/orders")
  public ResponseEntity<OrderResponse> createOrder(
      @Valid @RequestBody SelfOrderCreateRequest request) {
    OrderResponse body = service.createOrder(request);
    return ResponseEntity.status(HttpStatus.CREATED).body(body);
  }
}
