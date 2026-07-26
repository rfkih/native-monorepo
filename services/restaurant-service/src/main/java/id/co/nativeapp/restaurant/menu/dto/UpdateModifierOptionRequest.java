package id.co.nativeapp.restaurant.menu.dto;

import jakarta.validation.constraints.Min;

/**
 * Request body for {@code PATCH
 * /api/v1/menu/{menuItemId}/modifier-groups/{groupId}/options/{optionId}}.
 *
 * <p>All fields are optional (patch semantics). Only non-null fields are applied.
 *
 * @param name the new option name; {@code null} means no change
 * @param priceDeltaMinor new signed price delta in minor units (never a float — rule 8); {@code
 *     null} means no change
 * @param displayOrder new sort position; {@code null} means no change
 */
public record UpdateModifierOptionRequest(
    String name, Long priceDeltaMinor, @Min(0) Integer displayOrder) {}
