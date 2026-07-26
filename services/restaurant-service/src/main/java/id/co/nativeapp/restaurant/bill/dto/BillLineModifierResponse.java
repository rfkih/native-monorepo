package id.co.nativeapp.restaurant.bill.dto;

import java.util.UUID;

/**
 * Wire shape for a modifier snapshot on a bill line.
 *
 * @param optionId the modifier option id (snapshot)
 * @param nameSnapshot the option name at append time
 * @param priceDeltaMinor the signed price delta in minor units
 */
public record BillLineModifierResponse(UUID optionId, String nameSnapshot, long priceDeltaMinor) {}
