package id.co.nativeapp.payment.charge.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.UUID;

/**
 * The console's create-charge request (ADR 0045): which vertical payment the dynamic QR settles.
 * {@code amountMinor} is the tender RESIDUAL the vertical checkout returned — the consumer verifies
 * it against the payment row before capturing, so a client-tampered amount can never capture.
 * {@code referenceId} carries the ticket id for carwash/barbershop; null for restaurant.
 */
public record CreateChargeRequest(
    @NotBlank String vertical,
    @NotNull UUID paymentId,
    UUID referenceId,
    @NotNull UUID businessId,
    @NotNull @Positive Long amountMinor,
    @NotBlank String currency) {}
