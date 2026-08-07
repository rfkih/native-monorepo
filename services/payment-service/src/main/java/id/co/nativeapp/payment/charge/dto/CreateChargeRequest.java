package id.co.nativeapp.payment.charge.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.UUID;

/**
 * The console's create-charge request (ADR 0045, DIVISION-scope amendment): which vertical payment
 * the dynamic QR settles. {@code amountMinor} is the tender RESIDUAL the vertical checkout returned
 * — the consumer verifies it against the payment row before capturing, so a client-tampered amount
 * can never capture. {@code referenceId} carries the ticket id for carwash/barbershop; null for
 * restaurant. {@code divisionId} is the outlet's parent business unit (nullable, client-supplied —
 * payment-service holds no org read model); it widens the effective gateway-mode resolution to
 * outlet ?? division ?? company but is NOT part of the idempotency payload match (it is advisory
 * only, unlike {@code paymentId}/{@code amountMinor}/{@code currency}).
 */
public record CreateChargeRequest(
    @NotBlank String vertical,
    @NotNull UUID paymentId,
    UUID referenceId,
    @NotNull UUID businessId,
    UUID divisionId,
    @NotNull @Positive Long amountMinor,
    @NotBlank String currency) {}
