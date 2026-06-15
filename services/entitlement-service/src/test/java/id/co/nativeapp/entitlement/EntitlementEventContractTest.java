package id.co.nativeapp.entitlement;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.entitlement.entitlement.messaging.EntitlementEventSchema;
import id.co.nativeapp.events.AvroSerde;
import java.time.Instant;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericRecord;
import org.junit.jupiter.api.Test;

/**
 * Test (e) — the contract triad for the PRODUCED events {@code EntitlementGranted} and {@code
 * EntitlementRevoked} (no Spring context needed; CLAUDE.md rule 7 / ENGINEERING-STANDARDS §3.2).
 *
 * <p>For each event it proves the {@code .avsc} on the classpath (a) parses with the expected shape
 * (full name + the §5 key fields {@code company_id}/{@code module_key}), (b) round-trips a {@link
 * GenericRecord} through {@code libs/events} {@link AvroSerde} (the exact path the outbox
 * serializes on), and (c) backward-compatibility: the schema is compatible with itself and with an
 * added-optional-field variant, while a new required field with no default is rejected.
 */
class EntitlementEventContractTest {

  // ---- EntitlementGranted ----

  @Test
  void grantedAvscParsesWithTheExpectedShape() {
    Schema schema = EntitlementEventSchema.grantedSchema();
    assertThat(schema.getFullName())
        .isEqualTo("id.co.nativeapp.events.entitlement.EntitlementGranted");
    assertThat(schema.getField("company_id")).isNotNull();
    assertThat(schema.getField("module_key")).isNotNull();
    assertThat(schema.getField("granted_at").schema().getLogicalType().getName())
        .isEqualTo("timestamp-millis");
  }

  @Test
  void grantedRoundTripsThroughAvroSerde() {
    GenericRecord record =
        EntitlementEventSchema.granted(
            "11111111-1111-1111-1111-111111111111",
            "restaurant",
            Instant.parse("2026-06-14T00:00:00Z"));
    byte[] bytes = AvroSerde.serialize(record);
    GenericRecord decoded = AvroSerde.deserialize(bytes, EntitlementEventSchema.grantedSchema());
    assertThat(decoded.get("company_id").toString())
        .isEqualTo("11111111-1111-1111-1111-111111111111");
    assertThat(decoded.get("module_key").toString()).isEqualTo("restaurant");
    assertThat(decoded.get("granted_at"))
        .isEqualTo(Instant.parse("2026-06-14T00:00:00Z").toEpochMilli());
  }

  @Test
  void grantedIsBackwardCompatibleWithItselfAndAnAddedOptionalField() {
    Schema v1 = EntitlementEventSchema.grantedSchema();
    assertThat(AvroSerde.isBackwardCompatible(v1, v1)).isTrue();
    Schema v2 =
        new Schema.Parser()
            .parse(
                """
                {
                  "type": "record",
                  "name": "EntitlementGranted",
                  "namespace": "id.co.nativeapp.events.entitlement",
                  "fields": [
                    {"name": "company_id", "type": "string"},
                    {"name": "module_key", "type": "string"},
                    {"name": "granted_at", "type": {"type": "long", "logicalType": "timestamp-millis"}},
                    {"name": "granted_by", "type": ["null", "string"], "default": null}
                  ]
                }
                """);
    assertThat(AvroSerde.isBackwardCompatible(v1, v2)).isTrue();
  }

  @Test
  void grantedRejectsANewRequiredFieldWithoutDefault() {
    Schema v1 = EntitlementEventSchema.grantedSchema();
    Schema incompatible =
        new Schema.Parser()
            .parse(
                """
                {
                  "type": "record",
                  "name": "EntitlementGranted",
                  "namespace": "id.co.nativeapp.events.entitlement",
                  "fields": [
                    {"name": "company_id", "type": "string"},
                    {"name": "module_key", "type": "string"},
                    {"name": "granted_at", "type": {"type": "long", "logicalType": "timestamp-millis"}},
                    {"name": "plan_id", "type": "string"}
                  ]
                }
                """);
    assertThat(AvroSerde.isBackwardCompatible(v1, incompatible)).isFalse();
  }

  // ---- EntitlementRevoked ----

  @Test
  void revokedAvscParsesWithTheExpectedShape() {
    Schema schema = EntitlementEventSchema.revokedSchema();
    assertThat(schema.getFullName())
        .isEqualTo("id.co.nativeapp.events.entitlement.EntitlementRevoked");
    assertThat(schema.getField("company_id")).isNotNull();
    assertThat(schema.getField("module_key")).isNotNull();
    assertThat(schema.getField("revoked_at").schema().getLogicalType().getName())
        .isEqualTo("timestamp-millis");
  }

  @Test
  void revokedRoundTripsThroughAvroSerde() {
    GenericRecord record =
        EntitlementEventSchema.revoked(
            "11111111-1111-1111-1111-111111111111",
            "finance",
            Instant.parse("2026-06-14T00:00:00Z"));
    byte[] bytes = AvroSerde.serialize(record);
    GenericRecord decoded = AvroSerde.deserialize(bytes, EntitlementEventSchema.revokedSchema());
    assertThat(decoded.get("company_id").toString())
        .isEqualTo("11111111-1111-1111-1111-111111111111");
    assertThat(decoded.get("module_key").toString()).isEqualTo("finance");
    assertThat(decoded.get("revoked_at"))
        .isEqualTo(Instant.parse("2026-06-14T00:00:00Z").toEpochMilli());
  }

  @Test
  void revokedIsBackwardCompatibleWithItselfAndAnAddedOptionalField() {
    Schema v1 = EntitlementEventSchema.revokedSchema();
    assertThat(AvroSerde.isBackwardCompatible(v1, v1)).isTrue();
    Schema v2 =
        new Schema.Parser()
            .parse(
                """
                {
                  "type": "record",
                  "name": "EntitlementRevoked",
                  "namespace": "id.co.nativeapp.events.entitlement",
                  "fields": [
                    {"name": "company_id", "type": "string"},
                    {"name": "module_key", "type": "string"},
                    {"name": "revoked_at", "type": {"type": "long", "logicalType": "timestamp-millis"}},
                    {"name": "reason", "type": ["null", "string"], "default": null}
                  ]
                }
                """);
    assertThat(AvroSerde.isBackwardCompatible(v1, v2)).isTrue();
  }

  @Test
  void revokedRejectsANewRequiredFieldWithoutDefault() {
    Schema v1 = EntitlementEventSchema.revokedSchema();
    Schema incompatible =
        new Schema.Parser()
            .parse(
                """
                {
                  "type": "record",
                  "name": "EntitlementRevoked",
                  "namespace": "id.co.nativeapp.events.entitlement",
                  "fields": [
                    {"name": "company_id", "type": "string"},
                    {"name": "module_key", "type": "string"},
                    {"name": "revoked_at", "type": {"type": "long", "logicalType": "timestamp-millis"}},
                    {"name": "reason", "type": "string"}
                  ]
                }
                """);
    assertThat(AvroSerde.isBackwardCompatible(v1, incompatible)).isFalse();
  }
}
