package id.co.nativeapp.employee;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.employee.payroll.messaging.CompanyCreatedEvent;
import id.co.nativeapp.employee.payroll.messaging.CompanyCreatedSchema;
import id.co.nativeapp.events.AvroSerde;
import java.util.UUID;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.junit.jupiter.api.Test;

/**
 * The {@code CompanyCreated} CONSUMER contract test (rule 7) for employee-service's
 * payroll-bootstrap consumer view. employee-service reads org-service's {@code CompanyCreated} (via
 * the shared {@code libs/contracts} schema) to auto-activate the OFFICIAL statutory dataset for a
 * new IDR company. This proves the consumer view:
 *
 * <ul>
 *   <li>parses from the classpath with the expected shape (including {@code base_currency}, the
 *       Indonesia gate this consumer — unlike entitlement-service's — actually reads);
 *   <li>round-trips a {@link GenericRecord} through {@code libs/events} {@link AvroSerde} — the
 *       exact path the listener decodes off the wire — and extracts {@code company_id} + {@code
 *       base_currency}; and
 *   <li>stays BACKWARD-COMPATIBLE with the PRODUCER schema (org-service's, copied verbatim from
 *       docs/EVENT-CATALOG.md) — a consumer reader on the shared copy can read producer bytes.
 * </ul>
 */
class CompanyCreatedConsumerContractTest {

  /**
   * The producer's registered {@code CompanyCreated} schema, copied verbatim from EVENT-CATALOG.md.
   */
  private static final String PRODUCER_JSON =
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
  void consumerCopyParsesWithTheExpectedShape() {
    Schema schema = CompanyCreatedSchema.schema();
    assertThat(schema.getFullName()).isEqualTo("id.co.nativeapp.events.org.CompanyCreated");
    assertThat(schema.getField("company_id")).isNotNull();
    assertThat(schema.getField("base_currency")).isNotNull();
  }

  @Test
  void decodeExtractsCompanyIdAndBaseCurrency() {
    Schema schema = CompanyCreatedSchema.schema();
    GenericRecord record = new GenericData.Record(schema);
    record.put("company_id", "33333333-3333-3333-3333-333333333333");
    record.put("legal_employer_id", "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    record.put("base_currency", "IDR");
    record.put("default_language", "id");

    byte[] bytes = AvroSerde.serialize(record);
    UUID eventId = UUID.randomUUID();
    CompanyCreatedEvent event = CompanyCreatedSchema.decode(eventId, bytes);

    assertThat(event.eventId()).isEqualTo(eventId);
    assertThat(event.companyId()).isEqualTo("33333333-3333-3333-3333-333333333333");
    assertThat(event.baseCurrency()).isEqualTo("IDR");
  }

  @Test
  void consumerCopyIsBackwardCompatibleWithTheProducerSchema() {
    Schema producer = new Schema.Parser().parse(PRODUCER_JSON);
    Schema consumer = CompanyCreatedSchema.schema();
    assertThat(AvroSerde.isBackwardCompatible(producer, consumer)).isTrue();
  }

  @Test
  void producerBytesDecodeUnderTheConsumerCopy() {
    Schema producer = new Schema.Parser().parse(PRODUCER_JSON);
    Schema consumer = CompanyCreatedSchema.schema();
    GenericRecord produced = new GenericData.Record(producer);
    produced.put("company_id", "44444444-4444-4444-4444-444444444444");
    produced.put("legal_employer_id", "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    produced.put("base_currency", "USD");
    produced.put("default_language", "en");

    byte[] wire = AvroSerde.serialize(produced);
    GenericRecord decoded = AvroSerde.deserialize(wire, producer, consumer);
    assertThat(decoded.get("base_currency").toString()).isEqualTo("USD");
  }

  @Test
  void addingARequiredFieldWithoutDefaultBreaksBackwardCompatibility() {
    Schema producer = new Schema.Parser().parse(PRODUCER_JSON);
    Schema broken =
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
                    {"name": "tax_regime", "type": "string"}
                  ]
                }
                """);
    assertThat(AvroSerde.isBackwardCompatible(producer, broken)).isFalse();
  }
}
