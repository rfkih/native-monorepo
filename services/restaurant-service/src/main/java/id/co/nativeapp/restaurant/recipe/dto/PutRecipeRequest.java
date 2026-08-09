package id.co.nativeapp.restaurant.recipe.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Full-replace PUT body for a menu item's recipe (ADR 0050 phase A): the lines given here become
 * the ENTIRE recipe — lines absent from the list are deleted. An empty list removes the recipe.
 * Full-replace is deliberate: recipes are small documents edited rarely by one manager, and
 * replace-semantics eliminate cross-line lost-update anomalies (see ADR 0050).
 */
public record PutRecipeRequest(
    @NotNull @Size(max = 200) List<@Valid @NotNull RecipeLineInput> lines) {}
