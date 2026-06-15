package id.co.nativeapp.finance;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.events.AvroSerde;
import id.co.nativeapp.finance.group.GroupMembershipChangedSchema;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.junit.jupiter.api.Test;

/**
 * The {@code GroupMembershipChanged} CONSUMER contract test (P3d SEAM 1, no Spring context). Proves
 * finance's consumer copy ({@code src/main/resources/avro/GroupMembershipChanged.avsc}) parses,
 * round-trips a {@link GenericRecord} through {@code libs/events} {@link AvroSerde}, stays
 * BACKWARD-COMPATIBLE with the producer schema, and rejects a new required field without a default
 * (the rule-7 triad). Effective dates are Avro {@code date} logical-type ints (epoch day).
 */
class GroupMembershipChangedContractTest {

  private static final String PRODUCER_SCHEMA_JSON =
      """
      {
        "type": "record",
        "name": "GroupMembershipChanged",
        "namespace": "id.co.nativeapp.events.org",
        "fields": [
          {"name": "group_id", "type": "string"},
          {"name": "member_company_id", "type": "string"},
          {"name": "change_kind", "type": "string"},
          {"name": "effective_from", "type": {"type": "int", "logicalType": "date"}},
          {"name": "effective_to", "type": {"type": "int", "logicalType": "date"}}
        ]
      }
      """;

  @Test
  void consumerAvscParsesFromClasspath() {
    Schema schema = GroupMembershipChangedSchema.schema();
    assertThat(schema.getFullName()).isEqualTo("id.co.nativeapp.events.org.GroupMembershipChanged");
    assertThat(schema.getField("group_id")).isNotNull();
    assertThat(schema.getField("member_company_id")).isNotNull();
    assertThat(schema.getField("change_kind")).isNotNull();
    assertThat(schema.getField("effective_from").schema().getLogicalType().getName())
        .isEqualTo("date");
    assertThat(schema.getField("effective_to").schema().getLogicalType().getName())
        .isEqualTo("date");
  }

  @Test
  void genericRecordRoundTripsThroughAvroSerde() {
    Schema schema = GroupMembershipChangedSchema.schema();
    GenericRecord record = new GenericData.Record(schema);
    record.put("group_id", "11111111-1111-1111-1111-111111111111");
    record.put("member_company_id", "33333333-3333-3333-3333-333333333333");
    record.put("change_kind", "REMOVED");
    record.put("effective_from", 19_000);
    record.put("effective_to", 20_500);

    byte[] bytes = AvroSerde.serialize(record);
    GenericRecord decoded = AvroSerde.deserialize(bytes, schema);
    assertThat(decoded.get("change_kind").toString()).isEqualTo("REMOVED");
    assertThat(decoded.get("effective_from")).isEqualTo(19_000);
    assertThat(decoded.get("effective_to")).isEqualTo(20_500);
  }

  @Test
  void consumerCopyIsBackwardCompatibleWithTheProducerSchema() {
    Schema producer = new Schema.Parser().parse(PRODUCER_SCHEMA_JSON);
    Schema consumer = GroupMembershipChangedSchema.schema();
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
                  "name": "GroupMembershipChanged",
                  "namespace": "id.co.nativeapp.events.org",
                  "fields": [
                    {"name": "group_id", "type": "string"},
                    {"name": "member_company_id", "type": "string"},
                    {"name": "change_kind", "type": "string"},
                    {"name": "effective_from", "type": {"type": "int", "logicalType": "date"}},
                    {"name": "effective_to", "type": {"type": "int", "logicalType": "date"}},
                    {"name": "approver_id", "type": "string"}
                  ]
                }
                """);
    assertThat(AvroSerde.isBackwardCompatible(producer, incompatible)).isFalse();
  }
}
