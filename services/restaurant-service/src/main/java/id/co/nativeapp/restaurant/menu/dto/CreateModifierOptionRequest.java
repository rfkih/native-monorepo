package id.co.nativeapp.restaurant.menu.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * Request body for {@code POST /api/v1/menu/{menuItemId}/modifier-groups/{groupId}/options}.
 *
 * @param businessId the owning business unit
 * @param name the option name (e.g. "Large", "Extra Spicy")
 * @param priceDeltaMinor signed price delta in minor units; positive = surcharge, 0 = no change,
 *     negative = discount-variant (NEVER a float — rule 8)
 * @param displayOrder sort position within the group's options
 */
public record CreateModifierOptionRequest(
    @NotNull UUID businessId,
    @NotBlank String name,
    long priceDeltaMinor,
    @Min(0) int displayOrder) {}
