package id.co.nativeapp.restaurant;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.events.AvroSerde;
import id.co.nativeapp.restaurant.payment.messaging.PaymentChargeSucceededConsumerSchema;
import id.co.nativeapp.restaurant.payment.messaging.PaymentChargeSucceededEvent;
import java.util.UUID;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.junit.jupiter.api.Test;

/**
 * Consumer-driven contract test for the {@code PaymentChargeSucceeded} event (ADR 0045) — mirrors
 * {@link GiftCardStateChangedContractTest} / {@link LoyaltyBalanceChangedContractTest}.
 * payment-service is the producer, restaurant-service the consumer (one of three verticals; each
 * filters on {@code vertical}).
 */
class PaymentChargeSucceededContractTest {

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
        "name": "PaymentChargeSucceeded",
        "namespace": "id.co.nativeapp.events.payment",
        "doc": "Emitted by payment-service when a dynamic-QRIS gateway charge (ADR 0045) reaches SUCCEEDED.",
        "fields": [
          {"name": "charge_id", "type": "string"},
          {"name": "company_id", "type": "string"},
          {"name": "vertical", "type": "string"},
          {"name": "payment_id", "type": "string"},
          {"name": "reference_id", "type": ["null", "string"], "default": null},
          {"name": "business_id", "type": "string"},
          {"name": "amount_minor", "type": "long"},
          {"name": "currency", "type": "string"},
          {"name": "provider", "type": "string"},
          {"name": "provider_txn_id", "type": ["null", "string"], "default": null},
          {"name": "succeeded_at", "type": {"type": "long", "logicalType": "timestamp-millis"}}
        ]
      }
      """;

  @Test
  void schemaParsesFromClasspathWithExpectedShape() {
    Schema schema = PaymentChargeSucceededConsumerSchema.schema();

    assertThat(schema.getFullName())
        .isEqualTo("id.co.nativeapp.events.payment.PaymentChargeSucceeded");
    assertThat(schema.getField("charge_id")).isNotNull();
    assertThat(schema.getField("company_id")).isNotNull();
    assertThat(schema.getField("vertical")).isNotNull();
    assertThat(schema.getField("payment_id")).isNotNull();
    assertThat(schema.getField("reference_id").schema().getType()).isEqualTo(Schema.Type.UNION);
    assertThat(schema.getField("business_id")).isNotNull();
    assertThat(schema.getField("amount_minor").schema().getType()).isEqualTo(Schema.Type.LONG);
    assertThat(schema.getField("currency")).isNotNull();
    assertThat(schema.getField("provider")).isNotNull();
    assertThat(schema.getField("provider_txn_id").schema().getType()).isEqualTo(Schema.Type.UNION);
    assertThat(schema.getField("succeeded_at").schema().getLogicalType().getName())
        .isEqualTo("timestamp-millis");
  }

  @Test
  void genericRecordRoundTripsThroughAvroSerdeWithBothOptionalsPresent() {
    Schema schema = PaymentChargeSucceededConsumerSchema.schema();
    GenericRecord record = new GenericData.Record(schema);
    record.put("charge_id", CHARGE_ID);
    record.put("company_id", COMPANY_ID);
    record.put("vertical", "carwash");
    record.put("payment_id", PAYMENT_ID);
    record.put("reference_id", TICKET_ID);
    record.put("business_id", BUSINESS_ID);
    record.put("amount_minor", 75_000L);
    record.put("currency", "IDR");
    record.put("provider", "MIDTRANS");
    record.put("provider_txn_id", "mt-txn-0001");
    record.put("succeeded_at", 1_750_000_000_000L);

    byte[] bytes = AvroSerde.serialize(record);
    GenericRecord decoded = AvroSerde.deserialize(bytes, schema);

    assertThat(decoded.get("vertical").toString()).isEqualTo("carwash");
    assertThat(decoded.get("reference_id").toString()).isEqualTo(TICKET_ID);
    assertThat(decoded.get("provider_txn_id").toString()).isEqualTo("mt-txn-0001");
    assertThat(decoded.get("amount_minor")).isEqualTo(75_000L);
  }

  @Test
  void consumerCopyIsBackwardCompatibleWithProducerSchema() {
    Schema producer = new Schema.Parser().parse(PRODUCER_SCHEMA_JSON);
    Schema consumer = PaymentChargeSucceededConsumerSchema.schema();
    assertThat(AvroSerde.isBackwardCompatible(producer, consumer)).isTrue();
  }

  @Test
  void producerBytesDecodeUnderConsumerSchema() {
    Schema producer = new Schema.Parser().parse(PRODUCER_SCHEMA_JSON);
    Schema consumer = PaymentChargeSucceededConsumerSchema.schema();

    GenericRecord produced = new GenericData.Record(producer);
    produced.put("charge_id", CHARGE_ID);
    produced.put("company_id", COMPANY_ID);
    produced.put("vertical", "restaurant");
    produced.put("payment_id", PAYMENT_ID);
    produced.put("reference_id", null);
    produced.put("business_id", BUSINESS_ID);
    produced.put("amount_minor", 185_000L);
    produced.put("currency", "IDR");
    produced.put("provider", "MIDTRANS");
    produced.put("provider_txn_id", null);
    produced.put("succeeded_at", 1_750_000_000_000L);

    byte[] wire = AvroSerde.serialize(produced);
    GenericRecord decoded = AvroSerde.deserialize(wire, producer, consumer);
    assertThat(decoded.get("vertical").toString()).isEqualTo("restaurant");
    assertThat(decoded.get("reference_id")).isNull();
    assertThat(decoded.get("provider_txn_id")).isNull();
    assertThat(decoded.get("amount_minor")).isEqualTo(185_000L);
  }

  @Test
  void decodeProducesTheCorrectEventWithBothOptionalsPresent() {
    Schema schema = PaymentChargeSucceededConsumerSchema.schema();
    GenericRecord record = new GenericData.Record(schema);
    record.put("charge_id", CHARGE_ID);
    record.put("company_id", COMPANY_ID);
    record.put("vertical", "carwash");
    record.put("payment_id", PAYMENT_ID);
    record.put("reference_id", TICKET_ID);
    record.put("business_id", BUSINESS_ID);
    record.put("amount_minor", 75_000L);
    record.put("currency", "IDR");
    record.put("provider", "MIDTRANS");
    record.put("provider_txn_id", "mt-txn-0001");
    record.put("succeeded_at", 1_750_000_000_000L);

    byte[] bytes = AvroSerde.serialize(record);
    UUID eventId = UUID.randomUUID();
    PaymentChargeSucceededEvent event = PaymentChargeSucceededConsumerSchema.decode(eventId, bytes);

    assertThat(event.eventId()).isEqualTo(eventId);
    assertThat(event.chargeId()).isEqualTo(UUID.fromString(CHARGE_ID));
    assertThat(event.companyId()).isEqualTo(COMPANY_ID);
    assertThat(event.vertical()).isEqualTo("carwash");
    assertThat(event.paymentId()).isEqualTo(UUID.fromString(PAYMENT_ID));
    assertThat(event.referenceId()).isEqualTo(UUID.fromString(TICKET_ID));
    assertThat(event.businessId()).isEqualTo(UUID.fromString(BUSINESS_ID));
    assertThat(event.amountMinor()).isEqualTo(75_000L);
    assertThat(event.currency()).isEqualTo("IDR");
    assertThat(event.provider()).isEqualTo("MIDTRANS");
    assertThat(event.providerTxnId()).isEqualTo("mt-txn-0001");
  }

  @Test
  void decodeProducesTheCorrectEventWithBothOptionalsNull() {
    // The restaurant shape: reference_id is always null (payment_id IS the capture key), and a
    // provider may omit provider_txn_id.
    Schema schema = PaymentChargeSucceededConsumerSchema.schema();
    GenericRecord record = new GenericData.Record(schema);
    record.put("charge_id", CHARGE_ID);
    record.put("company_id", COMPANY_ID);
    record.put("vertical", "restaurant");
    record.put("payment_id", PAYMENT_ID);
    record.put("reference_id", null);
    record.put("business_id", BUSINESS_ID);
    record.put("amount_minor", 185_000L);
    record.put("currency", "IDR");
    record.put("provider", "MIDTRANS");
    record.put("provider_txn_id", null);
    record.put("succeeded_at", 1_750_000_000_000L);

    byte[] bytes = AvroSerde.serialize(record);
    UUID eventId = UUID.randomUUID();
    PaymentChargeSucceededEvent event = PaymentChargeSucceededConsumerSchema.decode(eventId, bytes);

    assertThat(event.vertical()).isEqualTo("restaurant");
    assertThat(event.referenceId()).isNull();
    assertThat(event.providerTxnId()).isNull();
    assertThat(event.amountMinor()).isEqualTo(185_000L);
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
                  "name": "PaymentChargeSucceeded",
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
                    {"name": "provider", "type": "string"},
                    {"name": "provider_txn_id", "type": ["null", "string"], "default": null},
                    {"name": "succeeded_at", "type": {"type": "long", "logicalType": "timestamp-millis"}},
                    {"name": "settled_by", "type": "string"}
                  ]
                }
                """);
    assertThat(AvroSerde.isBackwardCompatible(producer, broken)).isFalse();
  }
}
