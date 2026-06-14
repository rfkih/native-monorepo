package id.co.nativeapp.finance.revenue;

import id.co.nativeapp.money.Money;
import java.time.Instant;
import java.util.UUID;

/**
 * The decoded {@code SaleRecorded} event — the application command the consumer hands to the
 * posting service. An immutable record carrying exactly the fields finance needs from the contract,
 * already parsed out of the raw Avro {@link org.apache.avro.generic.GenericRecord}: the source
 * event id (used for idempotency), the owning tenant, the originating business, the sale amount as
 * {@link Money} (never a float), and when it occurred (drives the period).
 *
 * <p>{@code companyId} is the tenant the consumer binds the handler to (via {@code
 * TenantContext.callAs}); it is carried on the event, never taken from a request.
 */
public record SaleRecordedEvent(
    UUID eventId, String companyId, UUID businessId, Money amount, Instant occurredAt) {}
