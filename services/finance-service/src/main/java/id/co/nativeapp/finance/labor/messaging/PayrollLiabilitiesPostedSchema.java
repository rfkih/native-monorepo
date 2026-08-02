package id.co.nativeapp.finance.labor.messaging;

import id.co.nativeapp.events.AvroSerde;
import id.co.nativeapp.money.Money;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericRecord;

/**
 * Loads finance-service's CONSUMER copy of the {@code PayrollLiabilitiesPosted} Avro schema from
 * the classpath ({@code avro/PayrollLiabilitiesPosted.avsc}, single-sourced from {@code
 * libs/contracts} — ADR 0003) and decodes raw Avro bytes into a {@link
 * PayrollLiabilitiesPostedEvent} — no Avro code-generation plugin, and no Confluent / Schema
 * Registry serde (ADR 0032, Track P phase P4).
 *
 * <p>The wire bytes are the producer outbox payload (raw Avro), decoded with {@code libs/events}
 * {@link AvroSerde} against this parsed schema — exactly how {@code PayrollPostedSchema} / {@code
 * LaborCostAllocatedSchema} work. Money is reconstructed as {@code libs/money} {@link Money} from
 * integer minor units + the {@code base_currency} ISO-4217 code (never a float, rule 8).
 */
public final class PayrollLiabilitiesPostedSchema {

  /** Classpath location of the consumer-copy {@code .avsc}. */
  public static final String RESOURCE = "avro/PayrollLiabilitiesPosted.avsc";

  /** The Kafka topic the producer outbox event is routed to (one topic per event type). */
  public static final String TOPIC = "PayrollLiabilitiesPosted";

  private static final Schema SCHEMA = parse();

  private PayrollLiabilitiesPostedSchema() {
    // static holder
  }

  /** The parsed reader schema for {@code PayrollLiabilitiesPosted} (finance's consumer copy). */
  public static Schema schema() {
    return SCHEMA;
  }

  /**
   * Decodes raw Avro bytes into a {@link PayrollLiabilitiesPostedEvent}, using this consumer copy
   * of the schema. Each liability bucket becomes a {@link Money} in the run's {@code
   * base_currency}; {@code posted_at} is epoch millis UTC.
   *
   * @param eventId the event's UUID (the outbox row / Debezium message id) — the idempotency key
   * @param payload the raw Avro bytes off the topic
   */
  @SuppressWarnings("unchecked")
  public static PayrollLiabilitiesPostedEvent decode(UUID eventId, byte[] payload) {
    GenericRecord record = AvroSerde.deserialize(payload, SCHEMA);
    String currency = record.get("base_currency").toString();

    List<GenericRecord> bucketRecords = (List<GenericRecord>) record.get("liabilities");
    List<PayrollLiabilitiesPostedEvent.LiabilityBucket> buckets =
        new ArrayList<>(bucketRecords.size());
    for (GenericRecord bucketRecord : bucketRecords) {
      buckets.add(
          new PayrollLiabilitiesPostedEvent.LiabilityBucket(
              bucketRecord.get("liability_role").toString(),
              Money.ofMinor((long) bucketRecord.get("amount_minor"), currency)));
    }

    return new PayrollLiabilitiesPostedEvent(
        eventId,
        record.get("company_id").toString(),
        UUID.fromString(record.get("payroll_run_id").toString()),
        (int) record.get("run_seq"),
        record.get("run_type").toString(),
        record.get("period").toString(),
        currency,
        Money.ofMinor((long) record.get("employer_cost_total_minor"), currency),
        List.copyOf(buckets),
        (boolean) record.get("uses_illustrative_rules"),
        Instant.ofEpochMilli((long) record.get("posted_at")));
  }

  private static Schema parse() {
    try (InputStream in =
        PayrollLiabilitiesPostedSchema.class.getClassLoader().getResourceAsStream(RESOURCE)) {
      if (in == null) {
        throw new IllegalStateException("Avro schema not found on classpath: " + RESOURCE);
      }
      return new Schema.Parser().parse(in);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to load Avro schema " + RESOURCE, e);
    }
  }
}
