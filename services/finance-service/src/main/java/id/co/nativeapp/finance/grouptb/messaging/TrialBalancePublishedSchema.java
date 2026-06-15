package id.co.nativeapp.finance.grouptb.messaging;

import id.co.nativeapp.events.AvroSerde;
import id.co.nativeapp.finance.grouptb.messaging.TrialBalancePublishedEvent.TrialBalanceLine;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;

/**
 * Loads finance-service's {@code TrialBalancePublished} Avro schema from the classpath ({@code
 * avro/TrialBalancePublished.avsc}) — the SINGLE SOURCE OF TRUTH for BOTH the SEAM-2 consumer (it
 * decodes raw Avro bytes into a {@link TrialBalancePublishedEvent}) and the SEAM-4a PRODUCER (a
 * within-company close builds a {@link GenericRecord} via {@link #toRecord} and writes it to the
 * outbox). No Avro code-generation plugin, and no Confluent / Schema Registry serde, exactly like
 * the {@code GroupDefined} / {@code SaleRecorded} paths. {@code TrialBalancePublishedContractTest}
 * asserts the schema stays backward-compatible (rule 7); because producer and consumer read the
 * very same {@code .avsc}, a produced event is decode-compatible with the consumer by construction.
 */
public final class TrialBalancePublishedSchema {

  /** Classpath location of the {@code .avsc} (producer source of truth + consumer copy). */
  public static final String RESOURCE = "avro/TrialBalancePublished.avsc";

  /** The Kafka topic / outbox {@code event_type} (one topic per event type). */
  public static final String TOPIC = "TrialBalancePublished";

  /** The event name as it appears in the outbox {@code event_type} column. */
  public static final String EVENT_TYPE = "TrialBalancePublished";

  /**
   * The producing aggregate kind (outbox {@code aggregate_type}). The partition key (the outbox
   * {@code aggregate_id}) is the {@code group_id}, so a group's member trial balances are ordered
   * after its {@code GroupDefined}, exactly as the catalog requires.
   */
  public static final String AGGREGATE_TYPE = "consolidation_group";

  private static final Schema SCHEMA = parse();

  private static final Schema LINE_SCHEMA = SCHEMA.getField("lines").schema().getElementType();

  private TrialBalancePublishedSchema() {
    // static holder
  }

  /** The parsed schema for {@code TrialBalancePublished} (producer source of truth + consumer). */
  public static Schema schema() {
    return SCHEMA;
  }

  /**
   * Builds a {@code TrialBalancePublished} {@link GenericRecord} for a within-company close (P3d
   * SEAM 4a — the PRODUCER side). The lines are the company's balanced trial balance (its
   * REVENUE/EXPENSE lines by GL account PLUS the balancing retained-earnings EQUITY closing line);
   * the same {@link TrialBalanceLine} record the consumer decodes is reused as the line carrier.
   *
   * @param memberCompanyId the closing company (the MEMBER dimension on the wire — {@code
   *     company_id})
   * @param groupId the consolidation group this event is emitted for (the partition key + RLS
   *     dimension)
   * @param period the accounting period {@code YYYY-MM}
   * @param baseCurrency the company's base (functional) ISO-4217 currency
   * @param reconciled whether the company's trial balance reconciled (always true here — the
   *     balancing equity line makes the signed double-entry residual zero)
   * @param usesIllustrativeRules sticky-OR from any illustrative-derived posting in the period
   * @param lines the balanced trial-balance lines
   */
  @SuppressWarnings("checkstyle:ParameterNumber")
  public static GenericRecord toRecord(
      UUID memberCompanyId,
      UUID groupId,
      String period,
      String baseCurrency,
      boolean reconciled,
      boolean usesIllustrativeRules,
      List<TrialBalanceLine> lines) {
    GenericRecord record = new GenericData.Record(SCHEMA);
    record.put("company_id", memberCompanyId.toString());
    record.put("group_id", groupId.toString());
    record.put("period", period);
    record.put("base_currency", baseCurrency);
    record.put("reconciled", reconciled);
    record.put("uses_illustrative_rules", usesIllustrativeRules);
    List<GenericRecord> lineRecords = new ArrayList<>(lines.size());
    for (TrialBalanceLine line : lines) {
      lineRecords.add(toLineRecord(line));
    }
    record.put("lines", lineRecords);
    return record;
  }

  private static GenericRecord toLineRecord(TrialBalanceLine line) {
    GenericRecord record = new GenericData.Record(LINE_SCHEMA);
    record.put("gl_account_code", line.glAccountCode());
    record.put("account_type", line.accountType());
    record.put("posting_type", line.postingType());
    record.put("amount_minor", line.amountMinor());
    record.put("currency", line.currency());
    record.put(
        "related_party_counterparty_id",
        line.relatedPartyCounterpartyId() == null
            ? null
            : line.relatedPartyCounterpartyId().toString());
    record.put("intercompany_ref", line.intercompanyRef());
    return record;
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
