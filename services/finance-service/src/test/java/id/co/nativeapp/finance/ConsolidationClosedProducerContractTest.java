package id.co.nativeapp.finance;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.events.AvroSerde;
import id.co.nativeapp.finance.consolidation.ConsolidationClosedSchema;
import java.util.UUID;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericRecord;
import org.junit.jupiter.api.Test;

/**
 * The {@code ConsolidationClosed} PRODUCER contract test (P3d SEAM 4a — THE PRODUCERS; no Spring
 * context needed).
 *
 * <p>finance now OWNS the {@code ConsolidationClosed} producer schema (the SOURCE OF TRUTH, {@code
 * src/main/resources/avro/ConsolidationClosed.avsc}). This proves (the rule-7 triad):
 *
 * <ul>
 *   <li>it parses from the classpath with the expected shape (full name + fields, the nullable
 *       {@code group_id} union);
 *   <li>{@link ConsolidationClosedSchema#toRecord} builds a record that round-trips through {@code
 *       libs/events} {@link AvroSerde} for BOTH close kinds (group_id present + null); and
 *   <li>the producer schema stays BACKWARD-COMPATIBLE with the notification-service CONSUMER copy
 *       (embedded verbatim) — so the existing notification {@code ConsolidationClosedContractTest}
 *       stays satisfied (a notification reader on its copy can read bytes finance writes), and a
 *       deliberate break (a new required field without a default) is rejected.
 * </ul>
 */
class ConsolidationClosedProducerContractTest {

  /**
   * notification-service's CONSUMER copy of {@code ConsolidationClosed}, copied verbatim from
   * {@code services/notification-service/src/main/resources/avro/ConsolidationClosed.avsc} (the
   * three fields; the nullable {@code group_id} union with {@code default null}). The producer must
   * stay backward-compatible with it (rule 7).
   */
  private static final String NOTIFICATION_CONSUMER_SCHEMA_JSON =
      """
      {
        "type": "record",
        "name": "ConsolidationClosed",
        "namespace": "id.co.nativeapp.events.finance",
        "fields": [
          {"name": "company_id", "type": "string"},
          {"name": "group_id", "type": ["null", "string"], "default": null},
          {"name": "period", "type": "string"}
        ]
      }
      """;

  @Test
  void producerAvscParsesFromClasspath() {
    Schema schema = ConsolidationClosedSchema.schema();
    assertThat(schema.getFullName())
        .isEqualTo("id.co.nativeapp.events.finance.ConsolidationClosed");
    assertThat(schema.getField("company_id")).isNotNull();
    assertThat(schema.getField("group_id")).isNotNull();
    assertThat(schema.getField("group_id").schema().getType()).isEqualTo(Schema.Type.UNION);
    assertThat(schema.getField("period")).isNotNull();
  }

  @Test
  void aWithinCompanyCloseRecordRoundTripsWithNullGroupId() {
    UUID company = UUID.randomUUID();
    GenericRecord record = ConsolidationClosedSchema.toRecord(company, null, "2026-06");

    byte[] bytes = AvroSerde.serialize(record);
    GenericRecord decoded = AvroSerde.deserialize(bytes, ConsolidationClosedSchema.schema());

    assertThat(decoded.get("company_id").toString()).isEqualTo(company.toString());
    assertThat(decoded.get("group_id")).isNull();
    assertThat(decoded.get("period").toString()).isEqualTo("2026-06");
  }

  @Test
  void aGroupCloseRecordRoundTripsWithThePresentGroupId() {
    UUID lead = UUID.randomUUID();
    UUID group = UUID.randomUUID();
    GenericRecord record = ConsolidationClosedSchema.toRecord(lead, group, "2026-06");

    byte[] bytes = AvroSerde.serialize(record);
    GenericRecord decoded = AvroSerde.deserialize(bytes, ConsolidationClosedSchema.schema());

    assertThat(decoded.get("company_id").toString()).isEqualTo(lead.toString());
    assertThat(decoded.get("group_id").toString()).isEqualTo(group.toString());
    assertThat(decoded.get("period").toString()).isEqualTo("2026-06");
  }

  @Test
  void theNotificationConsumerCopyCanReadBytesTheProducerWrites() {
    Schema producer = ConsolidationClosedSchema.schema();
    Schema notificationConsumer = new Schema.Parser().parse(NOTIFICATION_CONSUMER_SCHEMA_JSON);

    // The notification consumer (reader) must read data the finance producer (writer) writes.
    assertThat(AvroSerde.isBackwardCompatible(producer, notificationConsumer)).isTrue();

    UUID lead = UUID.randomUUID();
    UUID group = UUID.randomUUID();
    byte[] wire = AvroSerde.serialize(ConsolidationClosedSchema.toRecord(lead, group, "2026-05"));
    GenericRecord decoded = AvroSerde.deserialize(wire, producer, notificationConsumer);
    assertThat(decoded.get("group_id").toString()).isEqualTo(group.toString());
    assertThat(decoded.get("period").toString()).isEqualTo("2026-05");
  }

  @Test
  void addingARequiredFieldWithoutDefaultBreaksBackwardCompatibility() {
    Schema producer = ConsolidationClosedSchema.schema();
    Schema incompatible =
        new Schema.Parser()
            .parse(
                """
                {
                  "type": "record",
                  "name": "ConsolidationClosed",
                  "namespace": "id.co.nativeapp.events.finance",
                  "fields": [
                    {"name": "company_id", "type": "string"},
                    {"name": "group_id", "type": ["null", "string"], "default": null},
                    {"name": "period", "type": "string"},
                    {"name": "closed_by", "type": "string"}
                  ]
                }
                """);
    assertThat(AvroSerde.isBackwardCompatible(producer, incompatible)).isFalse();
  }
}
