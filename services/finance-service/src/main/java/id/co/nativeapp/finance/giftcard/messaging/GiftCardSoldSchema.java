package id.co.nativeapp.finance.giftcard.messaging;

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
 * Loads finance-service's consumer view of the {@code GiftCardSold} Avro schema from the classpath
 * ({@code avro/GiftCardSold.avsc}, sourced from {@code libs/contracts} — ADR 0003) and decodes raw
 * Avro bytes into a {@link GiftCardSoldEvent} — no Avro code-generation plugin, and no Confluent /
 * Schema Registry serde. finance owns its consumer decode path (full name {@code
 * id.co.nativeapp.events.restaurant.GiftCardSold}, matching docs/EVENT-CATALOG.md — the
 * SaleRecorded-family namespace convention for a multi-vertical producer).
 *
 * <p>The wire bytes are the producer outbox payload (raw Avro), so we deserialize with {@code
 * libs/events} {@link AvroSerde} against this parsed schema — consistent with how the outbox stores
 * events and mirroring {@code SaleVoidedSchema}/{@code SaleRefundedSchema}.
 */
public final class GiftCardSoldSchema {

  /** Classpath location of the consumer-copy {@code .avsc} (from libs/contracts). */
  public static final String RESOURCE = "avro/GiftCardSold.avsc";

  /** The Kafka topic the producer outbox event is routed to (one topic per event type). */
  public static final String TOPIC = "GiftCardSold";

  private static final Schema SCHEMA = parse();

  private GiftCardSoldSchema() {
    // static holder
  }

  /** The parsed reader schema for {@code GiftCardSold} (finance's consumer view). */
  public static Schema schema() {
    return SCHEMA;
  }

  /**
   * Decodes raw Avro bytes (the producer outbox payload, shipped by Debezium) into a {@link
   * GiftCardSoldEvent}, using this consumer view of the schema as both writer and reader schema.
   *
   * <p>The money is reconstructed as {@code libs/money} {@link Money} from the integer {@code
   * amount_minor} + ISO-4217 {@code currency} (never a float). The optional {@code tender_type}
   * (nullable string) is decoded — {@code null} for legacy/unspecified.
   *
   * @param eventId the event's UUID (the outbox row / Debezium message id) — the idempotency key
   * @param payload the raw Avro bytes off the topic
   */
  public static GiftCardSoldEvent decode(UUID eventId, byte[] payload) {
    GenericRecord record = AvroSerde.deserialize(payload, SCHEMA);
    Money amount =
        Money.ofMinor((long) record.get("amount_minor"), record.get("currency").toString());
    Object tenderTypeRaw = record.get("tender_type");
    String tenderType = (tenderTypeRaw != null) ? tenderTypeRaw.toString() : null;
    // gift_card_sale_id is the catalog-defined dedupe key (not the Kafka offset, and not
    // necessarily the same object identity as the header-derived eventId param, though the
    // producer mints them equal by construction) — sourced from the payload, exactly like
    // SaleVoidedSchema#decode reads void_id and SaleRefundedSchema#decode reads refund_id.
    return new GiftCardSoldEvent(
        UUID.fromString(record.get("gift_card_sale_id").toString()),
        UUID.fromString(record.get("gift_card_id").toString()),
        record.get("company_id").toString(),
        UUID.fromString(record.get("business_id").toString()),
        amount,
        Instant.ofEpochMilli((long) record.get("occurred_at")),
        tenderType);
  }

  private static Schema parse() {
    try (InputStream in = GiftCardSoldSchema.class.getClassLoader().getResourceAsStream(RESOURCE)) {
      if (in == null) {
        throw new IllegalStateException("Avro schema not found on classpath: " + RESOURCE);
      }
      return new Schema.Parser().parse(in);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to load Avro schema " + RESOURCE, e);
    }
  }
}
