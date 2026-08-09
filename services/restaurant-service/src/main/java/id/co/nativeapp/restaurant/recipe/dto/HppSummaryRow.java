package id.co.nativeapp.restaurant.recipe.dto;

import id.co.nativeapp.restaurant.recipe.dto.RecipeResponse.Completeness;
import java.util.UUID;
import org.springframework.lang.Nullable;

/**
 * One menu item's derived HPP in the outlet-wide summary (ADR 0050 phase A) — feeds the console's
 * HPP/margin chip on the menu list. Only items that HAVE a recipe appear; the console joins onto
 * its already-loaded menu list by {@code menuItemId} (price/name stay client-side).
 */
public record HppSummaryRow(
    UUID menuItemId,
    @Nullable Long unitHppMinor,
    @Nullable String hppCurrency,
    Completeness completeness) {}
