package id.co.nativeapp.entitlement;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.entitlement.entitlement.CompanyCreatedSchema;
import id.co.nativeapp.events.AvroSerde;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.junit.jupiter.api.Test;

/**
 * Test (e) — the {@code CompanyCreated} CONSUMER contract test (no Spring context needed).
 *
 * <p>entitlement-service owns its own consumer copy of the {@code CompanyCreated} schema ({@code
 * src/main/resources/avro/CompanyCreated.avsc}). This proves that copy:
 *
 * <ul>
 *   <li>parses from the classpath with the expected shape (full name + fields);
 *   <li>round-trips a {@link GenericRecord} through {@code libs/events} {@link AvroSerde}, the
 *       exact path the listener decodes off the wire; and
 *   <li>stays BACKWARD-COMPATIBLE with the PRODUCER schema (org-service's, embedded below verbatim
 *       from docs/EVENT-CATALOG.md) — a consumer reader on its own copy can read bytes a producer
 *       wrote, and a deliberate incompatible break (a new required field with no default) is
 *       rejected. CLAUDE.md rule 7: event schema changes are backward-compatible ONLY.
 * </ul>
 */
class CompanyCreatedContractTest {

  /**
   * The producer's registered {@code CompanyCreated} schema, copied verbatim from
   * docs/EVENT-CATALOG.md (services/org-service/src/main/resources/avro/CompanyCreated.avsc). The
   * contract test pins entitlement-service's consumer copy to it.
   */
  private static final String PRODUCER_SCHEMA_JSON =
      """
      {
        "type": "record",
        "name": "CompanyCreated",
        "namespace": "id.co.nativeapp.events.org",
        "fields": [
          {"name": "company_id", "type": "string"},
          {"name": "legal_employer_id", "type": "string"},
          {"name": "base_currency", "type": "string"},
          {"name": "default_language", "type": "string"}
        ]
      }
      """;

  @Test
  void consumerAvscParsesFromClasspath() {
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
    assertThat(decoded.get("base_currency").toString()).isEqualTo("IDR");
  }

  @Test
  void consumerCopyIsBackwardCompatibleWithTheProducerSchema() {
    Schema producer = new Schema.Parser().parse(PRODUCER_SCHEMA_JSON);
    Schema consumer = CompanyCreatedSchema.schema();
    // A consumer reader on its copy must read bytes a producer wrote.
    assertThat(AvroSerde.isBackwardCompatible(producer, consumer)).isTrue();
  }

  @Test
  void bytesWrittenWithTheProducerSchemaDecodeUnderTheConsumerCopy() {
    Schema producer = new Schema.Parser().parse(PRODUCER_SCHEMA_JSON);
    Schema consumer = CompanyCreatedSchema.schema();

    GenericRecord produced = new GenericData.Record(producer);
    produced.put("company_id", "11111111-1111-1111-1111-111111111111");
    produced.put("legal_employer_id", "11111111-1111-1111-1111-111111111111");
    produced.put("base_currency", "USD");
    produced.put("default_language", "en");

    byte[] wireBytes = AvroSerde.serialize(produced);
    GenericRecord decoded = AvroSerde.deserialize(wireBytes, producer, consumer);

    assertThat(decoded.get("company_id").toString())
        .isEqualTo("11111111-1111-1111-1111-111111111111");
    assertThat(decoded.get("base_currency").toString()).isEqualTo("USD");
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
    assertThat(AvroSerde.isBackwardCompatible(producer, incompatible)).isFalse();
  }
}
