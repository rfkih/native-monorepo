package id.co.nativeapp.restaurant.inventory.controller;

import id.co.nativeapp.restaurant.inventory.dto.IngredientStocktakeResponse;
import id.co.nativeapp.restaurant.inventory.dto.SubmitIngredientStocktakeRequest;
import id.co.nativeapp.restaurant.inventory.dto.SubmitIngredientStocktakeResult;
import id.co.nativeapp.restaurant.inventory.service.IngredientStocktakeService;
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
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Ingredient stock opnames (ADR 0046 phase 1) — a clone of {@code
 * id.co.nativeapp.restaurant.stocktake.controller.StocktakeController} keyed by {@link
 * id.co.nativeapp.restaurant.inventory.domain.Ingredient} rather than a menu item. Gateway-routed
 * under POS_ROLES. The submit mutation REQUIRES an {@code Idempotency-Key} header (the
 * register-session idiom: keyless → 400, same-key replay → 200, the DB unique backstops races).
 */
@Tag(name = "Ingredient Stocktakes", description = "Ingredient stock opname (ADR 0046)")
@RestController
@RequestMapping("/api/v1/ingredient-stocktakes")
public class IngredientStocktakeController {

  private final IngredientStocktakeService service;

  public IngredientStocktakeController(IngredientStocktakeService service) {
    this.service = service;
  }

  /**
   * Submits a physical ingredient count for an outlet. Adjusts every counted ingredient's stock to
   * the physical count and — only when at least one counted line carried a cost — emits {@code
   * StocktakeCompleted} with the valued net shrinkage. 201 + Location on a fresh submit, 200 on a
   * same-key replay.
   */
  @Operation(
      summary = "Submit an ingredient stocktake",
      description =
          "Submits a physical ingredient count for an outlet: adjusts each counted ingredient's stock"
              + " to the count and — only when at least one line carried a cost — emits"
              + " StocktakeCompleted with the server-computed valued net shrinkage. A count with zero"
              + " costed lines posts nothing (ADR 0046). Idempotency-Key required (replay returns"
              + " 200, no re-adjustment).")
  @PostMapping
  public ResponseEntity<IngredientStocktakeResponse> submit(
      @Valid @RequestBody SubmitIngredientStocktakeRequest request,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
    requireKey(idempotencyKey);
    SubmitIngredientStocktakeResult result = service.submit(request, idempotencyKey);
    return result.created()
        ? ResponseEntity.created(
                URI.create("/api/v1/ingredient-stocktakes/" + result.stocktake().id()))
            .body(result.stocktake())
        : ResponseEntity.ok(result.stocktake());
  }

  /**
   * An ingredient stocktake by id, scoped to the bound tenant and the caller's outlet assignment.
   */
  @Operation(
      summary = "Get an ingredient stocktake",
      description = "A single ingredient stocktake by id, with its lines.")
  @GetMapping("/{id}")
  public ResponseEntity<IngredientStocktakeResponse> getById(@PathVariable("id") UUID id) {
    return ResponseEntity.ok(service.findById(id));
  }

  /** The outlet's ingredient stocktake history, most recent first (capped at 50). */
  @Operation(
      summary = "Ingredient stocktake history",
      description = "Most recent 50 ingredient stocktakes for an outlet.")
  @GetMapping
  public ResponseEntity<List<IngredientStocktakeResponse>> history(@RequestParam UUID businessId) {
    return ResponseEntity.ok(service.history(businessId));
  }

  private static void requireKey(String idempotencyKey) {
    if (idempotencyKey == null || idempotencyKey.isBlank()) {
      throw new IllegalArgumentException(
          "the Idempotency-Key header is required to submit an ingredient stocktake");
    }
    if (idempotencyKey.length() > 64) {
      throw new IllegalArgumentException("Idempotency-Key must be at most 64 characters");
    }
  }
}
