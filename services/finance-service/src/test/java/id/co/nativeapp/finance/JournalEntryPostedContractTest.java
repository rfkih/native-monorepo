package id.co.nativeapp.finance;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.events.AvroSerde;
import id.co.nativeapp.finance.gl.domain.JournalEntry;
import id.co.nativeapp.finance.gl.domain.JournalLine;
import id.co.nativeapp.finance.gl.messaging.JournalEntryPostedSchema;
import id.co.nativeapp.money.Money;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericRecord;
import org.junit.jupiter.api.Test;

/**
 * The {@code JournalEntryPosted} PRODUCER contract test (ADR 0071 P1, no Spring context needed).
 *
 * <p>The schema lives in {@code libs/contracts} ({@code avro/JournalEntryPosted.avsc}) — the single
 * source of truth the future analytics consumer will read too. This proves the rule-7 triad: the
 * {@code .avsc} parses from the classpath with the expected shape, a {@link GenericRecord} built by
 * {@link JournalEntryPostedSchema#toRecord} from a REAL domain entry round-trips through {@code
 * libs/events} {@link AvroSerde} (the exact bytes the outbox carries), and the classpath schema
 * stays BACKWARD-COMPATIBLE with the registered producer schema (embedded verbatim from
 * docs/EVENT-CATALOG.md), while a new required field with no default is rejected.
 */
class JournalEntryPostedContractTest {

  /** The registered producer schema, copied verbatim from the catalog. */
  private static final String PRODUCER_SCHEMA_JSON =
      """
      {
        "type": "record",
        "name": "JournalEntryPosted",
        "namespace": "id.co.nativeapp.events.finance",
        "fields": [
          {"name": "journal_entry_id", "type": "string"},
          {"name": "company_id", "type": "string"},
          {"name": "business_id", "type": ["null", "string"], "default": null},
          {"name": "period", "type": "string"},
          {"name": "occurred_at", "type": {"type": "long", "logicalType": "timestamp-millis"}},
          {"name": "currency", "type": "string"},
          {"name": "posting_role", "type": "string"},
          {"name": "source_event_id", "type": "string"},
          {
            "name": "lines",
            "type": {
              "type": "array",
              "items": {
                "type": "record",
                "name": "JournalEntryLine",
                "fields": [
                  {"name": "line_no", "type": "int"},
                  {"name": "account_code", "type": "string"},
                  {"name": "debit_minor", "type": "long"},
                  {"name": "credit_minor", "type": "long"},
                  {"name": "currency", "type": "string"}
                ]
              }
            }
          }
        ]
      }
      """;

  @Test
  void avscParsesFromClasspathWithExpectedShape() {
    Schema schema = JournalEntryPostedSchema.schema();
    assertThat(schema.getFullName()).isEqualTo("id.co.nativeapp.events.finance.JournalEntryPosted");
    assertThat(schema.getField("journal_entry_id")).isNotNull();
    assertThat(schema.getField("company_id")).isNotNull();
    assertThat(schema.getField("business_id")).isNotNull();
    assertThat(schema.getField("period")).isNotNull();
    assertThat(schema.getField("occurred_at")).isNotNull();
    assertThat(schema.getField("posting_role")).isNotNull();
    assertThat(schema.getField("source_event_id")).isNotNull();
    assertThat(schema.getField("lines")).isNotNull();
    assertThat(schema.getField("lines").schema().getType()).isEqualTo(Schema.Type.ARRAY);
  }

