package id.co.nativeapp.org;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.events.AvroSerde;
import id.co.nativeapp.org.company.OrgUnitChangedSchema;
import id.co.nativeapp.org.company.OrgUnitCreatedSchema;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.junit.jupiter.api.Test;

/**
 * Acceptance (e) — the {@code OrgUnitCreated} / {@code OrgUnitChanged} contract test triad (no
 * Spring context). For each event it proves the {@code .avsc} on the classpath parses with the
 * expected key fields (ARCHITECTURE.md §5: {@code org_unit_id}, {@code type}, {@code parent_id},
 * {@code company_id}), that a {@link GenericRecord} round-trips through {@code libs/events} {@link
 * AvroSerde}, and that the backward-compatibility gate accepts the schema against itself and an
 * added-optional-field variant while rejecting an incompatible change (a new required field with no
 * default). CLAUDE.md rule 7: event schema changes are backward-compatible ONLY.
 */
class OrgUnitEventContractTest {

  private static final String TENANT = "11111111-1111-1111-1111-111111111111";
  private static final String UNIT = "22222222-2222-2222-2222-222222222222";
  private static final String PARENT = "33333333-3333-3333-3333-333333333333";

  // ---- OrgUnitCreated -------------------------------------------------------------------------

  @Test
  void orgUnitCreatedAvscParsesFromClasspathWithTheKeyFields() {
    Schema schema = OrgUnitCreatedSchema.schema();
    assertThat(schema.getFullName()).isEqualTo("id.co.nativeapp.events.org.OrgUnitCreated");
    assertThat(schema.getField("org_unit_id")).isNotNull();
    assertThat(schema.getField("type")).isNotNull();
    assertThat(schema.getField("parent_id")).isNotNull();
    assertThat(schema.getField("company_id")).isNotNull();
  }

  @Test
  void orgUnitCreatedRoundTripsThroughAvroSerdeWithAndWithoutAParent() {
    Schema schema = OrgUnitCreatedSchema.schema();

    GenericRecord withParent = new GenericData.Record(schema);
    withParent.put("org_unit_id", UNIT);
    withParent.put("company_id", TENANT);
    withParent.put("type", "BRANCH");
    withParent.put("parent_id", PARENT);
    withParent.put("legal_employer_id", TENANT);
    withParent.put("name", "North Branch");
    GenericRecord decodedWithParent =
        AvroSerde.deserialize(AvroSerde.serialize(withParent), schema);
    assertThat(decodedWithParent.get("type").toString()).isEqualTo("BRANCH");
    assertThat(decodedWithParent.get("parent_id").toString()).isEqualTo(PARENT);

    // A top-level node has a null parent_id (the nullable union).
    GenericRecord topLevel = new GenericData.Record(schema);
    topLevel.put("org_unit_id", UNIT);
    topLevel.put("company_id", TENANT);
    topLevel.put("type", "BUSINESS_UNIT");
    topLevel.put("parent_id", null);
    topLevel.put("legal_employer_id", TENANT);
    topLevel.put("name", "HQ");
    GenericRecord decodedTopLevel = AvroSerde.deserialize(AvroSerde.serialize(topLevel), schema);
    assertThat(decodedTopLevel.get("parent_id")).isNull();
  }

  @Test
  void orgUnitCreatedIsBackwardCompatibleWithItselfAndAnAddedOptionalField() {
    Schema v1 = OrgUnitCreatedSchema.schema();
    assertThat(AvroSerde.isBackwardCompatible(v1, v1)).isTrue();

    Schema v2 =
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
                    {"name": "cost_center", "type": ["null", "string"], "default": null}
                  ]
                }
                """);
    assertThat(AvroSerde.isBackwardCompatible(v1, v2)).isTrue();
  }

  @Test
  void orgUnitCreatedAddingARequiredFieldWithoutDefaultBreaksCompatibility() {
    Schema v1 = OrgUnitCreatedSchema.schema();
    Schema incompatible =
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
                    {"name": "manager_id", "type": "string"}
                  ]
                }
                """);
    assertThat(AvroSerde.isBackwardCompatible(v1, incompatible)).isFalse();
  }

  // ---- OrgUnitChanged -------------------------------------------------------------------------

  @Test
  void orgUnitChangedAvscParsesFromClasspathWithTheKeyFields() {
    Schema schema = OrgUnitChangedSchema.schema();
    assertThat(schema.getFullName()).isEqualTo("id.co.nativeapp.events.org.OrgUnitChanged");
    assertThat(schema.getField("org_unit_id")).isNotNull();
    assertThat(schema.getField("type")).isNotNull();
    assertThat(schema.getField("parent_id")).isNotNull();
    assertThat(schema.getField("company_id")).isNotNull();
    assertThat(schema.getField("change_kind")).isNotNull();
  }

  @Test
  void orgUnitChangedRoundTripsThroughAvroSerde() {
    Schema schema = OrgUnitChangedSchema.schema();
    GenericRecord record = new GenericData.Record(schema);
    record.put("org_unit_id", UNIT);
    record.put("company_id", TENANT);
    record.put("type", "OUTLET");
    record.put("parent_id", PARENT);
    record.put("change_kind", "MOVED");
    record.put("name", "Outlet 7");
    record.put("active", true);

    GenericRecord decoded = AvroSerde.deserialize(AvroSerde.serialize(record), schema);
    assertThat(decoded.get("change_kind").toString()).isEqualTo("MOVED");
    assertThat(decoded.get("active")).isEqualTo(true);
    assertThat(decoded.get("parent_id").toString()).isEqualTo(PARENT);
  }

  @Test
  void orgUnitChangedIsBackwardCompatibleWithItselfAndAnAddedOptionalField() {
    Schema v1 = OrgUnitChangedSchema.schema();
    assertThat(AvroSerde.isBackwardCompatible(v1, v1)).isTrue();

    Schema v2 =
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
                    {"name": "changed_by_note", "type": ["null", "string"], "default": null}
                  ]
                }
                """);
    assertThat(AvroSerde.isBackwardCompatible(v1, v2)).isTrue();
  }

  @Test
  void orgUnitChangedAddingARequiredFieldWithoutDefaultBreaksCompatibility() {
    Schema v1 = OrgUnitChangedSchema.schema();
    Schema incompatible =
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
                    {"name": "reason", "type": "string"}
                  ]
                }
                """);
    assertThat(AvroSerde.isBackwardCompatible(v1, incompatible)).isFalse();
  }
}
