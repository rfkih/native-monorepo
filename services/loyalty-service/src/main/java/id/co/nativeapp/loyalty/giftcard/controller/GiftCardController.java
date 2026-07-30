package id.co.nativeapp.loyalty.giftcard.controller;

import id.co.nativeapp.loyalty.giftcard.dto.GiftCardResponse;
import id.co.nativeapp.loyalty.giftcard.service.GiftCardReader;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/loyalty/gift-cards} — the POS redemption-validation lookup + an admin listing.
 * Gift cards themselves are created only by the {@code ingest.service.GiftCardSoldWriter} (from a
 * vertical's {@code GiftCardSold} event) — this feature is read-only.
 */
@Tag(name = "Loyalty Gift Cards", description = "Gift-card lookup by code for POS redemption")
@RestController
@RequestMapping("/api/v1/loyalty/gift-cards")
public class GiftCardController {

  private final GiftCardReader giftCardReader;

  public GiftCardController(GiftCardReader giftCardReader) {
    this.giftCardReader = giftCardReader;
  }

  @Operation(
      summary = "Look up a gift card by code",
      description =
          "POS redemption-validation lookup: state, balance, and currency. 404 if unknown or"
              + " belonging to another tenant.")
  @GetMapping("/{code}")
  public GiftCardResponse lookupByCode(@PathVariable String code) {
    return giftCardReader.lookupByCode(code);
  }

  @Operation(summary = "List gift cards", description = "The admin listing, newest-sold first.")
  @GetMapping
  public List<GiftCardResponse> list() {
    return giftCardReader.list();
  }
}
