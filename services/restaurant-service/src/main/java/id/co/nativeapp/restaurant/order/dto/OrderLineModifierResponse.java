package id.co.nativeapp.restaurant.order.dto;

import java.util.UUID;

/**
 * Response shape for one modifier snapshot on an order line — the receipt-reproducible record of
 * which modifier option was chosen and its price delta at order time.
 *
 * @param optionId the modifier option id (snapshot; the option may have been edited since)
 * @param nameSnapshot the option name at order time (e.g. "Large", "Extra Spicy")
 * @param priceDeltaMinor the signed price delta in minor units at order time (never a float)
 */
public record OrderLineModifierResponse(UUID optionId, String nameSnapshot, long priceDeltaMinor) {}
