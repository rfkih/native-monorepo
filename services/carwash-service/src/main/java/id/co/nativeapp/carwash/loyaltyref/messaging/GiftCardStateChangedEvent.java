package id.co.nativeapp.carwash.loyaltyref.messaging;

import java.time.Instant;
import java.util.UUID;

/**
 * Decoded view of a {@code GiftCardStateChanged} event (ADR 0027) — the projection the consumer
 * set-if-newer upserts into {@code gift_card_ref}.
 *
 * @param eventId the durable event UUID taken from the {@code id} Kafka header; the idempotency key
 * @param giftCardId the gift card aggregate id (also the Kafka partition key)
 * @param companyId the owning tenant; used to bind {@link id.co.nativeapp.tenant.TenantContext}
 * @param state {@code ACTIVE|DEPLETED|EXPIRED|VOID}
 * @param balanceMinor the card's resulting stored-value balance — an ABSOLUTE set-to-value, minor
 *     units. Never a float (rule 8)
 * @param currency ISO-4217 currency code
 * @param balanceSeq monotonically increasing per-card sequence; set-if-newer guard
 * @param occurredAt when the state/balance change occurred
 */
public record GiftCardStateChangedEvent(
    UUID eventId,
    UUID giftCardId,
    String companyId,
    String state,
    long balanceMinor,
    String currency,
    long balanceSeq,
    Instant occurredAt) {}
