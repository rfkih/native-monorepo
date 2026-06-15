package id.co.nativeapp.finance.grouptb;

import id.co.nativeapp.events.AvroSerde;
import id.co.nativeapp.finance.grouptb.TrialBalancePublishedEvent.TrialBalanceLine;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericRecord;

/**
 * Loads finance-service's CONSUMER copy of the {@code TrialBalancePublished} Avro schema from the
 * classpath ({@code avro/TrialBalancePublished.avsc}) and decodes raw Avro bytes into a {@link
 * TrialBalancePublishedEvent} — no Avro code-generation plugin, and no Confluent / Schema Registry
 * serde, exactly like the {@code GroupDefined} / {@code SaleRecorded} consumer paths. {@code
 * TrialBalancePublishedContractTest} asserts this copy stays backward-compatible with the
 * producer's schema (rule 7).
 */
public final class TrialBalancePublishedSchema {

  /** Classpath location of the consumer-copy {@code .avsc}. */
  public static final String RESOURCE = "avro/TrialBalancePublished.avsc";

  /** The Kafka topic the producer outbox event is routed to (one topic per event type). */
  public static final String TOPIC = "TrialBalancePublished";

  private static final Schema SCHEMA = parse();

  private TrialBalancePublishedSchema() {
    // static holder
  }

  /** The parsed reader schema for {@code TrialBalancePublished} (finance's consumer copy). */
  public static Schema schema() {
    return SCHEMA;
  }

  /**
   * Decodes raw Avro bytes (the producer outbox payload, shipped by Debezium) into a {@link
   * TrialBalancePublishedEvent}, using this consumer copy of the schema.
   *
   * @param eventId the event's UUID (the outbox row / Debezium message id) — the idempotency key
   * @param payload the raw Avro bytes off the topic
   */
  public static TrialBalancePublishedEvent decode(UUID eventId, byte[] payload) {
    GenericRecord record = AvroSerde.deserialize(payload, SCHEMA);
    return new TrialBalancePublishedEvent(
        eventId,
        UUID.fromString(record.get("company_id").toString()),
        UUID.fromString(record.get("group_id").toString()),
        record.get("period").toString(),
        record.get("base_currency").toString(),
        (Boolean) record.get("reconciled"),
        (Boolean) record.get("uses_illustrative_rules"),
        decodeLines(record));
  }

  @SuppressWarnings("unchecked")
  private static List<TrialBalanceLine> decodeLines(GenericRecord record) {
    Object raw = record.get("lines");
    if (!(raw instanceof Iterable<?> iterable)) {
      throw new IllegalArgumentException("TrialBalancePublished 'lines' is not an array");
    }
    List<TrialBalanceLine> lines = new ArrayList<>();
    for (Object element : (Iterable<GenericRecord>) iterable) {
      GenericRecord line = (GenericRecord) element;
      lines.add(
          new TrialBalanceLine(
              line.get("gl_account_code").toString(),
              line.get("account_type").toString(),
              line.get("posting_type").toString(),
              (Long) line.get("amount_minor"),
              line.get("currency").toString(),
              optionalUuid(line.get("related_party_counterparty_id")),
              optionalString(line.get("intercompany_ref"))));
    }
    return List.copyOf(lines);
  }

  private static UUID optionalUuid(Object value) {
    return value == null ? null : UUID.fromString(value.toString());
  }

  private static String optionalString(Object value) {
    return value == null ? null : value.toString();
  }

  private static Schema parse() {
    try (InputStream in =
        TrialBalancePublishedSchema.class.getClassLoader().getResourceAsStream(RESOURCE)) {
      if (in == null) {
        throw new IllegalStateException("Avro schema not found on classpath: " + RESOURCE);
      }
      return new Schema.Parser().parse(in);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to load Avro schema " + RESOURCE, e);
    }
  }
}
