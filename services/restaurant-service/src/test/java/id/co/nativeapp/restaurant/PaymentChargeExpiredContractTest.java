package id.co.nativeapp.restaurant;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.events.AvroSerde;
import id.co.nativeapp.restaurant.payment.messaging.PaymentChargeExpiredConsumerSchema;
import id.co.nativeapp.restaurant.payment.messaging.PaymentChargeExpiredEvent;
import java.util.UUID;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.junit.jupiter.api.Test;

/**
 * Consumer-driven contract test for the {@code PaymentChargeExpired} event (ADR 0045) — the
 * un-happy-path counterpart of {@link PaymentChargeSucceededContractTest}. payment-service is the
 * producer, restaurant-service the consumer (one of the verticals; each filters on {@code
 * vertical}).
 */
class PaymentChargeExpiredContractTest {

  private static final String CHARGE_ID = "aaaaaaaa-1111-2222-3333-444444444444";
  private static final String COMPANY_ID = "11111111-1111-1111-1111-111111111111";
  private static final String PAYMENT_ID = "bbbbbbbb-1111-2222-3333-444444444444";
  private static final String TICKET_ID = "cccccccc-1111-2222-3333-444444444444";
  private static final String BUSINESS_ID = "22222222-2222-2222-2222-222222222222";

  /** The producer's schema as registered in docs/EVENT-CATALOG.md — the contract anchor. */
  private static final String PRODUCER_SCHEMA_JSON =
      """
      {
        "type": "record",
        "name": "PaymentChargeExpired",
        "namespace": "id.co.nativeapp.events.payment",
        "doc": "Emitted by payment-service when a QR_ISSUED dynamic-QRIS gateway charge (ADR 0045) terminates without settling.",
        "fields": [
          {"name": "charge_id", "type": "string"},
          {"name": "company_id", "type": "string"},
          {"name": "vertical", "type": "string"},
          {"name": "payment_id", "type": "string"},
          {"name": "reference_id", "type": ["null", "string"], "default": null},
          {"name": "business_id", "type": "string"},
          {"name": "amount_minor", "type": "long"},
          {"name": "currency", "type": "string"},
          {"name": "reason", "type": "string"},
          {"name": "occurred_at", "type": {"type": "long", "logicalType": "timestamp-millis"}}
        ]
      }
      """;

  @Test
  void schemaParsesFromClasspathWithExpectedShape() {
    Schema schema = PaymentChargeExpiredConsumerSchema.schema();

    assertThat(schema.getFullName())
        .isEqualTo("id.co.nativeapp.events.payment.PaymentChargeExpired");
    assertThat(schema.getField("charge_id")).isNotNull();
    assertThat(schema.getField("company_id")).isNotNull();
    assertThat(schema.getField("vertical")).isNotNull();
    assertThat(schema.getField("payment_id")).isNotNull();
    assertThat(schema.getField("reference_id").schema().getType()).isEqualTo(Schema.Type.UNION);
    assertThat(schema.getField("business_id")).isNotNull();
    assertThat(schema.getField("amount_minor").schema().getType()).isEqualTo(Schema.Type.LONG);
    assertThat(schema.getField("currency")).isNotNull();
    assertThat(schema.getField("reason")).isNotNull();
    assertThat(schema.getField("occurred_at").schema().getLogicalType().getName())
        .isEqualTo("timestamp-millis");
  }

  @Test
  void consumerCopyIsBackwardCompatibleWithProducerSchema() {
    Schema producer = new Schema.Parser().parse(PRODUCER_SCHEMA_JSON);
    Schema consumer = PaymentChargeExpiredConsumerSchema.schema();
    assertThat(AvroSerde.isBackwardCompatible(producer, consumer)).isTrue();
  }

  @Test
  void producerBytesDecodeUnderConsumerSchema() {
    Schema producer = new Schema.Parser().parse(PRODUCER_SCHEMA_JSON);
    Schema consumer = PaymentChargeExpiredConsumerSchema.schema();

    GenericRecord produced = new GenericData.Record(producer);
    produced.put("charge_id", CHARGE_ID);
    produced.put("company_id", COMPANY_ID);
    produced.put("vertical", "restaurant");
    produced.put("payment_id", PAYMENT_ID);
    produced.put("reference_id", null);
    produced.put("business_id", BUSINESS_ID);
    produced.put("amount_minor", 185_000L);
    produced.put("currency", "IDR");
    produced.put("reason", "EXPIRED");
    produced.put("occurred_at", 1_750_000_000_000L);

    byte[] wire = AvroSerde.serialize(produced);
    GenericRecord decoded = AvroSerde.deserialize(wire, producer, consumer);
    assertThat(decoded.get("vertical").toString()).isEqualTo("restaurant");
    assertThat(decoded.get("reference_id")).isNull();
    assertThat(decoded.get("reason").toString()).isEqualTo("EXPIRED");
    assertThat(decoded.get("amount_minor")).isEqualTo(185_000L);
  }

