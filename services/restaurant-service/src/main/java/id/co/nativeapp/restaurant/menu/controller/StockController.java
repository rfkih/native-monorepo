package id.co.nativeapp.restaurant.menu.controller;

import id.co.nativeapp.restaurant.menu.dto.AddStockRequest;
import id.co.nativeapp.restaurant.menu.dto.MenuItemResponse;
import id.co.nativeapp.restaurant.menu.dto.SetStockRequest;
import id.co.nativeapp.restaurant.menu.service.StockService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Per-item stock management endpoints.
 *
 * <ul>
 *   <li>{@code PUT /api/v1/menu/{itemId}/stock} — set absolute stock ({@code null} = infinite /
 *       untracked; &ge; 0 = tracked).
 *   <li>{@code POST /api/v1/menu/{itemId}/stock/add} — add a signed delta to a tracked item's
 *       stock.
 * </ul>
 *
 * <p>Both endpoints are tenant-scoped (RLS auto-applied via the {@code @Transactional} aspect) and
 * return a {@link MenuItemResponse} reflecting the updated state, including the new
 * {@code stockQuantity}.
 */
@Tag(name = "Menu Stock", description = "Per-item stock tracking: set absolute qty or add a delta")
@RestController
@RequestMapping("/api/v1/menu")
public class StockController {

  private final StockService stockService;

  public StockController(StockService stockService) {
    this.stockService = stockService;
  }

  /**
   * Sets the absolute stock quantity for a menu item. Pass {@code quantity: null} to make the item
   * infinite / untracked again. Pass a non-negative integer to enable tracking.
   *
   * <p>Returns {@code 200 OK} with the updated {@link MenuItemResponse}.
   */
  @Operation(
      summary = "Set absolute stock quantity",
      description =
          "Sets the absolute stock quantity for a menu item. Pass {\"quantity\": null} to make the"
              + " item infinite/untracked. Pass a non-negative integer to enable tracking."
              + " Returns 200 OK with the updated item.")
  @PutMapping("/{itemId}/stock")
  public ResponseEntity<MenuItemResponse> setStock(
      @PathVariable UUID itemId, @Valid @RequestBody SetStockRequest request) {
    MenuItemResponse response = stockService.setStock(itemId, request.quantity());
    return ResponseEntity.ok(response);
  }

  /**
   * Adds a signed delta to a tracked item's stock. Positive values restock; negative values
   * manually reduce stock (floored at 0). Returns {@code 422} if the item is untracked (has no
   * finite stock — set a quantity first).
   *
   * <p>Returns {@code 200 OK} with the updated {@link MenuItemResponse}.
   */
  @Operation(
      summary = "Add a stock delta",
      description =
          "Adds a signed delta to a tracked item's stock. Positive = restock; negative = manually"
              + " reduce (floored at 0). Returns 422 if the item is untracked (set a quantity"
              + " first). Returns 200 OK with the updated item.")
  @PostMapping("/{itemId}/stock/add")
  public ResponseEntity<MenuItemResponse> addStock(
      @PathVariable UUID itemId, @Valid @RequestBody AddStockRequest request) {
    MenuItemResponse response = stockService.addStock(itemId, request.amount());
    return ResponseEntity.ok(response);
  }
}
