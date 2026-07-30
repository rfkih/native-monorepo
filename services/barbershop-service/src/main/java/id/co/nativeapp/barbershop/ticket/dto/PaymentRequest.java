package id.co.nativeapp.barbershop.ticket.dto;

import id.co.nativeapp.barbershop.payment.domain.TenderType;
import jakarta.validation.constraints.NotNull;

/**
 * The tender for a {@link CheckoutRequest}.
 *
 * @param tenderType {@code CASH}, {@code QRIS}, or {@code CARD}
 * @param tenderedMinor the cash tendered amount in minor units; required for {@code CASH}, ignored
 *     for digital tenders
 */
public record PaymentRequest(@NotNull TenderType tenderType, Long tenderedMinor) {}
