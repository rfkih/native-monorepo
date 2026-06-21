package id.co.nativeapp.restaurant.menu.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.util.UUID;

/**
 * Request body for {@code POST /api/v1/menu/{menuItemId}/modifier-groups/{groupId}/options}.
 *
 * @param businessId ignored — the service derives {@code business_id} from the parent modifier
 *     group loaded via {@code groupId} (finding #5: never trust business_id from the request body).
 *     Accepted for backward compatibility with existing clients but not validated or stored.
 * @param name the option name (e.g. "Large", "Extra Spicy")
 * @param priceDeltaMinor signed price delta in minor units; positive = surcharge, 0 = no change,
 *     negative = discount-variant (NEVER a float — rule 8)
 * @param displayOrder sort position within the group's options
 */
public record CreateModifierOptionRequest(
    UUID businessId, @NotBlank String name, long priceDeltaMinor, @Min(0) int displayOrder) {}
