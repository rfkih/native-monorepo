package id.co.nativeapp.carwash.giftcard.controller;

import id.co.nativeapp.carwash.giftcard.dto.GiftCardSaleResponse;
import id.co.nativeapp.carwash.giftcard.dto.SellGiftCardRequest;
import id.co.nativeapp.carwash.giftcard.service.GiftCardSaleResult;
import id.co.nativeapp.carwash.giftcard.service.GiftCardSaleService;
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
 * {@code POST /api/v1/carwash/gift-cards/sell} — sells (mints) a gift card at the till (ADR 0027,
 * Phase 4).
 *
 * <p><strong>Path note.</strong> Sits under carwash-service's already-gateway-routed {@code
 * /api/v1/carwash/**} prefix (see {@code RoutingConfig#carwashPosRoute}), so — unlike
 * restaurant-service, which is unprefixed and needs a new gateway route for this feature — this
 * endpoint is reachable through the gateway with NO gateway change required.
 *
 * <p>The tenant ({@code company_id}) and actor come from the bound {@link
 * id.co.nativeapp.tenant.TenantContext TenantContext}, never from the body (rule 5).
 */
@Tag(name = "Gift Card Sales", description = "Sell (mint) a gift card at the till")
@RestController
@RequestMapping("/api/v1/carwash/gift-cards")
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
  @PostMapping("/sell")
  public ResponseEntity<GiftCardSaleResponse> sell(
      @Valid @RequestBody SellGiftCardRequest request) {
    GiftCardSaleResult result = giftCardSaleService.sell(request);
    GiftCardSaleResponse body = result.sale();
    return result.created()
        ? ResponseEntity.created(
                URI.create("/api/v1/carwash/gift-cards/" + body.giftCardSaleId()))
            .body(body)
        : ResponseEntity.ok(body);
  }
}
