package id.co.nativeapp.restaurant.menu.dto;

import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.lang.Nullable;

/**
 * Request body for {@code PATCH /api/v1/menu/{itemId}}.
 *
 * <p>All fields are optional — only non-null fields are applied to the item (partial update). The
 * currency of the item cannot be changed via this endpoint; {@code priceMinor} updates the amount
 * only while keeping the original currency.
 *
 * <h3>imageUrl null/absent convention</h3>
 *
 * <p>Java records cannot distinguish a JSON-absent field from an explicit {@code null}. The chosen
 * convention for this increment is:
 *
 * <ul>
 *   <li>{@code null} (absent from JSON, or explicitly {@code "imageUrl": null}) — leave the
 *       existing image unchanged.
 *   <li>Non-empty string — set/replace the image with that value.
 *   <li>Empty string ({@code "imageUrl": ""}) — clear the image (store {@code NULL} in the DB).
 * </ul>
 *
 * <p>This means a client that wants to clear the image sends {@code "imageUrl": ""}, and a client
 * that wants to leave it untouched omits the field entirely. The domain method
 * {@link id.co.nativeapp.restaurant.menu.domain.MenuItem#update} enforces the same rule.
 */
public record UpdateMenuItemRequest(
    @Nullable String name,
    @Nullable String category,
    @Nullable @Positive Long priceMinor,
    @Nullable @Size(max = 3_000_000) String imageUrl) {}
