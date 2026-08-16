package id.co.nativeapp.restaurant.recipe.controller;

import id.co.nativeapp.restaurant.recipe.dto.AutoLinkResult;
import id.co.nativeapp.restaurant.recipe.dto.HppSummaryRow;
import id.co.nativeapp.restaurant.recipe.dto.PutRecipeRequest;
import id.co.nativeapp.restaurant.recipe.dto.RecipeResponse;
import id.co.nativeapp.restaurant.recipe.service.RecipeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Recipes/BOM under the menu surface (ADR 0050 phase A). Rides the existing {@code /api/v1/menu/**}
 * gateway route (POS_ROLES) — no new route bean. Tenant + actor come from the bound {@link
 * id.co.nativeapp.tenant.TenantContext TenantContext}, never from the body (rule 5).
 */
@Tag(name = "Recipes", description = "Menu-item recipes/BOM + derived HPP (ADR 0050)")
@RestController
@RequestMapping("/api/v1/menu")
public class RecipeController {

  private final RecipeService service;

  public RecipeController(RecipeService service) {
    this.service = service;
  }

  /** The menu item's recipe with derived HPP; an item without a recipe returns empty lines. */
  @Operation(
      summary = "Get a menu item's recipe",
      description =
          "Returns the item's recipe lines (base + per-modifier-option deltas) joined with"
              + " ingredient display fields, plus the derived HPP in integer minor units and a"
              + " completeness flag. 404 if the item is unknown for the bound tenant is NOT raised"
              + " here — an unknown item simply has no lines; the PUT validates existence.")
  @GetMapping("/{itemId}/recipe")
  public ResponseEntity<RecipeResponse> get(@PathVariable UUID itemId) {
    return ResponseEntity.ok(service.getRecipe(itemId));
  }

  /** Full-replace: the body's lines become the ENTIRE recipe (empty list removes it). */
  @Operation(
      summary = "Replace a menu item's recipe",
      description =
          "Full-replace PUT: the given lines become the entire recipe; lines absent from the body"
              + " are deleted, an empty list removes the recipe. 404 unknown item, 422 on content"
              + " violations (unknown/cross-outlet/inactive ingredient, foreign modifier option,"
              + " duplicate line, sign rules), 400 on malformed body.")
  @PutMapping("/{itemId}/recipe")
  public ResponseEntity<RecipeResponse> put(
      @PathVariable UUID itemId, @Valid @RequestBody PutRecipeRequest request) {
    return ResponseEntity.ok(service.putRecipe(itemId, request));
  }

  /**
   * "Lacak stok semua menu" — 1:1 auto-link for every active recipe-less item at the outlet: each
   * gets a same-named ingredient (pcs, stock 0) + a base line of 1/portion, so its sales start
   * depleting stock and the opname prefill starts moving. Idempotent (second run links 0).
   */
  @Operation(
      summary = "Auto-link all recipe-less menu items to 1:1 ingredients",
      description =
          "For every ACTIVE menu item of the outlet without a recipe: creates (or reuses) a"
              + " same-named ingredient (unit pcs, stock 0, no cost) and a 1-per-portion base"
              + " recipe line. Items that already have a recipe are never touched. Idempotent.")
  @PostMapping("/recipes/auto-link")
  public ResponseEntity<AutoLinkResult> autoLinkAll(@RequestParam UUID businessId) {
    return ResponseEntity.ok(service.autoLinkAll(businessId));
  }

  /** 1-klik "Lacak stok" for ONE item — no-op if it already has a recipe (never overwrites). */
  @Operation(
      summary = "Auto-link one menu item to a 1:1 ingredient",
      description =
          "Creates (or reuses) a same-named ingredient and a 1-per-portion base recipe line for"
              + " this item; a no-op when the item already has a recipe. Returns the resulting"
              + " recipe. 404 unknown item.")
  @PostMapping("/{itemId}/recipe/auto-link")
  public ResponseEntity<RecipeResponse> autoLinkItem(@PathVariable UUID itemId) {
    return ResponseEntity.ok(service.autoLinkItem(itemId));
  }

  /** Outlet-wide derived HPP per menu item — feeds the console's HPP/margin chips in one call. */
  @Operation(
      summary = "HPP summary for a business",
      description =
          "One row per menu item that has a recipe: derived HPP in integer minor units, its"
              + " currency, and a completeness flag. Items without recipes are absent — the"
              + " console joins on its already-loaded menu list.")
  @GetMapping("/hpp-summary")
  public ResponseEntity<List<HppSummaryRow>> hppSummary(@RequestParam UUID businessId) {
    return ResponseEntity.ok(service.hppSummary(businessId));
  }
}
