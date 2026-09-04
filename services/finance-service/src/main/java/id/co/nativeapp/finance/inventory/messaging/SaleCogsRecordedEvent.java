package id.co.nativeapp.finance.inventory.messaging;

import java.time.Instant;
import java.util.UUID;
import org.apache.avro.generic.GenericRecord;

/**
 * Decoded {@code SaleCogsRecorded} event (ADR 0067 Phase C) — a sale's recipe-depletion cost
 * (restaurant's moving-average fold, ADR 0056). Finance debits the EXACT fold ({@link #cogsMinor})
 * to {@code 5100 COGS} against {@code 1100 Inventory} WHEN the bound tenant is perpetual-active for
 * {@link #occurredAt}'s period; otherwise a claimed no-op ({@link
 * id.co.nativeapp.finance.inventory.service.SaleCogsRecordedWriter}).
 *
 * @param eventId the source event UUID (idempotency key — the outbox row UUID from the Kafka {@code
 *     id} header)
 * @param saleId the sale aggregate id (restaurant's own idempotency anchor; traceability on the
 *     finance side — the {@code eventId} is finance's dedupe key)
 * @param companyId the owning tenant (UUID as string)
 * @param businessId the originating outlet — the dimensional {@code business_id} stamped on the
 *     COGS {@code ledger_posting}
 * @param occurredAt when the sale occurred — drives the accounting period (same period as the
 *     sale's revenue)
 * @param cogsMinor the Σ (depleted qty × moving-average unit cost) fold, minor units. Never a float
 *     (rule 8)
 * @param currency ISO-4217 code of {@link #cogsMinor}
 */
public record SaleCogsRecordedEvent(
    UUID eventId,
    UUID saleId,
    String companyId,
    UUID businessId,
    Instant occurredAt,
    long cogsMinor,
    String currency) {

  /** Decodes the Avro {@link GenericRecord} into the typed event. */
  public static SaleCogsRecordedEvent from(UUID eventId, GenericRecord record) {
    return new SaleCogsRecordedEvent(
        eventId,
        UUID.fromString(record.get("sale_id").toString()),
        record.get("company_id").toString(),
        UUID.fromString(record.get("business_id").toString()),
        Instant.ofEpochMilli((long) record.get("occurred_at")),
        (long) record.get("cogs_minor"),
        record.get("currency").toString());
  }

  /**
   * Guards the figures posting depends on. A violation means the event can never post correct money
   * — a poison message: the listener routes it to the DLT.
   *
   * @throws IllegalStateException if the currency is blank, or {@code cogsMinor} is not strictly
   *     positive (the producer only ever emits when its fold is positive — {@code sale.cogs_minor}
   *     both-or-neither CHECK, V44 — so a non-positive value here is a corrupt/malicious payload,
   *     the {@code StockReceivedEvent#assertValid} precedent)
   */
  public void assertValid() {
    if (currency == null || currency.isBlank()) {
      throw new IllegalStateException(
          "SaleCogsRecorded " + eventId + " has a blank currency — poison event, routing to DLT");
    }
    if (cogsMinor <= 0) {
      throw new IllegalStateException(
          "SaleCogsRecorded "
              + eventId
              + " cogs_minor is not positive ("
              + cogsMinor
              + ") — poison event, routing to DLT");
    }
  }
}
