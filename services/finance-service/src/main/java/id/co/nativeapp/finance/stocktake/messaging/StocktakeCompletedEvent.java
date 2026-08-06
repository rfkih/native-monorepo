package id.co.nativeapp.finance.stocktake.messaging;

import java.time.Instant;
import java.util.UUID;
import org.apache.avro.generic.GenericRecord;

/**
 * Decoded {@code StocktakeCompleted} event (ADR 0038 phase 3, physical inventory stocktake).
 * Finance posts ONLY the signed net valued shrinkage ({@link #shrinkageMinor}) — a server-computed
 * figure from the producer, Σ over cost-bearing lines of {@code (system_qty − counted_qty) ×
 * unit_cost_minor}: positive = a net loss/shrinkage ({@code Dr INVENTORY_SHRINKAGE / Cr
 * INVENTORY}), negative = a net gain/found stock ({@code Dr INVENTORY / Cr INVENTORY_SHRINKAGE}),
 * zero = no journal entry (the stocktake is still marked processed). Revenue/COGS-on-sale is never
 * touched — this is a periodic stocktake, not perpetual inventory.
 *
 * <p>The event is TRUSTED service data (the {@code RegisterSessionClosedEvent.overShortMinor}
 * precedent) — finance cannot recompute the valued shrinkage itself (it has no visibility into the
 * restaurant-service stock ledger) — but {@link #assertValid()} still guards the two figures a
 * corrupt/malicious payload could break posting with: a non-blank currency and a {@code
 * shrinkage_minor} that is not {@link Long#MIN_VALUE} (so {@code Math.abs} stays safe). A violation
 * is a poison message the listener routes to the DLT.
 *
 * @param eventId the source event UUID (idempotency key — the outbox row UUID from the Kafka {@code
 *     id} header)
 * @param stocktakeId the stocktake aggregate id
 * @param companyId the owning tenant (UUID as string)
 * @param businessId the outlet/business the count was taken at
 * @param countedAt when the stocktake was submitted — drives the accounting period ({@code
 *     periodOf(countedAt)})
 * @param shrinkageMinor SIGNED net valued shrinkage in minor units; positive = loss, negative =
 *     gain, zero = no entry
 * @param currency ISO-4217 currency code of {@link #shrinkageMinor}
 */
public record StocktakeCompletedEvent(
    UUID eventId,
    UUID stocktakeId,
    String companyId,
    UUID businessId,
    Instant countedAt,
    long shrinkageMinor,
    String currency) {

  /** Decodes the Avro {@link GenericRecord} into the typed event. */
  public static StocktakeCompletedEvent from(UUID eventId, GenericRecord record) {
    return new StocktakeCompletedEvent(
        eventId,
        UUID.fromString(record.get("stocktake_id").toString()),
        record.get("company_id").toString(),
        UUID.fromString(record.get("business_id").toString()),
        Instant.ofEpochMilli((long) record.get("counted_at")),
        (long) record.get("shrinkage_minor"),
        record.get("currency").toString());
  }

  /**
   * Guards the two figures posting depends on. A violation means the event can never post correct
   * money — a poison message: the listener routes it to the DLT rather than posting from a
   * contradictory/dangerous figure.
   *
   * @throws IllegalStateException if the currency is blank, or {@code shrinkageMinor} is {@link
   *     Long#MIN_VALUE} (would make {@code Math.abs} overflow into a negative number)
   */
  public void assertValid() {
    if (currency == null || currency.isBlank()) {
      throw new IllegalStateException(
          "StocktakeCompleted " + eventId + " has a blank currency — poison event, routing to DLT");
    }
    if (shrinkageMinor == Long.MIN_VALUE) {
      throw new IllegalStateException(
          "StocktakeCompleted "
              + eventId
              + " shrinkage_minor is Long.MIN_VALUE (Math.abs unsafe) — poison event, routing to"
              + " DLT");
    }
  }
}
