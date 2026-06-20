package id.co.nativeapp.finance;

import id.co.nativeapp.events.AvroSerde;
import id.co.nativeapp.events.Base64ByteArraySerializer;
import id.co.nativeapp.finance.reversal.messaging.SaleRefundedListener;
import id.co.nativeapp.finance.reversal.messaging.SaleRefundedSchema;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;

/**
 * Test fixtures for publishing a {@code SaleRefunded} the way the producer + Debezium would: raw
 * Avro bytes (built via {@code libs/events AvroSerde}) base64-encoded onto the wire by a {@link
 * Base64ByteArraySerializer}, the refund id as the key, and the durable event id stamped into the
 * {@code id} header (the Debezium outbox-router event-id header the {@link SaleRefundedListener}
 * dedupes on). NOT a Schema Registry serde — exactly the transport finance consumes in production.
 */
final class SaleRefundedFixtures {

  private SaleRefundedFixtures() {}

  /** Builds a {@code SaleRefunded} {@link GenericRecord} against finance's consumer-copy schema. */
  static GenericRecord record(
      UUID refundId,
      UUID saleId,
      UUID paymentId,
      String companyId,
      UUID businessId,
      long refundAmountMinor,
      long totalRefundedMinor,
      String currency,
      Instant occurredAt,
      String tenderType) {
    GenericRecord record = new GenericData.Record(SaleRefundedSchema.schema());
    record.put("refund_id", refundId.toString());
    record.put("sale_id", saleId.toString());
    record.put("payment_id", paymentId.toString());
    record.put("company_id", companyId);
    record.put("business_id", businessId.toString());
    record.put("refund_amount_minor", refundAmountMinor);
    record.put("currency", currency);
    record.put("total_refunded_minor", totalRefundedMinor);
    record.put("occurred_at", occurredAt.toEpochMilli());
    record.put("tender_type", tenderType);
    return record;
  }

  /**
   * Publishes the record to the {@code SaleRefunded} topic with the given key and event id. The
   * event id goes into the {@code id} header so the consumer dedupes by the durable id (not
   * offset), and the value is raw Avro bytes base64-encoded (the Debezium transport encoding).
   */
  static void publish(String bootstrapServers, String key, UUID eventId, GenericRecord event) {
    byte[] payload = AvroSerde.serialize(event);
    try (KafkaProducer<String, byte[]> producer =
        new KafkaProducer<>(producerConfig(bootstrapServers))) {
      ProducerRecord<String, byte[]> record =
          new ProducerRecord<>(SaleRefundedSchema.TOPIC, key, payload);
      record
          .headers()
          .add(
              SaleRefundedListener.EVENT_ID_HEADER,
              eventId.toString().getBytes(StandardCharsets.UTF_8));
      producer.send(record);
      producer.flush();
    }
  }

  private static Map<String, Object> producerConfig(String bootstrapServers) {
    return Map.of(
        ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,
        bootstrapServers,
        ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,
        StringSerializer.class,
        ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
        Base64ByteArraySerializer.class,
        ProducerConfig.ACKS_CONFIG,
        "all");
  }
}
