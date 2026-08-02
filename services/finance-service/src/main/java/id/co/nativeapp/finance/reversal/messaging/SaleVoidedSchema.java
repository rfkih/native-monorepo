package id.co.nativeapp.finance.reversal.messaging;

import id.co.nativeapp.events.AvroSerde;
import id.co.nativeapp.money.Money;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.UUID;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericRecord;

/**
 * Loads finance-service's consumer view of the {@code SaleVoided} Avro schema from the classpath
 * ({@code avro/SaleVoided.avsc}, sourced from {@code libs/contracts} — ADR 0003) and decodes raw
 * Avro bytes into a {@link SaleVoidedEvent}.
 *
 * <p>The money is reconstructed as {@code libs/money} {@link Money} from the integer {@code
 * amount_minor} + ISO-4217 {@code currency} (never a float). The optional {@code tender_type}
 * (nullable string) is decoded — {@code null} for legacy/no-tender sales. Finance uses the {@code
 * tender_type} to reverse the correct clearing account (same routing as {@code SaleRecorded}:
 * null/CASH → CASH_CLEARING, QRIS → QRIS_CLEARING, CARD → CARD_CLEARING).
 *
 * <p>ADR 0006 slice 4: finance reverses the original {@code SaleRecorded} ledger posting with a
 * contra entry whenever it receives {@code SaleVoided}.
 */
public final class SaleVoidedSchema {

  /** Classpath location of the consumer-copy {@code .avsc} (from {@code libs/contracts}). */
  public static final String RESOURCE = "avro/SaleVoided.avsc";

  /** The Kafka topic the producer outbox event is routed to. */
  public static final String TOPIC = "SaleVoided";

  private static final Schema SCHEMA = parse();

  private SaleVoidedSchema() {
    // static holder
  }

  /** The parsed reader schema for {@code SaleVoided} (finance's consumer view). */
  public static Schema schema() {
    return SCHEMA;
  }

  /**
   * Decodes raw Avro bytes (the producer outbox payload) into a {@link SaleVoidedEvent}, using this
   * consumer view of the schema.
   *
   * @param eventId the event's UUID (the outbox row / Debezium message id) — the idempotency key
   * @param payload the raw Avro bytes off the topic
   */
  public static SaleVoidedEvent decode(UUID eventId, byte[] payload) {
    GenericRecord record = AvroSerde.deserialize(payload, SCHEMA);
    Money amount =
        Money.ofMinor((long) record.get("amount_minor"), record.get("currency").toString());
    Object tenderTypeRaw = record.get("tender_type");
    String tenderType = (tenderTypeRaw != null) ? tenderTypeRaw.toString() : null;
    // Phase B (ADR 0036): additive nullable channel — the reader schema's default fills null for
    // payloads written before the field existed (rule 7).
    Object channelRaw = record.get("channel");
    String channel = (channelRaw != null) ? channelRaw.toString() : null;
    return new SaleVoidedEvent(
        UUID.fromString(record.get("void_id").toString()),
        record.get("company_id").toString(),
        UUID.fromString(record.get("business_id").toString()),
        UUID.fromString(record.get("sale_id").toString()),
        UUID.fromString(record.get("payment_id").toString()),
        amount,
        Instant.ofEpochMilli((long) record.get("occurred_at")),
        tenderType,
        channel);
  }

  private static Schema parse() {
    try (InputStream in = SaleVoidedSchema.class.getClassLoader().getResourceAsStream(RESOURCE)) {
      if (in == null) {
        throw new IllegalStateException("Avro schema not found on classpath: " + RESOURCE);
      }
      return new Schema.Parser().parse(in);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to load Avro schema " + RESOURCE, e);
    }
  }
}
