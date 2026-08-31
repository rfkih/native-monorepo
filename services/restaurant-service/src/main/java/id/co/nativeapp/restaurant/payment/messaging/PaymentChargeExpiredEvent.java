package id.co.nativeapp.restaurant.payment.messaging;

import java.time.Instant;
import java.util.UUID;

/**
 * Decoded view of a {@code PaymentChargeExpired} event (ADR 0045) — the un-happy-path counterpart of
 * {@link PaymentChargeSucceededEvent}: payment-service's dynamic-QRIS gateway charge terminated
 * WITHOUT settling (expired / canceled / failed after its QR was issued). Every vertical consumes
 * the one topic and filters on {@link #vertical()}; only events whose vertical is {@code restaurant}
 * are applied here.
 *
 * <p>Consumed by {@link PaymentChargeExpiredListener} and applied, idempotently, by {@code
 * payment.service.PaymentChargeExpiredWriter}, which RELEASES the PENDING tender this charge was
 * holding (no money moves).
 *
 * @param eventId the durable event UUID taken from the {@code id} Kafka header; the idempotency key
 * @param chargeId the {@code payment_charge} aggregate id (payment-service) — also the Kafka
 *     partition key
 * @param companyId the owning tenant; used to bind {@link id.co.nativeapp.tenant.TenantContext}
 * @param vertical which POS vertical this charge was holding: {@code restaurant | carwash |
 *     barbershop} (lowercase) — events whose vertical is not {@code restaurant} are skipped
 * @param paymentId this service's {@code payment} row id — the release anchor
 * @param referenceId the carwash/barbershop TICKET id when the release key differs from {@code
 *     paymentId}; always {@code null} for restaurant
 * @param businessId the outlet the charge was rung at
 * @param amountMinor the charge amount, minor units — audit/observability only (no capture)
 * @param currency ISO-4217 currency code of {@code amountMinor} (never a float — rule 8)
 * @param reason why the charge terminated: {@code EXPIRED | CANCELED | FAILED} — the release is
 *     identical regardless; the value is recorded in park messages
 * @param occurredAt when the terminal transition was recorded (UTC)
 */
public record PaymentChargeExpiredEvent(
    UUID eventId,
    UUID chargeId,
    String companyId,
    String vertical,
    UUID paymentId,
    UUID referenceId,
    UUID businessId,
    long amountMinor,
    String currency,
    String reason,
    Instant occurredAt) {}
