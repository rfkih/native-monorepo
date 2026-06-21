package id.co.nativeapp.restaurant.menu.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.UUID;

/**
 * Request body for {@code POST /api/v1/menu/{menuItemId}/modifier-groups}.
 *
 * @param businessId ignored — the service derives {@code business_id} from the parent menu item
 *     loaded via {@code menuItemId} (finding #5: never trust business_id from the request body).
 *     Accepted for backward compatibility with existing clients but not validated or stored.
 * @param name the group name (e.g. "Size", "Spice")
 * @param selectionType {@code SINGLE} or {@code MULTI}
 * @param required whether the cashier must select at least {@code minSelect} options
 * @param minSelect minimum number of selections; must be &ge; 0
 * @param maxSelect maximum number of selections; must be &ge; {@code minSelect}
 * @param displayOrder sort position within the item's modifier groups
 */
public record CreateModifierGroupRequest(
    UUID businessId,
    @NotBlank String name,
    @NotNull @Pattern(regexp = "SINGLE|MULTI") String selectionType,
    boolean required,
    @Min(0) int minSelect,
    @Min(1) int maxSelect,
    @Min(0) int displayOrder) {}
