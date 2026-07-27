package id.co.nativeapp.finance;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.events.AvroSerde;
import id.co.nativeapp.finance.orgref.messaging.OrgUnitRefSchemas;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.junit.jupiter.api.Test;

/**
 * The {@code OrgUnitCreated} / {@code OrgUnitChanged} CONSUMER contract tests for finance-service
 * (Phase 2 outlet-scoping increment — rule 7, no Spring context needed).
 *
 * <p>finance owns its consumer view of the org-service schemas, loaded from {@code libs/contracts}
 * on the classpath ({@code avro/OrgUnitCreated.avsc} / {@code avro/OrgUnitChanged.avsc}). This
 * proves each schema (the rule-7 triad):
 *
 * <ul>
 *   <li>parses from the classpath with the expected shape;
 *   <li>round-trips a {@link GenericRecord} through {@code libs/events} {@link AvroSerde} — the
 *       exact path the {@link id.co.nativeapp.finance.orgref.messaging.OrgUnitRefListener} decodes
 *       off the wire; and
 *   <li>stays BACKWARD-COMPATIBLE with the PRODUCER schema (org-service's, embedded verbatim from
 *       docs/EVENT-CATALOG.md) — a finance reader can read bytes the producer wrote, and a new
 *       required field without a default is correctly rejected.
 * </ul>
 */
class OrgUnitConsumerContractTest {

