package id.co.nativeapp.restaurant.stocktake.controller;

import id.co.nativeapp.restaurant.stocktake.dto.StocktakeResponse;
import id.co.nativeapp.restaurant.stocktake.dto.SubmitStocktakeRequest;
import id.co.nativeapp.restaurant.stocktake.dto.SubmitStocktakeResult;
import id.co.nativeapp.restaurant.stocktake.service.StocktakeService;
import io.swagger.v3.oas.annotations.Operation;
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
 * Inventory stocktakes — daily close v2 (ADR 0038 phase 3). Gateway-routed under POS_ROLES. The
 * submit mutation REQUIRES an {@code Idempotency-Key} header (the register-session idiom: keyless →
 * 400, same-key replay → 200, the DB unique backstops races).
 */
@RestController
@RequestMapping("/api/v1/stocktakes")
public class StocktakeController {

  private final StocktakeService service;

  public StocktakeController(StocktakeService service) {
    this.service = service;
  }

  /**
   * Submits a physical inventory count for an outlet. Adjusts every counted item's stock to the
   * physical count and emits {@code StocktakeCompleted} with the valued net shrinkage. 201 +
   * Location on a fresh submit, 200 on a same-key replay.
   */
  @Operation(
      summary = "Submit a stocktake",
      description =
          "Submits a physical inventory count for an outlet: adjusts each counted item's stock to"
              + " the count and emits StocktakeCompleted with the server-computed valued net"
              + " shrinkage. Idempotency-Key required (replay returns 200, no re-adjustment).")
  @PostMapping
  public ResponseEntity<StocktakeResponse> submit(
      @Valid @RequestBody SubmitStocktakeRequest request,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
    requireKey(idempotencyKey);
    SubmitStocktakeResult result = service.submit(request, idempotencyKey);
    return result.created()
        ? ResponseEntity.created(URI.create("/api/v1/stocktakes/" + result.stocktake().id()))
            .body(result.stocktake())
        : ResponseEntity.ok(result.stocktake());
  }

  /** A stocktake by id, scoped to the bound tenant and the caller's outlet assignment. */
  @Operation(summary = "Get a stocktake", description = "A single stocktake by id, with its lines.")
  @GetMapping("/{id}")
  public ResponseEntity<StocktakeResponse> getById(@PathVariable("id") UUID id) {
    return ResponseEntity.ok(service.findById(id));
  }

  /** The outlet's stocktake history, most recent first (capped at 50). */
  @Operation(
      summary = "Stocktake history",
      description = "Most recent 50 stocktakes for an outlet.")
  @GetMapping
  public ResponseEntity<List<StocktakeResponse>> history(@RequestParam UUID businessId) {
    return ResponseEntity.ok(service.history(businessId));
  }

  private static void requireKey(String idempotencyKey) {
    if (idempotencyKey == null || idempotencyKey.isBlank()) {
      throw new IllegalArgumentException(
          "the Idempotency-Key header is required to submit a stocktake");
    }
    if (idempotencyKey.length() > 64) {
      throw new IllegalArgumentException("Idempotency-Key must be at most 64 characters");
    }
  }
}
