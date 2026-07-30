package id.co.nativeapp.loyalty.giftcard.dto;

import java.util.UUID;

/** The response shape for a gift-card lookup by code — the POS redemption-validation surface. */
public record GiftCardResponse(
    UUID id, String code, String state, long balanceMinor, String currency) {}
