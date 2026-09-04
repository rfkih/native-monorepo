package id.co.nativeapp.restaurant.recipe.service;

import id.co.nativeapp.restaurant.recipe.dto.AutoLinkResult;
import id.co.nativeapp.restaurant.recipe.dto.HppSummaryRow;
import id.co.nativeapp.restaurant.recipe.dto.PutRecipeRequest;
import id.co.nativeapp.restaurant.recipe.dto.RecipeResponse;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Facade over the recipe feature (ADR 0050 phase A) — the non-transactional entry point the
 * controller talks to; the transactional units of work live in {@link RecipeWriter} and {@link
 * RecipeReader} so every {@code @Transactional} method is reached through the Spring proxy (the
 * {@code IngredientService} pattern).
 */
@Service
public class RecipeService {

  private final RecipeReader reader;
  private final RecipeWriter writer;
  private final RecipeAutoLinkWriter autoLinkWriter;

  public RecipeService(
      RecipeReader reader, RecipeWriter writer, RecipeAutoLinkWriter autoLinkWriter) {
    this.reader = reader;
    this.writer = writer;
    this.autoLinkWriter = autoLinkWriter;
  }

  public RecipeResponse getRecipe(UUID menuItemId) {
    return reader.getRecipe(menuItemId);
  }

  /** Full-replace PUT; returns the recipe as re-read after the write (fresh HPP included). */
  public RecipeResponse putRecipe(UUID menuItemId, PutRecipeRequest request) {
    writer.replace(menuItemId, request);
    return reader.getRecipe(menuItemId);
  }

  public List<HppSummaryRow> hppSummary(UUID businessId) {
    return reader.hppSummary(businessId);
  }

  /**
   * "Lacak stok semua menu": 1:1-links every active recipe-less item of the outlet (idempotent).
   */
  public AutoLinkResult autoLinkAll(UUID businessId) {
    return autoLinkWriter.autoLinkAll(businessId);
  }

  /** 1:1-links ONE item (no-op if it already has a recipe); returns the resulting recipe. */
  public RecipeResponse autoLinkItem(UUID menuItemId) {
    autoLinkWriter.autoLinkItem(menuItemId);
    return reader.getRecipe(menuItemId);
  }
}
