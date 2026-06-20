package id.co.nativeapp.restaurant.payment.service;

import id.co.nativeapp.money.Money;
import id.co.nativeapp.restaurant.payment.domain.TenderType;
import java.util.UUID;

/**
 * What a {@link PaymentProvider} is asked to authorize: the order being paid, the tender, the
 * server-computed {@code amount} due (Money), the cash {@code tenderedMinor} (null for digital),
 * and the idempotency key. An internal application value type — not an HTTP DTO.
 */
public record PaymentInstruction(
    UUID orderId,
    UUID businessId,
    TenderType tenderType,
    Money amount,
    Long tenderedMinor,
    String idempotencyKey) {}
