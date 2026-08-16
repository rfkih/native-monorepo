package id.co.nativeapp.restaurant.recipe.dto;

/**
 * Result of the "Lacak stok" auto-link sweep: how many menu items were newly linked to a 1:1
 * ingredient, how many were skipped because they already had a recipe, and how many were BLOCKED (a
 * recipe-less item whose name collides with a non-{@code pcs} ingredient — not linked, not
 * corrupted; review W2). Idempotent — a second run returns {@code linkedCount = 0}.
 */
public record AutoLinkResult(int linkedCount, int skippedCount, int blockedCount) {}
