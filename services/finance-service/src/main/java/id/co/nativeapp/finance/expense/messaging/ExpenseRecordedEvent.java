package id.co.nativeapp.finance.expense.messaging;

import id.co.nativeapp.money.Money;
import java.time.Instant;
import java.util.UUID;

/**
 * The decoded {@code ExpenseRecorded} event — the application command the consumer hands to the
 * expense posting service. An immutable record carrying exactly the fields finance needs from the
 * contract, already parsed out of the raw Avro {@link org.apache.avro.generic.GenericRecord}: the
 * source event id (idempotency), the owning tenant, the originating business, the expense amount as
 * {@link Money} (never a float), the {@code gl_hint} (drives the EXPENSE gl_account resolution via
 * {@code mapping_rule}), and when it occurred (drives the period and the effective rule version).
 *
 * <p>{@code companyId} is the tenant the consumer binds the handler to (via {@code
 * TenantContext.callAs}); it is carried on the event, never taken from a request.
 */
public record ExpenseRecordedEvent(
    UUID eventId,
    String companyId,
    UUID businessId,
    Money amount,
    String glHint,
    Instant occurredAt) {}
