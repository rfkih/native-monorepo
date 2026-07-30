package id.co.nativeapp.loyalty;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.events.AvroSerde;
import id.co.nativeapp.loyalty.ingest.dto.SaleReversalFact;
import id.co.nativeapp.loyalty.ingest.messaging.SaleReversalConsumerSchema;
import java.time.Instant;
import java.util.UUID;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.junit.jupiter.api.Test;

/**
 * The {@code SaleVoided} / {@code SaleRefunded} CONSUMER-side contract test (rule 7, HR-7 triad) —
 * both decode into the unified {@link SaleReversalFact}.
 */
class SaleReversalConsumerContractTest {

  private static final Schema PRODUCER_VOIDED =
      new Schema.Parser()
          .parse(
              """
              {
                "type": "record",
                "name": "SaleVoided",
                "namespace": "id.co.nativeapp.events.restaurant",
                "fields": [
                  {"name": "void_id", "type": "string"},
                  {"name": "sale_id", "type": "string"},
                  {"name": "payment_id", "type": "string"},
                  {"name": "company_id", "type": "string"},
                  {"name": "business_id", "type": "string"},
                  {"name": "amount_minor", "type": "long"},
                  {"name": "currency", "type": "string"},
                  {"name": "occurred_at", "type": {"type": "long", "logicalType": "timestamp-millis"}},
                  {"name": "tender_type", "type": ["null", "string"], "default": null}
                ]
              }
              """);

  private static final Schema PRODUCER_REFUNDED =
      new Schema.Parser()
          .parse(
              """
              {
                "type": "record",
                "name": "SaleRefunded",
                "namespace": "id.co.nativeapp.events.restaurant",
                "fields": [
                  {"name": "refund_id", "type": "string"},
                  {"name": "sale_id", "type": "string"},
                  {"name": "payment_id", "type": "string"},
                  {"name": "company_id", "type": "string"},
                  {"name": "business_id", "type": "string"},
                  {"name": "refund_amount_minor", "type": "long"},
                  {"name": "currency", "type": "string"},
                  {"name": "total_refunded_minor", "type": "long"},
                  {"name": "occurred_at", "type": {"type": "long", "logicalType": "timestamp-millis"}},
                  {"name": "tender_type", "type": ["null", "string"], "default": null}
                ]
              }
              """);

  @Test
  void voidedConsumerCopyParsesAndMatchesTheProducer() {
    Schema schema = SaleReversalConsumerSchema.voidedSchema();
    assertThat(schema.getFullName()).isEqualTo("id.co.nativeapp.events.restaurant.SaleVoided");
    assertThat(AvroSerde.isBackwardCompatible(PRODUCER_VOIDED, schema)).isTrue();
    assertThat(AvroSerde.isBackwardCompatible(schema, PRODUCER_VOIDED)).isTrue();
  }

  @Test
  void refundedConsumerCopyParsesAndMatchesTheProducer() {
    Schema schema = SaleReversalConsumerSchema.refundedSchema();
    assertThat(schema.getFullName()).isEqualTo("id.co.nativeapp.events.restaurant.SaleRefunded");
    assertThat(AvroSerde.isBackwardCompatible(PRODUCER_REFUNDED, schema)).isTrue();
    assertThat(AvroSerde.isBackwardCompatible(schema, PRODUCER_REFUNDED)).isTrue();
  }

  @Test
  void decodesAVoidedRecordAsVoidedKind() {
    Schema schema = SaleReversalConsumerSchema.voidedSchema();
    UUID saleId = UUID.randomUUID();
    UUID businessId = UUID.randomUUID();
    UUID eventId = UUID.randomUUID();

    GenericRecord record = new GenericData.Record(schema);
    record.put("void_id", UUID.randomUUID().toString());
    record.put("sale_id", saleId.toString());
    record.put("payment_id", UUID.randomUUID().toString());
    record.put("company_id", "11111111-1111-1111-1111-111111111111");
    record.put("business_id", businessId.toString());
    record.put("amount_minor", 10_000L);
    record.put("currency", "IDR");
    record.put("occurred_at", Instant.now().toEpochMilli());
    record.put("tender_type", "CASH");

    byte[] bytes = AvroSerde.serialize(record);
    SaleReversalFact fact = SaleReversalConsumerSchema.decodeVoided(eventId, bytes);

    assertThat(fact.eventId()).isEqualTo(eventId);
    assertThat(fact.kind()).isEqualTo(SaleReversalFact.Kind.VOIDED);
    assertThat(fact.saleId()).isEqualTo(saleId);
    assertThat(fact.businessId()).isEqualTo(businessId);
  }

  @Test
  void decodesARefundedRecordAsRefundedKind() {
    Schema schema = SaleReversalConsumerSchema.refundedSchema();
    UUID saleId = UUID.randomUUID();
    UUID businessId = UUID.randomUUID();
    UUID eventId = UUID.randomUUID();

    GenericRecord record = new GenericData.Record(schema);
    record.put("refund_id", UUID.randomUUID().toString());
    record.put("sale_id", saleId.toString());
    record.put("payment_id", UUID.randomUUID().toString());
    record.put("company_id", "11111111-1111-1111-1111-111111111111");
    record.put("business_id", businessId.toString());
    record.put("refund_amount_minor", 5_000L);
    record.put("currency", "IDR");
    record.put("total_refunded_minor", 5_000L);
    record.put("occurred_at", Instant.now().toEpochMilli());
    record.put("tender_type", "CASH");

    byte[] bytes = AvroSerde.serialize(record);
    SaleReversalFact fact = SaleReversalConsumerSchema.decodeRefunded(eventId, bytes);

    assertThat(fact.eventId()).isEqualTo(eventId);
    assertThat(fact.kind()).isEqualTo(SaleReversalFact.Kind.REFUNDED);
    assertThat(fact.saleId()).isEqualTo(saleId);
  }
}
