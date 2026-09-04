package id.co.nativeapp.org;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.events.AvroSerde;
import id.co.nativeapp.org.company.domain.OrgUnit;
import id.co.nativeapp.org.company.domain.OrgUnitType;
import id.co.nativeapp.org.company.messaging.OrgUnitDeletedSchema;
import java.time.LocalDate;
import java.util.UUID;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.junit.jupiter.api.Test;

/**
 * Acceptance (e) — the {@code OrgUnitDeleted} contract test triad (no Spring context; ADR 0070).
 * Proves the {@code .avsc} on the classpath parses with the expected key fields (ARCHITECTURE.md
 * §5: {@code org_unit_id}, {@code type}, {@code parent_id}, {@code company_id}), that a {@link
 * GenericRecord} round-trips through {@code libs/events} {@link AvroSerde} with and without a
 * parent, that {@link OrgUnitDeletedSchema#toRecord} projects the aggregate faithfully, and that
 * the backward-compatibility gate accepts an added-optional-field variant while rejecting a new
 * required field with no default. CLAUDE.md rule 7: event schema changes are backward-compatible
 * ONLY.
 */
class OrgUnitDeletedContractTest {

  private static final String TENANT = "11111111-1111-1111-1111-111111111111";
  private static final String UNIT = "22222222-2222-2222-2222-222222222222";
  private static final String PARENT = "33333333-3333-3333-3333-333333333333";

  @Test
  void avscParsesFromClasspathWithTheKeyFields() {
    Schema schema = OrgUnitDeletedSchema.schema();
    assertThat(schema.getFullName()).isEqualTo("id.co.nativeapp.events.org.OrgUnitDeleted");
    assertThat(schema.getField("org_unit_id")).isNotNull();
    assertThat(schema.getField("type")).isNotNull();
    assertThat(schema.getField("parent_id")).isNotNull();
    assertThat(schema.getField("company_id")).isNotNull();
    // parent_id is the nullable union and MUST stay last: AvroSerde decodes positionally, so a
    // future appended field goes AFTER it, never before.
    assertThat(schema.getFields().getLast().name()).isEqualTo("parent_id");
  }

  @Test
  void roundTripsThroughAvroSerdeWithAndWithoutAParent() {
    Schema schema = OrgUnitDeletedSchema.schema();

    GenericRecord child = new GenericData.Record(schema);
    child.put("org_unit_id", UNIT);
    child.put("company_id", TENANT);
    child.put("type", "OUTLET");
    child.put("parent_id", PARENT);
    GenericRecord decodedChild = AvroSerde.deserialize(AvroSerde.serialize(child), schema);
    assertThat(decodedChild.get("org_unit_id").toString()).isEqualTo(UNIT);
    assertThat(decodedChild.get("company_id").toString()).isEqualTo(TENANT);
    assertThat(decodedChild.get("type").toString()).isEqualTo("OUTLET");
    assertThat(decodedChild.get("parent_id").toString()).isEqualTo(PARENT);

    // A top-level node deletes with a null parent_id (the nullable union).
    GenericRecord topLevel = new GenericData.Record(schema);
    topLevel.put("org_unit_id", UNIT);
    topLevel.put("company_id", TENANT);
    topLevel.put("type", "OUTLET");
    topLevel.put("parent_id", null);
    GenericRecord decodedTopLevel = AvroSerde.deserialize(AvroSerde.serialize(topLevel), schema);
    assertThat(decodedTopLevel.get("parent_id")).isNull();
  }

  @Test
  void toRecordProjectsTheAggregateBeingDeleted() {
    OrgUnit outlet =
        new OrgUnit(
            "Bara Kebab Binagriya",
            OrgUnitType.OUTLET,
            UUID.fromString(TENANT),
            LocalDate.of(2026, 9, 1));
    outlet.setCompanyId(TENANT);

    GenericRecord record = OrgUnitDeletedSchema.toRecord(outlet);
    assertThat(record.get("org_unit_id").toString()).isEqualTo(outlet.getId().toString());
    assertThat(record.get("company_id")).isEqualTo(TENANT);
    assertThat(record.get("type")).isEqualTo("OUTLET");
    // ADR 0070: an outlet is always top-level, so parent_id is the null branch of the union.
    assertThat(record.get("parent_id")).isNull();

    // And it survives the wire.
    GenericRecord decoded =
        AvroSerde.deserialize(AvroSerde.serialize(record), OrgUnitDeletedSchema.schema());
    assertThat(decoded.get("org_unit_id").toString()).isEqualTo(outlet.getId().toString());
    assertThat(decoded.get("parent_id")).isNull();
  }

  @Test
  void isBackwardCompatibleWithItselfAndAnAddedOptionalField() {
    Schema v1 = OrgUnitDeletedSchema.schema();
    assertThat(AvroSerde.isBackwardCompatible(v1, v1)).isTrue();

    Schema v2 =
        new Schema.Parser()
            .parse(
                """
                {
                  "type": "record",
                  "name": "OrgUnitDeleted",
                  "namespace": "id.co.nativeapp.events.org",
                  "fields": [
                    {"name": "org_unit_id", "type": "string"},
                    {"name": "company_id", "type": "string"},
                    {"name": "type", "type": "string"},
                    {"name": "parent_id", "type": ["null", "string"], "default": null},
                    {"name": "deleted_by", "type": ["null", "string"], "default": null}
                  ]
                }
                """);
    assertThat(AvroSerde.isBackwardCompatible(v1, v2)).isTrue();
  }

  @Test
  void rejectsANewRequiredFieldWithoutADefault() {
    Schema v1 = OrgUnitDeletedSchema.schema();
    Schema breaking =
        new Schema.Parser()
            .parse(
                """
                {
                  "type": "record",
                  "name": "OrgUnitDeleted",
                  "namespace": "id.co.nativeapp.events.org",
                  "fields": [
                    {"name": "org_unit_id", "type": "string"},
                    {"name": "company_id", "type": "string"},
                    {"name": "type", "type": "string"},
                    {"name": "parent_id", "type": ["null", "string"], "default": null},
                    {"name": "reason", "type": "string"}
                  ]
                }
                """);
    assertThat(AvroSerde.isBackwardCompatible(v1, breaking)).isFalse();
  }
}
