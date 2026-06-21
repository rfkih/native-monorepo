package id.co.nativeapp.restaurant.menu.controller;

import id.co.nativeapp.restaurant.menu.dto.CategoryResponse;
import id.co.nativeapp.restaurant.menu.dto.CreateCategoryRequest;
import id.co.nativeapp.restaurant.menu.dto.ReorderCategoryRequest;
import id.co.nativeapp.restaurant.menu.service.CategoryService;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Category management endpoints (Phase 3 catalog richness).
 *
 * <ul>
 *   <li>{@code GET /api/v1/menu/categories?businessId={uuid}} — list all categories for a business.
 *   <li>{@code POST /api/v1/menu/categories} — create a category; returns {@code 201 Created}.
 *   <li>{@code PATCH /api/v1/menu/categories/{id}/reorder} — change display_order.
 *   <li>{@code PATCH /api/v1/menu/categories/{id}/activate} — activate a category.
 *   <li>{@code PATCH /api/v1/menu/categories/{id}/deactivate} — deactivate a category.
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/menu/categories")
public class CategoryController {

  private final CategoryService categoryService;

  public CategoryController(CategoryService categoryService) {
    this.categoryService = categoryService;
  }

  /** Lists all categories for the given business, ordered by display_order then name. */
  @GetMapping
  public ResponseEntity<List<CategoryResponse>> listCategories(@RequestParam UUID businessId) {
    return ResponseEntity.ok(categoryService.findAllByBusiness(businessId));
  }

  /** Creates a new active category. Returns {@code 201 Created} with a {@code Location} header. */
  @PostMapping
  public ResponseEntity<CategoryResponse> createCategory(
      @Valid @RequestBody CreateCategoryRequest request) {
    CategoryResponse created = categoryService.createCategory(request);
    return ResponseEntity.created(URI.create("/api/v1/menu/categories/" + created.id()))
        .body(created);
  }

  /** Updates the display order of a category. */
  @PatchMapping("/{id}/reorder")
  public ResponseEntity<CategoryResponse> reorder(
      @PathVariable UUID id, @Valid @RequestBody ReorderCategoryRequest request) {
    return ResponseEntity.ok(categoryService.reorder(id, request));
  }

  /** Activates a category, making it and its items visible in the cashier view. */
  @PatchMapping("/{id}/activate")
  public ResponseEntity<CategoryResponse> activate(@PathVariable UUID id) {
    return ResponseEntity.ok(categoryService.activate(id));
  }

  /** Deactivates a category, hiding it and its items from the cashier view. */
  @PatchMapping("/{id}/deactivate")
  public ResponseEntity<CategoryResponse> deactivate(@PathVariable UUID id) {
    return ResponseEntity.ok(categoryService.deactivate(id));
  }
}
