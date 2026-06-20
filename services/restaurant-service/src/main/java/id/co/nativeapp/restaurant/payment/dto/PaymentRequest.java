package id.co.nativeapp.restaurant.payment.dto;

import id.co.nativeapp.restaurant.payment.domain.TenderType;
import jakarta.validation.constraints.NotNull;

/**
 * Optional payment block on a checkout request ({@code POST /api/v1/orders}). When present, the
 * order is paid in the same call.
 *
 * <p>{@code tenderedMinor} is the physical cash handed over (integer minor units), required for a
 * {@link TenderType#CASH} tender so the change can be computed; it is ignored for digital tenders.
 * The amount due is never trusted from the client — it is the server-computed order total.
 *
 * @param tenderType the tender (CASH live; QRIS/CARD via the flagged-pending provider, ADR 0006)
 * @param tenderedMinor cash handed over, in minor units (required for CASH)
 */
public record PaymentRequest(@NotNull TenderType tenderType, Long tenderedMinor) {}
