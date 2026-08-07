package id.co.nativeapp.carwash.payment.messaging;

import java.time.Instant;
import java.util.UUID;

/**
 * Decoded {@code PaymentChargeSucceeded} (ADR 0045) — payment-service's outbox payload, produced
 * when a dynamic-QRIS gateway charge (via the merchant's OWN Midtrans account) reaches SUCCEEDED.
 * carwash-service is one of three verticals on this topic ({@code restaurant}, {@code carwash},
 * {@code barbershop}); {@link PaymentChargeSucceededListener} decodes every record regardless of
 * {@link #vertical()} and {@code payment.service.PaymentChargeSucceededWriter} skips the ones that
 * are not carwash's.
 *
 * @param eventId the durable event id (the Kafka {@code id} header) — NOT read from the payload,
 *     the {@code ProcessedEventStore} dedupe key
 * @param chargeId the {@code payment_charge} aggregate id (also the Kafka partition key)
 * @param companyId the owning tenant (UUID as string) — the {@code TenantContext} bind source
 * @param vertical {@code restaurant} | {@code carwash} | {@code barbershop} (lowercase); only
 *     {@code carwash} is acted on here
 * @param paymentId the vertical's payment row id — carwash's verification anchor (carwash captures
 *     by TICKET id, carried in {@link #referenceId()}, not by this field)
 * @param referenceId the TICKET id this charge settles (carwash/barbershop capture key); {@code
 *     null} means the producer never populated it — capture cannot proceed (park)
 * @param businessId the outlet the charge was rung at (a real OUTLET org unit, ADR 0012)
 * @param amountMinor the charged amount in minor units — must equal the vertical payment's amount
 *     exactly; a mismatch parks, never captures (rule 8: integer minor units, never a float)
 * @param currency ISO-4217 currency code of {@link #amountMinor()} (QRIS is IDR-only)
 * @param provider the settling PSP ({@code MIDTRANS})
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
