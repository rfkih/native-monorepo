package id.co.nativeapp.finance.revenue.messaging;

import id.co.nativeapp.money.Money;
import java.time.Instant;
import java.util.UUID;

/**
 * The decoded {@code SaleRecorded} event — the application command the consumer hands to the
 * posting service. An immutable record carrying exactly the fields finance needs from the contract,
 * already parsed out of the raw Avro {@link org.apache.avro.generic.GenericRecord}: the source
 * event id (used for idempotency), the owning tenant, the originating business, the sale amount as
 * {@link Money} (never a float), when it occurred (drives the period), and the optional tender type
 * (ADR 0006 slice 2 — drives GL clearing-account routing).
 *
 * <p>{@code companyId} is the tenant the consumer binds the handler to (via {@code
 * TenantContext.callAs}); it is carried on the event, never taken from a request.
 *
 * <p>{@code tenderType} is the nullable tender string ({@code "CASH"}, {@code "QRIS"}, {@code
 * "CARD"}, or {@code null} for legacy/no-payment sales). Finance maps {@code null}/{@code "CASH"}
 * to {@code CASH_CLEARING}, {@code "QRIS"} to {@code QRIS_CLEARING}, and {@code "CARD"} to {@code
 * CARD_CLEARING} (the existing carwash / direct-sale paths remain unchanged).
 */
public record SaleRecordedEvent(
    UUID eventId,
    String companyId,
    UUID businessId,
    Money amount,
    Instant occurredAt,
    String tenderType) {

  /**
   * Backward-compatible constructor for callers that pre-date the {@code tenderType} field (e.g.
   * tests written before slice 2). Sets {@code tenderType} to {@code null}.
   */
  public SaleRecordedEvent(
      UUID eventId, String companyId, UUID businessId, Money amount, Instant occurredAt) {
    this(eventId, companyId, businessId, amount, occurredAt, null);
  }
}
