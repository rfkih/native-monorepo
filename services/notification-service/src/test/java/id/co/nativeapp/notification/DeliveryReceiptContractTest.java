package id.co.nativeapp.notification;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.events.AvroSerde;
import id.co.nativeapp.notification.notification.messaging.DeliveryReceiptSchema;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.junit.jupiter.api.Test;

/**
 * Test (d) — the {@code DeliveryReceipt} PRODUCER contract test (no Spring context needed).
 *
 * <p>notification-service OWNS the {@code DeliveryReceipt} event ({@code
 * src/main/resources/avro/DeliveryReceipt.avsc}). This proves the schema (the rule-7 triad):
 *
 * <ul>
 *   <li>parses from the classpath and has the expected shape (full name + fields);
 *   <li>round-trips a {@link GenericRecord} through {@code libs/events} {@link AvroSerde}
 *       (serialize -> deserialize), the exact path the outbox payload is written with — including a
 *       null {@code provider_ref} (a failed delivery); and
 *   <li>evolves BACKWARD-COMPATIBLY — adding an optional field with a default is accepted, while a
 *       deliberate incompatible break (a new required field with no default) is rejected.
 * </ul>
 */
class DeliveryReceiptContractTest {

  @Test
  void avscParsesFromClasspath() {
    Schema schema = DeliveryReceiptSchema.schema();
    assertThat(schema.getFullName())
        .isEqualTo("id.co.nativeapp.events.notification.DeliveryReceipt");
    assertThat(schema.getField("notification_id")).isNotNull();
    assertThat(schema.getField("company_id")).isNotNull();
    assertThat(schema.getField("channel")).isNotNull();
    assertThat(schema.getField("status")).isNotNull();
    assertThat(schema.getField("provider_ref").schema().getType()).isEqualTo(Schema.Type.UNION);
    assertThat(schema.getField("delivered_at").schema().getLogicalType().getName())
        .isEqualTo("timestamp-millis");
  }

  @Test
  void deliveredReceiptRoundTripsThroughAvroSerde() {
    Schema schema = DeliveryReceiptSchema.schema();
    GenericRecord record = new GenericData.Record(schema);
    record.put("notification_id", "44444444-4444-4444-4444-444444444444");
    record.put("company_id", "11111111-1111-1111-1111-111111111111");
    record.put("channel", "EMAIL");
    record.put("status", "DELIVERED");
    record.put("provider_ref", "stub-abc");
    record.put("delivered_at", 1_750_000_000_000L);

    byte[] bytes = AvroSerde.serialize(record);
    GenericRecord decoded = AvroSerde.deserialize(bytes, schema);

    assertThat(decoded.get("status").toString()).isEqualTo("DELIVERED");
    assertThat(decoded.get("provider_ref").toString()).isEqualTo("stub-abc");
    assertThat(decoded.get("delivered_at")).isEqualTo(1_750_000_000_000L);
  }

  @Test
  void failedReceiptWithNullProviderRefRoundTrips() {
    Schema schema = DeliveryReceiptSchema.schema();
    GenericRecord record = new GenericData.Record(schema);
    record.put("notification_id", "44444444-4444-4444-4444-444444444444");
    record.put("company_id", "11111111-1111-1111-1111-111111111111");
    record.put("channel", "EMAIL");
    record.put("status", "FAILED");
    record.put("provider_ref", null); // a failure may carry no provider reference
    record.put("delivered_at", 1_750_000_000_000L);

    byte[] bytes = AvroSerde.serialize(record);
    GenericRecord decoded = AvroSerde.deserialize(bytes, schema);

    assertThat(decoded.get("status").toString()).isEqualTo("FAILED");
    assertThat(decoded.get("provider_ref")).isNull();
  }

  @Test
  void addingAnOptionalFieldWithDefaultIsBackwardCompatible() {
    Schema current = DeliveryReceiptSchema.schema();
    Schema evolved =
        new Schema.Parser()
            .parse(
                """
                {
                  "type": "record",
                  "name": "DeliveryReceipt",
                  "namespace": "id.co.nativeapp.events.notification",
                  "fields": [
                    {"name": "notification_id", "type": "string"},
                    {"name": "company_id", "type": "string"},
                    {"name": "channel", "type": "string"},
                    {"name": "status", "type": "string"},
                    {"name": "provider_ref", "type": ["null", "string"], "default": null},
                    {"name": "delivered_at", "type": {"type": "long", "logicalType": "timestamp-millis"}},
                    {"name": "attempt_count", "type": ["null", "int"], "default": null}
                  ]
                }
                """);
    assertThat(AvroSerde.isBackwardCompatible(current, evolved)).isTrue();
  }

  @Test
  void addingARequiredFieldWithoutDefaultBreaksBackwardCompatibility() {
    Schema current = DeliveryReceiptSchema.schema();
    Schema incompatible =
        new Schema.Parser()
            .parse(
                """
                {
                  "type": "record",
                  "name": "DeliveryReceipt",
                  "namespace": "id.co.nativeapp.events.notification",
                  "fields": [
                    {"name": "notification_id", "type": "string"},
                    {"name": "company_id", "type": "string"},
                    {"name": "channel", "type": "string"},
                    {"name": "status", "type": "string"},
                    {"name": "provider_ref", "type": ["null", "string"], "default": null},
                    {"name": "delivered_at", "type": {"type": "long", "logicalType": "timestamp-millis"}},
                    {"name": "attempt_count", "type": "int"}
                  ]
                }
                """);
    assertThat(AvroSerde.isBackwardCompatible(current, incompatible)).isFalse();
  }
}
