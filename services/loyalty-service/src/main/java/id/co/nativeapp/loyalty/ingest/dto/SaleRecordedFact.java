package id.co.nativeapp.loyalty.ingest.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * The decoded shape of a consumed {@code SaleRecorded} event, projected down to the fields
 * loyalty-service acts on. {@code eventId} is the durable outbox event id (the Kafka {@code id}
 * header) — the {@code ProcessedEventStore} dedupe key AND the ledger's {@code source_event_id}
 * idempotency backstop. Every {@code loyalty*}/{@code giftCard*} field is nullable exactly as on
 * the wire (ADR 0027) — {@code null} means "not involved in this sale".
 */
public record SaleRecordedFact(
    UUID eventId,
    UUID saleId,
    String companyId,
    UUID businessId,
    long amountMinor,
    String currency,
    Instant occurredAt,
    Long subtotalMinor,
    Long discountMinor,
    UUID loyaltyMemberId,
    Long loyaltyRedeemedPoints,
    Long loyaltyRedeemedMinor,
    UUID giftCardId,
    Long giftCardRedeemedMinor) {}
