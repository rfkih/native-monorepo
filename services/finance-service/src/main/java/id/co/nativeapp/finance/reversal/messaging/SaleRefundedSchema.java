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
 * Loads finance-service's consumer view of the {@code SaleRefunded} Avro schema from the classpath
 * ({@code avro/SaleRefunded.avsc}, sourced from {@code libs/contracts} — ADR 0003) and decodes raw
 * Avro bytes into a {@link SaleRefundedEvent}.
 *
 * <p>The refund amount is reconstructed as {@code libs/money} {@link Money} from the integer {@code
 * refund_amount_minor} + ISO-4217 {@code currency} (never a float). The optional {@code
 * tender_type} (nullable string) is decoded — {@code null} for legacy/no-tender sales.
 *
 * <p>ADR 0006 slice 4: finance posts a proportional contra entry for each {@code SaleRefunded},
 * reversing the original {@code SaleRecorded} posting by the refunded amount.
 */
public final class SaleRefundedSchema {

  /** Classpath location of the consumer-copy {@code .avsc} (from {@code libs/contracts}). */
  public static final String RESOURCE = "avro/SaleRefunded.avsc";

  /** The Kafka topic the producer outbox event is routed to. */
  public static final String TOPIC = "SaleRefunded";

  private static final Schema SCHEMA = parse();

  private SaleRefundedSchema() {
    // static holder
  }

  /** The parsed reader schema for {@code SaleRefunded} (finance's consumer view). */
  public static Schema schema() {
    return SCHEMA;
  }

  /**
   * Decodes raw Avro bytes (the producer outbox payload) into a {@link SaleRefundedEvent}.
   *
   * @param eventId the event's UUID (the outbox row / Debezium message id) — the idempotency key
   * @param payload the raw Avro bytes off the topic
   */
  public static SaleRefundedEvent decode(UUID eventId, byte[] payload) {
    GenericRecord record = AvroSerde.deserialize(payload, SCHEMA);
    Money refundAmount =
        Money.ofMinor((long) record.get("refund_amount_minor"), record.get("currency").toString());
    long totalRefundedMinor = (long) record.get("total_refunded_minor");
    Object tenderTypeRaw = record.get("tender_type");
    String tenderType = (tenderTypeRaw != null) ? tenderTypeRaw.toString() : null;
    // Phase B (ADR 0036): additive nullable channel — the reader schema's default fills null for
    // payloads written before the field existed (rule 7).
    Object channelRaw = record.get("channel");
    String channel = (channelRaw != null) ? channelRaw.toString() : null;
    return new SaleRefundedEvent(
        UUID.fromString(record.get("refund_id").toString()),
        record.get("company_id").toString(),
        UUID.fromString(record.get("business_id").toString()),
        UUID.fromString(record.get("sale_id").toString()),
        UUID.fromString(record.get("payment_id").toString()),
        refundAmount,
        totalRefundedMinor,
        Instant.ofEpochMilli((long) record.get("occurred_at")),
        tenderType,
        channel);
  }

  private static Schema parse() {
    try (InputStream in = SaleRefundedSchema.class.getClassLoader().getResourceAsStream(RESOURCE)) {
      if (in == null) {
        throw new IllegalStateException("Avro schema not found on classpath: " + RESOURCE);
      }
      return new Schema.Parser().parse(in);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to load Avro schema " + RESOURCE, e);
    }
  }
}
