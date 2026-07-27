package id.co.nativeapp.employee;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.employee.org.messaging.OrgUnitEventSchemas;
import id.co.nativeapp.events.AvroSerde;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.junit.jupiter.api.Test;

/**
 * The {@code OrgUnitCreated} / {@code OrgUnitChanged} CONSUMER contract tests (rule 7). employee
 * owns its own consumer copies of the org schemas; this proves each copy:
 *
 * <ul>
 *   <li>parses from the classpath with the expected shape;
 *   <li>round-trips a {@link GenericRecord} through {@code libs/events} {@link AvroSerde} — the
 *       exact path the listener decodes off the wire; and
 *   <li>stays BACKWARD-COMPATIBLE with the PRODUCER schema (org-service's, copied verbatim from
 *       docs/EVENT-CATALOG.md) — a consumer reader on its own copy can read bytes the producer
 *       wrote, and a deliberate incompatible break is rejected.
 * </ul>
 */
class OrgUnitConsumerContractTest {

  /**
   * The producer's registered {@code OrgUnitCreated} schema, copied verbatim from EVENT-CATALOG.md.
   */
  private static final String PRODUCER_CREATED_JSON =
      """
      {
        "type": "record",
        "name": "OrgUnitCreated",
        "namespace": "id.co.nativeapp.events.org",
        "fields": [
          {"name": "org_unit_id", "type": "string"},
          {"name": "company_id", "type": "string"},
          {"name": "type", "type": "string"},
          {"name": "parent_id", "type": ["null", "string"], "default": null},
          {"name": "legal_employer_id", "type": "string"},
          {"name": "name", "type": "string"}
        ]
      }
      """;

  /**
   * The producer's registered {@code OrgUnitChanged} schema, copied verbatim from EVENT-CATALOG.md.
   */
  private static final String PRODUCER_CHANGED_JSON =
      """
      {
        "type": "record",
        "name": "OrgUnitChanged",
        "namespace": "id.co.nativeapp.events.org",
        "fields": [
          {"name": "org_unit_id", "type": "string"},
          {"name": "company_id", "type": "string"},
          {"name": "type", "type": "string"},
          {"name": "parent_id", "type": ["null", "string"], "default": null},
          {"name": "change_kind", "type": "string"},
          {"name": "name", "type": "string"},
          {"name": "active", "type": "boolean"}
        ]
      }
      """;

  @Test
  void createdConsumerCopyParsesWithTheExpectedShape() {
    Schema schema = OrgUnitEventSchemas.createdSchema();
    assertThat(schema.getFullName()).isEqualTo("id.co.nativeapp.events.org.OrgUnitCreated");
    assertThat(schema.getField("org_unit_id")).isNotNull();
    assertThat(schema.getField("legal_employer_id")).isNotNull();
    assertThat(schema.getField("type")).isNotNull();
  }

  @Test
  void changedConsumerCopyParsesWithTheExpectedShape() {
    Schema schema = OrgUnitEventSchemas.changedSchema();
    assertThat(schema.getFullName()).isEqualTo("id.co.nativeapp.events.org.OrgUnitChanged");
    assertThat(schema.getField("active").schema().getType()).isEqualTo(Schema.Type.BOOLEAN);
    assertThat(schema.getField("change_kind")).isNotNull();
  }

  @Test
  void createdRoundTripsThroughAvroSerde() {
    Schema schema = OrgUnitEventSchemas.createdSchema();
    GenericRecord record = new GenericData.Record(schema);
    record.put("org_unit_id", "22222222-2222-2222-2222-222222222222");
    record.put("company_id", "11111111-1111-1111-1111-111111111111");
    record.put("type", "OUTLET");
    record.put("parent_id", null);
    record.put("legal_employer_id", "11111111-1111-1111-1111-111111111111");
    record.put("name", "Outlet 1");

    byte[] bytes = AvroSerde.serialize(record);
    GenericRecord decoded = AvroSerde.deserialize(bytes, schema);
    assertThat(decoded.get("legal_employer_id").toString())
        .isEqualTo("11111111-1111-1111-1111-111111111111");
  }

  @Test
  void createdConsumerCopyIsBackwardCompatibleWithTheProducerSchema() {
    Schema producer = new Schema.Parser().parse(PRODUCER_CREATED_JSON);
    Schema consumer = OrgUnitEventSchemas.createdSchema();
    assertThat(AvroSerde.isBackwardCompatible(producer, consumer)).isTrue();
  }

  @Test
  void changedConsumerCopyIsBackwardCompatibleWithTheProducerSchema() {
    Schema producer = new Schema.Parser().parse(PRODUCER_CHANGED_JSON);
    Schema consumer = OrgUnitEventSchemas.changedSchema();
    assertThat(AvroSerde.isBackwardCompatible(producer, consumer)).isTrue();
  }

  @Test
  void producerCreatedBytesDecodeUnderTheConsumerCopy() {
    Schema producer = new Schema.Parser().parse(PRODUCER_CREATED_JSON);
    Schema consumer = OrgUnitEventSchemas.createdSchema();
    GenericRecord produced = new GenericData.Record(producer);
    produced.put("org_unit_id", "22222222-2222-2222-2222-222222222222");
    produced.put("company_id", "11111111-1111-1111-1111-111111111111");
    produced.put("type", "OUTLET");
    produced.put("parent_id", null);
    produced.put("legal_employer_id", "11111111-1111-1111-1111-111111111111");
    produced.put("name", "North Outlet");

    byte[] wire = AvroSerde.serialize(produced);
    GenericRecord decoded = AvroSerde.deserialize(wire, producer, consumer);
    assertThat(decoded.get("type").toString()).isEqualTo("OUTLET");
  }

  @Test
  void addingARequiredFieldWithoutDefaultBreaksBackwardCompatibility() {
    Schema producer = new Schema.Parser().parse(PRODUCER_CREATED_JSON);
    Schema broken =
        new Schema.Parser()
            .parse(
                """
                {
                  "type": "record",
                  "name": "OrgUnitCreated",
                  "namespace": "id.co.nativeapp.events.org",
                  "fields": [
                    {"name": "org_unit_id", "type": "string"},
                    {"name": "company_id", "type": "string"},
                    {"name": "type", "type": "string"},
                    {"name": "parent_id", "type": ["null", "string"], "default": null},
                    {"name": "legal_employer_id", "type": "string"},
                    {"name": "name", "type": "string"},
                    {"name": "cost_center", "type": "string"}
                  ]
                }
                """);
    assertThat(AvroSerde.isBackwardCompatible(producer, broken)).isFalse();
  }
}
