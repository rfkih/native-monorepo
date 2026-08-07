package id.co.nativeapp.restaurant.payment.messaging;

import java.time.Instant;
import java.util.UUID;

/**
 * Decoded view of a {@code PaymentChargeSucceeded} event (ADR 0045) — payment-service's
 * dynamic-QRIS gateway settlement. Every vertical consumes the one topic and filters on {@link
 * #vertical()}; only events whose vertical is {@code restaurant} are applied here.
 *
 * <p>Consumed by {@link PaymentChargeSucceededListener} and applied, idempotently, by {@code
 * payment.service.PaymentChargeSucceededWriter}.
 *
 * @param eventId the durable event UUID taken from the {@code id} Kafka header; the idempotency key
 * @param chargeId the {@code payment_charge} aggregate id (payment-service) — also the Kafka
 *     partition key
 * @param companyId the owning tenant; used to bind {@link id.co.nativeapp.tenant.TenantContext}
 * @param vertical which POS vertical this charge settles: {@code restaurant | carwash | barbershop}
 *     (lowercase) — events whose vertical is not {@code restaurant} are skipped
 * @param paymentId this service's {@code payment} row id — the capture key and verification anchor
 * @param referenceId the carwash/barbershop TICKET id when the capture key differs from {@code
 *     paymentId}; always {@code null} for restaurant
 * @param businessId the outlet the charge was rung at
 * @param amountMinor the charged amount, minor units — must equal the payment's amount exactly, or
 *     the delivery parks instead of capturing
 * @param currency ISO-4217 currency code of {@code amountMinor} (never a float — rule 8)
 * @param provider the settling PSP (e.g. {@code MIDTRANS})
 * @param providerTxnId the provider's transaction id, or {@code null} if the provider omitted it
 * @param succeededAt when the settlement was recorded (UTC)
 */
public record PaymentChargeSucceededEvent(
    UUID eventId,
    UUID chargeId,
    String companyId,
    String vertical,
    UUID paymentId,
    UUID referenceId,
    UUID businessId,
    long amountMinor,
    String currency,
    String provider,
    String providerTxnId,
    Instant succeededAt) {}
