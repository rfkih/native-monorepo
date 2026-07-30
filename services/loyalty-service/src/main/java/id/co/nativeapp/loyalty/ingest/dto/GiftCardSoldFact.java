package id.co.nativeapp.loyalty.ingest.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * The decoded shape of a consumed {@code GiftCardSold} event. {@code eventId} is the durable outbox
 * event id (the Kafka {@code id} header) — dedupe + ledger idempotency key. {@code
 * giftCardSaleId} is the payload's OWN unique id (see the catalog: "the idempotency/dedupe key"),
 * decoded here for traceability, but {@code eventId} (the header) is what this service actually
 * dedupes and keys the ledger backstop on — the producer convention is that the two are the SAME
 * value (see {@code GiftCardSoldConsumerSchema} class javadoc).
 */
public record GiftCardSoldFact(
    UUID eventId,
    String giftCardSaleId,
    UUID giftCardId,
    String companyId,
    UUID businessId,
    long amountMinor,
    String currency,
    Instant occurredAt) {}
