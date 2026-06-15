package id.co.nativeapp.org;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.events.AvroSerde;
import id.co.nativeapp.org.company.messaging.CompanyCreatedSchema;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.junit.jupiter.api.Test;

/**
 * Acceptance (d) — the CompanyCreated contract test (no Spring context needed).
 *
 * <p>Proves the {@code CompanyCreated.avsc} on the classpath parses, that a {@link GenericRecord}
 * built from it round-trips through {@code libs/events} {@link AvroSerde}, and that the
 * backward-compatibility gate accepts the schema against itself and against an added-optional-field
 * variant, while rejecting an incompatible change (a new required field with no default). CLAUDE.md
 * rule 7: event schema changes are backward-compatible ONLY.
 */
class CompanyCreatedContractTest {

  @Test
  void avscParsesFromClasspath() {
    Schema schema = CompanyCreatedSchema.schema();
    assertThat(schema.getFullName()).isEqualTo("id.co.nativeapp.events.org.CompanyCreated");
    assertThat(schema.getField("company_id")).isNotNull();
    assertThat(schema.getField("legal_employer_id")).isNotNull();
    assertThat(schema.getField("base_currency")).isNotNull();
    assertThat(schema.getField("default_language")).isNotNull();
  }

  @Test
  void genericRecordRoundTripsThroughAvroSerde() {
    Schema schema = CompanyCreatedSchema.schema();
    GenericRecord record = new GenericData.Record(schema);
    record.put("company_id", "11111111-1111-1111-1111-111111111111");
    record.put("legal_employer_id", "11111111-1111-1111-1111-111111111111");
    record.put("base_currency", "IDR");
    record.put("default_language", "id");

    byte[] bytes = AvroSerde.serialize(record);
    GenericRecord decoded = AvroSerde.deserialize(bytes, schema);

    assertThat(decoded.get("company_id").toString())
        .isEqualTo("11111111-1111-1111-1111-111111111111");
    assertThat(decoded.get("legal_employer_id").toString())
        .isEqualTo("11111111-1111-1111-1111-111111111111");
    assertThat(decoded.get("base_currency").toString()).isEqualTo("IDR");
    assertThat(decoded.get("default_language").toString()).isEqualTo("id");
  }

  @Test
  void schemaIsBackwardCompatibleWithItself() {
    Schema schema = CompanyCreatedSchema.schema();
    assertThat(AvroSerde.isBackwardCompatible(schema, schema)).isTrue();
  }

  @Test
  void addingAnOptionalFieldWithDefaultIsBackwardCompatible() {
    Schema v1 = CompanyCreatedSchema.schema();
    Schema v2 =
        new Schema.Parser()
            .parse(
                """
                {
                  "type": "record",
                  "name": "CompanyCreated",
                  "namespace": "id.co.nativeapp.events.org",
                  "fields": [
                    {"name": "company_id", "type": "string"},
                    {"name": "legal_employer_id", "type": "string"},
                    {"name": "base_currency", "type": "string"},
                    {"name": "default_language", "type": "string"},
                    {"name": "display_name", "type": ["null", "string"], "default": null}
                  ]
                }
                """);
    // A v2 reader must still read v1 data (the new optional field defaults to null).
    assertThat(AvroSerde.isBackwardCompatible(v1, v2)).isTrue();
  }

  @Test
  void addingARequiredFieldWithoutDefaultBreaksBackwardCompatibility() {
    Schema v1 = CompanyCreatedSchema.schema();
    Schema incompatible =
        new Schema.Parser()
            .parse(
                """
                {
                  "type": "record",
                  "name": "CompanyCreated",
                  "namespace": "id.co.nativeapp.events.org",
                  "fields": [
                    {"name": "company_id", "type": "string"},
                    {"name": "legal_employer_id", "type": "string"},
                    {"name": "base_currency", "type": "string"},
                    {"name": "default_language", "type": "string"},
                    {"name": "owner_id", "type": "string"}
                  ]
                }
                """);
    // A reader on the new schema cannot read v1 data — no value for owner_id.
    assertThat(AvroSerde.isBackwardCompatible(v1, incompatible)).isFalse();
  }
}
