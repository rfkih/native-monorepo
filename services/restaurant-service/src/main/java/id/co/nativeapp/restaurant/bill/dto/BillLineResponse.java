package id.co.nativeapp.restaurant.bill.dto;

import java.util.List;
import java.util.UUID;

/**
 * Wire shape for one line on a bill.
 *
 * @param id the bill line id (individually addressable for Increment 3 split-check)
 * @param menuItemId the originating menu item
 * @param nameSnapshot the item name at append time
 * @param unitPriceMinor the base unit price in minor units
 * @param modifierDeltaMinor the summed modifier price delta in minor units
 * @param qty the quantity
 * @param lineTotalMinor the pre-computed line total in minor units
 * @param modifiers the selected modifier snapshots
 * @param paid {@code true} once this line has been included in a paid check
 */
public record BillLineResponse(
    UUID id,
    UUID menuItemId,
    String nameSnapshot,
    long unitPriceMinor,
    long modifierDeltaMinor,
    int qty,
    long lineTotalMinor,
    List<BillLineModifierResponse> modifiers,
    boolean paid) {}