  /**
   * The producer's registered {@code OrgUnitCreated} schema, copied verbatim from
   * docs/EVENT-CATALOG.md (the contract anchor).
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
   * The producer's registered {@code OrgUnitChanged} schema, copied verbatim from
   * docs/EVENT-CATALOG.md (the contract anchor).
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

  // ------------------------------------------------------------------ OrgUnitCreated

  @Test
  void createdSchemaParsesFromClasspathWithExpectedShape() {
    Schema schema = OrgUnitRefSchemas.createdSchema();
    assertThat(schema.getFullName()).isEqualTo("id.co.nativeapp.events.org.OrgUnitCreated");
    assertThat(schema.getField("org_unit_id")).isNotNull();
    assertThat(schema.getField("company_id")).isNotNull();
    assertThat(schema.getField("type")).isNotNull();
    assertThat(schema.getField("parent_id")).isNotNull();
    assertThat(schema.getField("legal_employer_id")).isNotNull();
    assertThat(schema.getField("name")).isNotNull();
  }

  @Test
  void createdRoundTripsThroughAvroSerde() {
    Schema schema = OrgUnitRefSchemas.createdSchema();
    GenericRecord record = new GenericData.Record(schema);
    record.put("org_unit_id", "22222222-2222-2222-2222-222222222222");
    record.put("company_id", "11111111-1111-1111-1111-111111111111");
    record.put("type", "outlet");
    record.put("parent_id", null);
    record.put("legal_employer_id", "11111111-1111-1111-1111-111111111111");
    record.put("name", "Drive-Through Outlet");

    byte[] bytes = AvroSerde.serialize(record);
    GenericRecord decoded = AvroSerde.deserialize(bytes, schema);

    assertThat(decoded.get("name").toString()).isEqualTo("Drive-Through Outlet");
    assertThat(decoded.get("org_unit_id").toString())
        .isEqualTo("22222222-2222-2222-2222-222222222222");
  }

  @Test
  void createdConsumerCopyIsBackwardCompatibleWithProducerSchema() {
    Schema producer = new Schema.Parser().parse(PRODUCER_CREATED_JSON);
    Schema consumer = OrgUnitRefSchemas.createdSchema();
    assertThat(AvroSerde.isBackwardCompatible(producer, consumer)).isTrue();
  }

  @Test
  void producerCreatedBytesDecodeUnderConsumerCopy() {
    Schema producer = new Schema.Parser().parse(PRODUCER_CREATED_JSON);
    Schema consumer = OrgUnitRefSchemas.createdSchema();

    GenericRecord produced = new GenericData.Record(producer);
    produced.put("org_unit_id", "33333333-3333-3333-3333-333333333333");
    produced.put("company_id", "11111111-1111-1111-1111-111111111111");
    produced.put("type", "outlet");
    produced.put("parent_id", null);
    produced.put("legal_employer_id", "11111111-1111-1111-1111-111111111111");
    produced.put("name", "North Branch");

    byte[] wire = AvroSerde.serialize(produced);
    GenericRecord decoded = AvroSerde.deserialize(wire, producer, consumer);
    assertThat(decoded.get("name").toString()).isEqualTo("North Branch");
    assertThat(decoded.get("type").toString()).isEqualTo("outlet");
  }

  @Test
  void addingRequiredFieldToCreatedBreaksBackwardCompatibility() {
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
                    {"name": "cost_center_id", "type": "string"}
                  ]
                }
                """);
    assertThat(AvroSerde.isBackwardCompatible(producer, broken)).isFalse();
  }

  // ------------------------------------------------------------------ OrgUnitChanged

  @Test
  void changedSchemaParsesFromClasspathWithExpectedShape() {
    Schema schema = OrgUnitRefSchemas.changedSchema();
    assertThat(schema.getFullName()).isEqualTo("id.co.nativeapp.events.org.OrgUnitChanged");
    assertThat(schema.getField("org_unit_id")).isNotNull();
    assertThat(schema.getField("company_id")).isNotNull();
    assertThat(schema.getField("type")).isNotNull();
    assertThat(schema.getField("parent_id")).isNotNull();
    assertThat(schema.getField("change_kind")).isNotNull();
    assertThat(schema.getField("name")).isNotNull();
    assertThat(schema.getField("active").schema().getType()).isEqualTo(Schema.Type.BOOLEAN);
  }

  @Test
  void changedRoundTripsThroughAvroSerde() {
    Schema schema = OrgUnitRefSchemas.changedSchema();
    GenericRecord record = new GenericData.Record(schema);
    record.put("org_unit_id", "22222222-2222-2222-2222-222222222222");
    record.put("company_id", "11111111-1111-1111-1111-111111111111");
    record.put("type", "outlet");
    record.put("parent_id", null);
    record.put("change_kind", "RENAMED");
    record.put("name", "Renamed Outlet");
    record.put("active", true);

    byte[] bytes = AvroSerde.serialize(record);
    GenericRecord decoded = AvroSerde.deserialize(bytes, schema);

    assertThat(decoded.get("name").toString()).isEqualTo("Renamed Outlet");
    assertThat(decoded.get("active")).isEqualTo(true);
    assertThat(decoded.get("change_kind").toString()).isEqualTo("RENAMED");
  }

  @Test
  void changedConsumerCopyIsBackwardCompatibleWithProducerSchema() {
    Schema producer = new Schema.Parser().parse(PRODUCER_CHANGED_JSON);
    Schema consumer = OrgUnitRefSchemas.changedSchema();
    assertThat(AvroSerde.isBackwardCompatible(producer, consumer)).isTrue();
  }

  @Test
  void producerChangedBytesDecodeUnderConsumerCopy() {
    Schema producer = new Schema.Parser().parse(PRODUCER_CHANGED_JSON);
    Schema consumer = OrgUnitRefSchemas.changedSchema();

    GenericRecord produced = new GenericData.Record(producer);
    produced.put("org_unit_id", "44444444-4444-4444-4444-444444444444");
    produced.put("company_id", "11111111-1111-1111-1111-111111111111");
    produced.put("type", "outlet");
    produced.put("parent_id", null);
    produced.put("change_kind", "DEACTIVATED");
    produced.put("name", "Closed Outlet");
    produced.put("active", false);

    byte[] wire = AvroSerde.serialize(produced);
    GenericRecord decoded = AvroSerde.deserialize(wire, producer, consumer);
    assertThat(decoded.get("active")).isEqualTo(false);
    assertThat(decoded.get("change_kind").toString()).isEqualTo("DEACTIVATED");
  }

  @Test
  void addingRequiredFieldToChangedBreaksBackwardCompatibility() {
    Schema producer = new Schema.Parser().parse(PRODUCER_CHANGED_JSON);
    Schema broken =
        new Schema.Parser()
            .parse(
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
                    {"name": "active", "type": "boolean"},
                    {"name": "reason_code", "type": "string"}
                  ]
                }
                """);
    assertThat(AvroSerde.isBackwardCompatible(producer, broken)).isFalse();
  }
}
