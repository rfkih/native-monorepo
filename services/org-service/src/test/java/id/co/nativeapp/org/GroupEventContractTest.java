package id.co.nativeapp.org;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.events.AvroSerde;
import id.co.nativeapp.org.group.GroupDefinedSchema;
import id.co.nativeapp.org.group.GroupMembershipChangedSchema;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.junit.jupiter.api.Test;

/**
 * The {@code GroupDefined} / {@code GroupMembershipChanged} contract-test triad (P3d SEAM 1, no
 * Spring context). For each event it proves the {@code .avsc} on the classpath parses with the
 * expected key fields, that a {@link GenericRecord} round-trips through {@code libs/events} {@link
 * AvroSerde}, and that the backward-compatibility gate accepts the schema against itself and an
 * added-optional-field variant while rejecting an incompatible change (a new required field with no
 * default). CLAUDE.md rule 7: event schema changes are backward-compatible ONLY.
 */
class GroupEventContractTest {

  private static final String GROUP = "11111111-1111-1111-1111-111111111111";
  private static final String LEAD = "22222222-2222-2222-2222-222222222222";
  private static final String MEMBER = "33333333-3333-3333-3333-333333333333";

  // ---- GroupDefined ---------------------------------------------------------------------------

  @Test
  void groupDefinedAvscParsesFromClasspathWithTheKeyFields() {
    Schema schema = GroupDefinedSchema.schema();
    assertThat(schema.getFullName()).isEqualTo("id.co.nativeapp.events.org.GroupDefined");
    assertThat(schema.getField("group_id")).isNotNull();
    assertThat(schema.getField("lead_company_id")).isNotNull();
    assertThat(schema.getField("reporting_currency")).isNotNull();
    assertThat(schema.getField("name")).isNotNull();
  }

  @Test
  void groupDefinedRoundTripsThroughAvroSerde() {
    Schema schema = GroupDefinedSchema.schema();
    GenericRecord record = new GenericData.Record(schema);
    record.put("group_id", GROUP);
    record.put("lead_company_id", LEAD);
    record.put("reporting_currency", "USD");
    record.put("name", "APAC Group");

    GenericRecord decoded = AvroSerde.deserialize(AvroSerde.serialize(record), schema);
    assertThat(decoded.get("lead_company_id").toString()).isEqualTo(LEAD);
    assertThat(decoded.get("reporting_currency").toString()).isEqualTo("USD");
    assertThat(decoded.get("name").toString()).isEqualTo("APAC Group");
  }

  @Test
  void groupDefinedIsBackwardCompatibleWithItselfAndAnAddedOptionalField() {
    Schema v1 = GroupDefinedSchema.schema();
    assertThat(AvroSerde.isBackwardCompatible(v1, v1)).isTrue();

    Schema v2 =
        new Schema.Parser()
            .parse(
                """
                {
                  "type": "record",
                  "name": "GroupDefined",
                  "namespace": "id.co.nativeapp.events.org",
                  "fields": [
                    {"name": "group_id", "type": "string"},
                    {"name": "lead_company_id", "type": "string"},
                    {"name": "reporting_currency", "type": "string"},
                    {"name": "name", "type": "string"},
                    {"name": "description", "type": ["null", "string"], "default": null}
                  ]
                }
                """);
    assertThat(AvroSerde.isBackwardCompatible(v1, v2)).isTrue();
  }

  @Test
  void groupDefinedAddingARequiredFieldWithoutDefaultBreaksCompatibility() {
    Schema v1 = GroupDefinedSchema.schema();
    Schema incompatible =
        new Schema.Parser()
            .parse(
                """
                {
                  "type": "record",
                  "name": "GroupDefined",
                  "namespace": "id.co.nativeapp.events.org",
                  "fields": [
                    {"name": "group_id", "type": "string"},
                    {"name": "lead_company_id", "type": "string"},
                    {"name": "reporting_currency", "type": "string"},
                    {"name": "name", "type": "string"},
                    {"name": "owner_id", "type": "string"}
                  ]
                }
                """);
    assertThat(AvroSerde.isBackwardCompatible(v1, incompatible)).isFalse();
  }

  // ---- GroupMembershipChanged -----------------------------------------------------------------

  @Test
  void membershipChangedAvscParsesFromClasspathWithTheKeyFields() {
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
  void membershipChangedRoundTripsThroughAvroSerde() {
    Schema schema = GroupMembershipChangedSchema.schema();
    GenericRecord record = new GenericData.Record(schema);
    record.put("group_id", GROUP);
    record.put("member_company_id", MEMBER);
    record.put("change_kind", "ADDED");
    record.put("effective_from", 20_000); // epoch day
    record.put("effective_to", 2_932_896); // 9999-12-31 epoch day

    GenericRecord decoded = AvroSerde.deserialize(AvroSerde.serialize(record), schema);
    assertThat(decoded.get("change_kind").toString()).isEqualTo("ADDED");
    assertThat(decoded.get("effective_from")).isEqualTo(20_000);
    assertThat(decoded.get("effective_to")).isEqualTo(2_932_896);
  }

  @Test
  void membershipChangedIsBackwardCompatibleWithItselfAndAnAddedOptionalField() {
    Schema v1 = GroupMembershipChangedSchema.schema();
    assertThat(AvroSerde.isBackwardCompatible(v1, v1)).isTrue();

    Schema v2 =
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
                    {"name": "reason", "type": ["null", "string"], "default": null}
                  ]
                }
                """);
    assertThat(AvroSerde.isBackwardCompatible(v1, v2)).isTrue();
  }

  @Test
  void membershipChangedAddingARequiredFieldWithoutDefaultBreaksCompatibility() {
    Schema v1 = GroupMembershipChangedSchema.schema();
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
    assertThat(AvroSerde.isBackwardCompatible(v1, incompatible)).isFalse();
  }
}
