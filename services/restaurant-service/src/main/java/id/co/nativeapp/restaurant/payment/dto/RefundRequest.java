package id.co.nativeapp.restaurant.payment.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

/**
 * Request body for {@code POST /api/v1/payments/{id}/refund}.
 *
 * <p>The refund amount is supplied in minor units (integer; never a float — rule 8) plus an
 * ISO-4217 currency code. The server validates that the currency matches the payment's currency and
 * that the amount does not exceed the remaining refundable balance.
 *
 * @param amountMinor the amount to refund in minor units (must be positive)
 * @param currency the ISO-4217 currency code (must match the payment's currency)
 */
public record RefundRequest(@Positive long amountMinor, @NotBlank String currency) {}