  @Test
  void decodeProducesTheCorrectRestaurantEvent() {
    // The restaurant shape: reference_id is always null (payment_id IS the release key).
    Schema schema = PaymentChargeExpiredConsumerSchema.schema();
    GenericRecord record = new GenericData.Record(schema);
    record.put("charge_id", CHARGE_ID);
    record.put("company_id", COMPANY_ID);
    record.put("vertical", "restaurant");
    record.put("payment_id", PAYMENT_ID);
    record.put("reference_id", null);
    record.put("business_id", BUSINESS_ID);
    record.put("amount_minor", 185_000L);
    record.put("currency", "IDR");
    record.put("reason", "CANCELED");
    record.put("occurred_at", 1_750_000_000_000L);

    byte[] bytes = AvroSerde.serialize(record);
    UUID eventId = UUID.randomUUID();
    PaymentChargeExpiredEvent event = PaymentChargeExpiredConsumerSchema.decode(eventId, bytes);

    assertThat(event.eventId()).isEqualTo(eventId);
    assertThat(event.chargeId()).isEqualTo(UUID.fromString(CHARGE_ID));
    assertThat(event.companyId()).isEqualTo(COMPANY_ID);
    assertThat(event.vertical()).isEqualTo("restaurant");
    assertThat(event.paymentId()).isEqualTo(UUID.fromString(PAYMENT_ID));
    assertThat(event.referenceId()).isNull();
    assertThat(event.businessId()).isEqualTo(UUID.fromString(BUSINESS_ID));
    assertThat(event.amountMinor()).isEqualTo(185_000L);
    assertThat(event.currency()).isEqualTo("IDR");
    assertThat(event.reason()).isEqualTo("CANCELED");
  }

  @Test
  void decodeCarriesTheTicketReferenceIdForAVerticalThatUsesIt() {
    Schema schema = PaymentChargeExpiredConsumerSchema.schema();
    GenericRecord record = new GenericData.Record(schema);
    record.put("charge_id", CHARGE_ID);
    record.put("company_id", COMPANY_ID);
    record.put("vertical", "carwash");
    record.put("payment_id", PAYMENT_ID);
    record.put("reference_id", TICKET_ID);
    record.put("business_id", BUSINESS_ID);
    record.put("amount_minor", 75_000L);
    record.put("currency", "IDR");
    record.put("reason", "FAILED");
    record.put("occurred_at", 1_750_000_000_000L);

    byte[] bytes = AvroSerde.serialize(record);
    PaymentChargeExpiredEvent event =
        PaymentChargeExpiredConsumerSchema.decode(UUID.randomUUID(), bytes);

    assertThat(event.vertical()).isEqualTo("carwash");
    assertThat(event.referenceId()).isEqualTo(UUID.fromString(TICKET_ID));
    assertThat(event.reason()).isEqualTo("FAILED");
  }

  @Test
  void addingRequiredFieldBreaksBackwardCompatibility() {
    Schema producer = new Schema.Parser().parse(PRODUCER_SCHEMA_JSON);
    Schema broken =
        new Schema.Parser()
            .parse(
                """
                {
                  "type": "record",
                  "name": "PaymentChargeExpired",
                  "namespace": "id.co.nativeapp.events.payment",
                  "fields": [
                    {"name": "charge_id", "type": "string"},
                    {"name": "company_id", "type": "string"},
                    {"name": "vertical", "type": "string"},
                    {"name": "payment_id", "type": "string"},
                    {"name": "reference_id", "type": ["null", "string"], "default": null},
                    {"name": "business_id", "type": "string"},
                    {"name": "amount_minor", "type": "long"},
                    {"name": "currency", "type": "string"},
                    {"name": "reason", "type": "string"},
                    {"name": "occurred_at", "type": {"type": "long", "logicalType": "timestamp-millis"}},
                    {"name": "swept_by", "type": "string"}
                  ]
                }
                """);
    assertThat(AvroSerde.isBackwardCompatible(producer, broken)).isFalse();
  }
}
