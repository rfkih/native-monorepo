package id.co.nativeapp.finance.inventory.messaging;

import java.time.Instant;
import java.util.UUID;
import org.apache.avro.generic.GenericRecord;

/**
 * Decoded {@code StockReceived} event (ADR 0067 Phase B) — a PRICED goods receipt (restaurant's
 * moving-average receive, ADR 0056). Finance capitalizes the EXACT landed value ({@link
 * #valueMinor}) to {@code 1100 Inventory} against {@code 2050 GRNI Clearing} WHEN the bound tenant
 * is perpetual-active for {@link #receivedAt}'s period; otherwise a claimed no-op ({@link
 * id.co.nativeapp.finance.inventory.service.StockReceivedWriter}).
 *
 * @param eventId the source event UUID (idempotency key — the outbox row UUID from the Kafka {@code
 *     id} header)
 * @param receiptId the {@code goods_receipt} aggregate id (restaurant's own idempotency anchor;
 *     traceability only on the finance side — the {@code eventId} is finance's dedupe key)
 * @param companyId the owning tenant (UUID as string)
 * @param businessId the originating outlet
 * @param ingredientId the received ingredient (UUID reference only, not PII; traceability — finance
 *     posts one aggregate 1100/2050 pair and does not dimension the GL on it)
 * @param qty units received, in the ingredient's own base unit
 * @param valueMinor the EXACT total paid for the receipt, minor units. Never a float (rule 8)
 * @param currency ISO-4217 code of {@link #valueMinor}
 * @param receivedAt when the goods were received — drives the accounting period
 */
public record StockReceivedEvent(
    UUID eventId,
    UUID receiptId,
    String companyId,
    UUID businessId,
    UUID ingredientId,
    long qty,
    long valueMinor,
    String currency,
    Instant receivedAt) {

  /** Decodes the Avro {@link GenericRecord} into the typed event. */
  public static StockReceivedEvent from(UUID eventId, GenericRecord record) {
    return new StockReceivedEvent(
        eventId,
        UUID.fromString(record.get("receipt_id").toString()),
        record.get("company_id").toString(),
        UUID.fromString(record.get("business_id").toString()),
        UUID.fromString(record.get("ingredient_id").toString()),
        (long) record.get("qty"),
        (long) record.get("value_minor"),
        record.get("currency").toString(),
        Instant.ofEpochMilli((long) record.get("received_at")));
  }

  /**
   * Guards the figures posting depends on. A violation means the event can never post correct money
   * — a poison message: the listener routes it to the DLT.
   *
   * @throws IllegalStateException if the currency is blank, or {@code valueMinor} is negative
   *     (restaurant's {@code goods_receipt} CHECK already forbids this, but a corrupt/malicious
   *     payload is defended against here too — the {@code StocktakeCompletedEvent#assertValid}
   *     precedent)
   */
  public void assertValid() {
    if (currency == null || currency.isBlank()) {
      throw new IllegalStateException(
          "StockReceived " + eventId + " has a blank currency — poison event, routing to DLT");
    }
    if (valueMinor < 0) {
      throw new IllegalStateException(
          "StockReceived "
              + eventId
              + " value_minor is negative ("
              + valueMinor
              + ") — poison event, routing to DLT");
    }
  }
}
