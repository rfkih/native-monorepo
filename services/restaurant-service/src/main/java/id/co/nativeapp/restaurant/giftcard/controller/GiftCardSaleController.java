package id.co.nativeapp.restaurant.giftcard.controller;

import id.co.nativeapp.restaurant.giftcard.dto.GiftCardSaleResponse;
import id.co.nativeapp.restaurant.giftcard.dto.SellGiftCardRequest;
import id.co.nativeapp.restaurant.giftcard.service.GiftCardSaleResult;
import id.co.nativeapp.restaurant.giftcard.service.GiftCardSaleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /api/v1/gift-card-sales} — sells (mints) a gift card at the till (ADR 0027, Phase
 * 4).
 *
 * <p><strong>Path note (deliberate).</strong> restaurant-service's routes are NOT prefixed by
 * service name at the gateway (unlike carwash/barbershop's {@code /api/v1/{vertical}/**}); this
 * resource deliberately avoids {@code /api/v1/loyalty/**}, which the gateway already routes to
 * loyalty-service (see {@code RoutingConfig#loyaltyPosRoute}) — using that prefix here would either
 * collide or silently misroute. A new gateway route for {@code /api/v1/gift-card-sales/**} →
 * restaurant-service is REQUIRED before this endpoint is reachable through the gateway (gateway is
 * out of scope for this change; tracked as a follow-up).
 *
 * <p>The tenant ({@code company_id}) and actor come from the bound {@link
 * id.co.nativeapp.tenant.TenantContext TenantContext}, never from the body (rule 5).
 */
@Tag(name = "Gift Card Sales", description = "Sell (mint) a gift card at the till")
@RestController
@RequestMapping("/api/v1/gift-card-sales")
public class GiftCardSaleController {

  private final GiftCardSaleService giftCardSaleService;

  public GiftCardSaleController(GiftCardSaleService giftCardSaleService) {
    this.giftCardSaleService = giftCardSaleService;
  }

  @Operation(
      summary = "Sell a gift card",
      description =
          "Mints a new gift card at the till, persists the liability sale record, and emits"
              + " GiftCardSold. Returns 201 Created (new) with the derived display code, or 200 OK"
              + " (idempotent retry with the same idempotencyKey).")
  @PostMapping
  public ResponseEntity<GiftCardSaleResponse> sell(
      @Valid @RequestBody SellGiftCardRequest request) {
    GiftCardSaleResult result = giftCardSaleService.sell(request);
    GiftCardSaleResponse body = result.sale();
    return result.created()
        ? ResponseEntity.created(
                URI.create("/api/v1/gift-card-sales/" + body.giftCardSaleId()))
            .body(body)
        : ResponseEntity.ok(body);
  }
}
