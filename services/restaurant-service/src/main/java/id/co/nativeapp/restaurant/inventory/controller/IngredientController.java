package id.co.nativeapp.restaurant.inventory.controller;

import id.co.nativeapp.restaurant.inventory.dto.AddIngredientStockRequest;
import id.co.nativeapp.restaurant.inventory.dto.CreateIngredientRequest;
import id.co.nativeapp.restaurant.inventory.dto.IngredientResponse;
import id.co.nativeapp.restaurant.inventory.dto.IngredientUsageResponse;
import id.co.nativeapp.restaurant.inventory.dto.SetIngredientStockRequest;
import id.co.nativeapp.restaurant.inventory.dto.UpdateIngredientRequest;
import id.co.nativeapp.restaurant.inventory.service.IngredientService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/ingredients} — the raw-material catalog (ADR 0046 phase 1). Gateway-routed under
 * POS_ROLES. The tenant ({@code company_id}) and actor come from the bound {@link
 * id.co.nativeapp.tenant.TenantContext TenantContext}, never from the body (rule 5).
 */
@Tag(name = "Ingredients", description = "Raw-material catalog + stock management (ADR 0046)")
@RestController
@RequestMapping("/api/v1/ingredients")
public class IngredientController {

  private final IngredientService service;

  public IngredientController(IngredientService service) {
    this.service = service;
  }

  /** Active ingredients for the given business, scoped by the bound tenant (RLS). */
  @Operation(
      summary = "List active ingredients for a business",
      description =
          "Returns active ingredients for the given business, scoped by the bound tenant (RLS).")
  @GetMapping
  public ResponseEntity<List<IngredientResponse>> list(@RequestParam UUID businessId) {
    return ResponseEntity.ok(service.findByBusiness(businessId));
  }

  /**
   * Per-ingredient quantity consumed by sales on one outlet-local day ("terpakai", V42) — feeds the
   * opname sheet's "terpakai hari ini" and the opname-history detail. The literal {@code /usage}
   * segment cannot collide with an id path (this controller maps no {@code GET /{id}}).
   */
  @Operation(
      summary = "Ingredient usage for a day",
      description =
          "Per-ingredient quantity consumed by sales on the given outlet-local calendar day"
              + " (Asia/Jakarta attribution, mirroring the register business_date convention)."
              + " Ingredients with no usage that day are absent. Usage accrues from the feature's"
              + " deployment forward.")
  @GetMapping("/usage")
  public ResponseEntity<List<IngredientUsageResponse>> usage(
      @RequestParam UUID businessId,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
    return ResponseEntity.ok(service.usageForDate(businessId, date));
  }

  /** Creates a new active ingredient. {@code 201 Created} + {@code Location} header on success. */
  @Operation(
      summary = "Create an ingredient",
      description =
          "Creates a new active ingredient. Returns 201 Created with a Location header on success."
              + " Bean-validation on the body rejects missing/invalid fields with a 400"
              + " ProblemDetail.")
  @PostMapping
  public ResponseEntity<IngredientResponse> create(
      @Valid @RequestBody CreateIngredientRequest request) {
    IngredientResponse created = service.create(request);
    return ResponseEntity.created(URI.create("/api/v1/ingredients/" + created.id())).body(created);
  }

  /** Partially updates an ingredient. Only the fields supplied in the request body are applied. */
  @Operation(
      summary = "Partially update an ingredient",
      description =
          "Applies a partial update to an ingredient — only non-null fields are changed. Returns 200"
              + " OK with the updated ingredient, 404 if not found or not visible to the bound"
              + " tenant, 400 on validation failure.")
  @PatchMapping("/{id}")
  public ResponseEntity<IngredientResponse> patch(
      @PathVariable UUID id, @Valid @RequestBody UpdateIngredientRequest request) {
    return ResponseEntity.ok(service.update(id, request));
  }

  /**
   * Soft-deletes an ingredient (sets {@code active = false}). The ingredient immediately disappears
   * from {@code GET /api/v1/ingredients} but historical ingredient-stocktake lines that reference
   * it are unaffected.
   */
  @Operation(
      summary = "Delete (soft-delete) an ingredient",
      description =
          "Soft-deletes an ingredient by setting active=false. Returns 204 No Content on success, 404"
              + " if the ingredient is not found or not visible to the bound tenant.")
  @DeleteMapping("/{id}")
  public ResponseEntity<Void> delete(@PathVariable UUID id) {
    service.deactivate(id);
    return ResponseEntity.noContent().build();
  }

  /**
   * Sets the absolute stock quantity for an ingredient. Unlike a menu item, an ingredient is ALWAYS
   * tracked — {@code quantity} is required (no infinite/untracked state, ADR 0046).
   */
  @Operation(
      summary = "Set absolute stock quantity",
      description =
          "Sets the absolute stock quantity for an ingredient (always tracked — no untracked state)."
              + " Returns 200 OK with the updated ingredient.")
  @PutMapping("/{id}/stock")
  public ResponseEntity<IngredientResponse> setStock(
      @PathVariable UUID id, @Valid @RequestBody SetIngredientStockRequest request) {
    return ResponseEntity.ok(service.setStock(id, request.quantity()));
  }

  /**
   * Adds a signed delta to an ingredient's stock. Positive values receive stock; negative values
   * manually reduce stock (floored at 0). When a price ({@code amountPaidMinor} + {@code
   * costCurrency}) accompanies a positive receive, it updates the moving weighted-average cost
   * (V36); otherwise it is a costless adjustment that preserves the unit cost.
   *
   * <p><strong>ADR 0067 Phase D, D1 — receive idempotency.</strong> An OPTIONAL {@code
   * Idempotency-Key} header (the {@code RegisterSessionController} idiom; max 64 chars, mirroring
   * the {@code goods_receipt.idempotency_key} column width) makes a PRICED receive safe to retry: a
   * replay with the SAME key + payload returns 200 with the current ingredient state and adds the
   * value/emits the event only once; the SAME key with a DIFFERENT payload is a 409. The header is
   * ignored on a costless call (no {@code goods_receipt} row to dedupe) and, for backward
   * compatibility, a priced call with NO key is unchanged pre-D1 behaviour (still susceptible to a
   * double-write on retry — callers should supply a key).
   */
  @Operation(
      summary = "Add a stock delta (optionally priced)",
      description =
          "Adds a signed delta to an ingredient's stock. Positive = receive; negative = manually"
              + " reduce (floored at 0). An optional amountPaidMinor + costCurrency on a positive"
              + " receive updates the moving-average cost. An optional Idempotency-Key header makes"
              + " a priced receive safe to retry: same key + payload replays without adding value"
              + " again; same key + a different payload is a 409. Returns 200 OK with the updated"
              + " ingredient.")
  @PostMapping("/{id}/stock/add")
  public ResponseEntity<IngredientResponse> addStock(
      @PathVariable UUID id,
      @Valid @RequestBody AddIngredientStockRequest request,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
    validateIdempotencyKey(idempotencyKey);
    return ResponseEntity.ok(
        service.addStock(
            id,
            request.amount(),
            request.amountPaidMinor(),
            request.costCurrency(),
            idempotencyKey));
  }

  private static void validateIdempotencyKey(String idempotencyKey) {
    if (idempotencyKey != null && idempotencyKey.length() > 64) {
      throw new IllegalArgumentException("Idempotency-Key must be at most 64 characters");
    }
  }
}
