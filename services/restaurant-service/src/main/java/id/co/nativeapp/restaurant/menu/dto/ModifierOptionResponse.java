package id.co.nativeapp.restaurant.menu.dto;

import id.co.nativeapp.restaurant.menu.domain.ModifierOption;
import java.util.UUID;

/**
 * Response body for a single modifier option.
 *
 * @param id the option id
 * @param groupId the owning modifier group
 * @param businessId the owning business unit
 * @param name the option name
 * @param priceDeltaMinor signed price delta in minor units (never a float)
 * @param available whether this option is currently available for selection
 * @param displayOrder sort position
 */
public record ModifierOptionResponse(
    UUID id,
    UUID groupId,
    UUID businessId,
    String name,
    long priceDeltaMinor,
    boolean available,
    int displayOrder) {

  /** Maps from the write-path aggregate. */
  public static ModifierOptionResponse from(ModifierOption option) {
    return new ModifierOptionResponse(
        option.getId(),
        option.getGroupId(),
        option.getBusinessId(),
        option.getName(),
        option.getPriceDeltaMinor(),
        option.isAvailable(),
        option.getDisplayOrder());
  }
  // NOTE: from(ModifierOptionView) lives in ModifierReader (service layer) — not here —
  // because the ArchUnit rule prohibits dto from depending on projection.
}
