package id.co.nativeapp.entitlement.entitlement.messaging;

import java.util.UUID;

/**
 * The decoded {@code CompanyCreated} event — the application command the consumer hands to the
 * default-entitlement granter. An immutable record carrying exactly the fields entitlement-service
 * needs from the contract, already parsed out of the raw Avro {@link
 * org.apache.avro.generic.GenericRecord}: the source event id (used for idempotency) and the new
 * company id (the tenant the consumer binds the grant transaction to).
 *
 * <p>{@code companyId} is the tenant the consumer binds the handler to (via {@code
 * TenantContext.callAs}); it is carried on the event, never taken from a request. {@code
 * baseCurrency} / {@code defaultLanguage} are on the contract but entitlement-service does not need
 * them to grant defaults, so they are intentionally not carried here.
 */
public record CompanyCreatedEvent(UUID eventId, String companyId) {}