  @Test
  void toRecordFromADomainEntryRoundTripsThroughAvroSerde() {
    UUID entryId = UUID.randomUUID();
    UUID sourceEventId = UUID.randomUUID();
    String tenant = "11111111-1111-1111-1111-111111111111";
    Instant occurredAt = Instant.parse("2026-09-02T03:00:00Z");
    JournalEntry entry =
        JournalEntry.balanced(
            entryId,
            "2026-09",
            occurredAt,
            "contract-test posting",
            "IDR",
            sourceEventId,
            false,
            List.of(
                JournalLine.debit(entryId, 1, "1100", Money.ofMinor(150_000L, "IDR")),
                JournalLine.credit(entryId, 2, "4000", Money.ofMinor(150_000L, "IDR"))));

    byte[] bytes = AvroSerde.serialize(JournalEntryPostedSchema.toRecord(entry, tenant));
    GenericRecord decoded = AvroSerde.deserialize(bytes, JournalEntryPostedSchema.schema());

    assertThat(decoded.get("journal_entry_id").toString()).isEqualTo(entryId.toString());
    assertThat(decoded.get("company_id").toString()).isEqualTo(tenant);
    assertThat(decoded.get("business_id")).isNull();
    assertThat(decoded.get("period").toString()).isEqualTo("2026-09");
    assertThat(decoded.get("occurred_at")).isEqualTo(occurredAt.toEpochMilli());
    assertThat(decoded.get("currency").toString()).isEqualTo("IDR");
    assertThat(decoded.get("posting_role").toString()).isEqualTo("PRIMARY");
    assertThat(decoded.get("source_event_id").toString()).isEqualTo(sourceEventId.toString());
    @SuppressWarnings("unchecked")
    List<GenericRecord> lines = (List<GenericRecord>) decoded.get("lines");
    assertThat(lines).hasSize(2);
    assertThat(lines.get(0).get("line_no")).isEqualTo(1);
    assertThat(lines.get(0).get("account_code").toString()).isEqualTo("1100");
    assertThat(lines.get(0).get("debit_minor")).isEqualTo(150_000L);
    assertThat(lines.get(0).get("credit_minor")).isEqualTo(0L);
    assertThat(lines.get(1).get("credit_minor")).isEqualTo(150_000L);
    assertThat(lines.get(1).get("currency").toString()).isEqualTo("IDR");
  }

  @Test
  void aReversalEntryCarriesItsPostingRole() {
    UUID entryId = UUID.randomUUID();
    JournalEntry contra =
        JournalEntry.reversal(
            entryId,
            "2026-09",
            Instant.parse("2026-09-02T04:00:00Z"),
            "supersession contra",
            "IDR",
            UUID.randomUUID(),
            false,
            List.of(
                JournalLine.debit(entryId, 1, "4000", Money.ofMinor(150_000L, "IDR")),
                JournalLine.credit(entryId, 2, "1100", Money.ofMinor(150_000L, "IDR"))));

    GenericRecord record =
        JournalEntryPostedSchema.toRecord(contra, "11111111-1111-1111-1111-111111111111");

    assertThat(record.get("posting_role").toString()).isEqualTo("REVERSAL");
  }

  @Test
  void classpathSchemaIsBackwardCompatibleWithTheRegisteredProducerSchema() {
    Schema producer = new Schema.Parser().parse(PRODUCER_SCHEMA_JSON);
    Schema consumer = JournalEntryPostedSchema.schema();
    assertThat(AvroSerde.isBackwardCompatible(producer, consumer)).isTrue();
  }

  @Test
  void addingARequiredFieldWithoutDefaultBreaksBackwardCompatibility() {
    Schema producer = new Schema.Parser().parse(PRODUCER_SCHEMA_JSON);
    Schema incompatible =
        new Schema.Parser()
            .parse(
                """
                {
                  "type": "record",
                  "name": "JournalEntryPosted",
                  "namespace": "id.co.nativeapp.events.finance",
                  "fields": [
                    {"name": "journal_entry_id", "type": "string"},
                    {"name": "company_id", "type": "string"},
                    {"name": "business_id", "type": ["null", "string"], "default": null},
                    {"name": "period", "type": "string"},
                    {"name": "occurred_at", "type": {"type": "long", "logicalType": "timestamp-millis"}},
                    {"name": "currency", "type": "string"},
                    {"name": "posting_role", "type": "string"},
                    {"name": "source_event_id", "type": "string"},
                    {"name": "approver_id", "type": "string"},
                    {
                      "name": "lines",
                      "type": {"type": "array", "items": {
                        "type": "record", "name": "JournalEntryLine", "fields": [
                          {"name": "line_no", "type": "int"},
                          {"name": "account_code", "type": "string"},
                          {"name": "debit_minor", "type": "long"},
                          {"name": "credit_minor", "type": "long"},
                          {"name": "currency", "type": "string"}
                        ]}}
                    }
                  ]
                }
                """);
    assertThat(AvroSerde.isBackwardCompatible(producer, incompatible)).isFalse();
  }
}
