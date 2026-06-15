package id.co.nativeapp.carwash;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.carwash.entitlement.messaging.EntitlementEventSchemas;
import id.co.nativeapp.events.AvroSerde;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.junit.jupiter.api.Test;

/**
 * Test (f) — the CONSUMER-copy contract test for {@code EntitlementGranted} / {@code
 * EntitlementRevoked} (no Spring context; rule 7).
 *
 * <p>Each consumer copy parses with the expected full name + the §5 key fields (company_id,
 * module_key), round-trips through {@code libs/events AvroSerde}, and stays mutually
 * backward-compatible with the entitlement-service PRODUCER schema (inlined from
 * docs/EVENT-CATALOG.md) — so carwash reads the producer's bytes and the back-compat gate accepts
 * an added-optional field while rejecting a new required field with no default.
 */
class EntitlementConsumerContractTest {

  private static final Schema PRODUCER_GRANTED =
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
                  {"name": "granted_at", "type": {"type": "long", "logicalType": "timestamp-millis"}}
                ]
              }
              """);

  private static final Schema PRODUCER_REVOKED =
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
                  {"name": "revoked_at", "type": {"type": "long", "logicalType": "timestamp-millis"}}
                ]
              }
              """);

  @Test
  void grantedConsumerCopyParsesAndMatchesTheProducer() {
    Schema schema = EntitlementEventSchemas.grantedSchema();
    assertThat(schema.getFullName())
        .isEqualTo("id.co.nativeapp.events.entitlement.EntitlementGranted");
    assertThat(schema.getField("company_id")).isNotNull();
    assertThat(schema.getField("module_key")).isNotNull();
    assertThat(AvroSerde.isBackwardCompatible(PRODUCER_GRANTED, schema)).isTrue();
    assertThat(AvroSerde.isBackwardCompatible(schema, PRODUCER_GRANTED)).isTrue();
  }

  @Test
  void revokedConsumerCopyParsesAndMatchesTheProducer() {
    Schema schema = EntitlementEventSchemas.revokedSchema();
    assertThat(schema.getFullName())
        .isEqualTo("id.co.nativeapp.events.entitlement.EntitlementRevoked");
    assertThat(AvroSerde.isBackwardCompatible(PRODUCER_REVOKED, schema)).isTrue();
    assertThat(AvroSerde.isBackwardCompatible(schema, PRODUCER_REVOKED)).isTrue();
  }

  @Test
  void grantedRoundTripsAndDecodesToEntitledTrue() {
    Schema schema = EntitlementEventSchemas.grantedSchema();
    GenericRecord record = new GenericData.Record(schema);
    record.put("company_id", "11111111-1111-1111-1111-111111111111");
    record.put("module_key", "carwash");
    record.put("granted_at", 1_750_000_000_000L);
    byte[] bytes = AvroSerde.serialize(record);

    var decoded =
        EntitlementEventSchemas.decodeGranted(
            java.util.UUID.fromString("44444444-4444-4444-4444-444444444444"), bytes);
    assertThat(decoded.companyId()).isEqualTo("11111111-1111-1111-1111-111111111111");
    assertThat(decoded.moduleKey()).isEqualTo("carwash");
    assertThat(decoded.entitled()).isTrue();
  }

  @Test
  void revokedRoundTripsAndDecodesToEntitledFalse() {
    Schema schema = EntitlementEventSchemas.revokedSchema();
    GenericRecord record = new GenericData.Record(schema);
    record.put("company_id", "11111111-1111-1111-1111-111111111111");
    record.put("module_key", "carwash");
    record.put("revoked_at", 1_750_000_000_000L);
    byte[] bytes = AvroSerde.serialize(record);

    var decoded =
        EntitlementEventSchemas.decodeRevoked(
            java.util.UUID.fromString("44444444-4444-4444-4444-444444444444"), bytes);
    assertThat(decoded.moduleKey()).isEqualTo("carwash");
    assertThat(decoded.entitled()).isFalse();
  }

  @Test
  void grantedRejectsANewRequiredFieldWithoutDefault() {
    Schema v1 = EntitlementEventSchemas.grantedSchema();
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
}
