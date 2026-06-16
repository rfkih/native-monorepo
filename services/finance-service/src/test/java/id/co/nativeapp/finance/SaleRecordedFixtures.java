package id.co.nativeapp.finance;

import id.co.nativeapp.events.AvroSerde;
import id.co.nativeapp.events.Base64ByteArraySerializer;
import id.co.nativeapp.finance.revenue.messaging.SaleRecordedListener;
import id.co.nativeapp.finance.revenue.messaging.SaleRecordedSchema;
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
 * Test fixtures for publishing a {@code SaleRecorded} the way the producer + Debezium would: the
 * raw Avro bytes (built via {@code libs/events AvroSerde}) base64-encoded onto the wire by a {@link
 * Base64ByteArraySerializer} (the exact encoding Debezium emits with {@code
 * binary.handling.mode=base64} + a {@code StringConverter} — see {@code docker/README.md}), the
 * sale id as the key, and the durable event id stamped into the {@code id} header (the Debezium
 * outbox-router event-id header the {@link SaleRecordedListener} dedupes on). NOT a Schema Registry
 * serde — exactly the transport finance consumes in production.
 */
final class SaleRecordedFixtures {

  private SaleRecordedFixtures() {}

  /** Builds a {@code SaleRecorded} {@link GenericRecord} against finance's consumer-copy schema. */
  static GenericRecord record(
      UUID saleId,
      String companyId,
      UUID businessId,
      long amountMinor,
      String currency,
      Instant occurredAt) {
    GenericRecord record = new GenericData.Record(SaleRecordedSchema.schema());
    record.put("sale_id", saleId.toString());
    record.put("company_id", companyId);
    record.put("business_id", businessId.toString());
    record.put("amount_minor", amountMinor);
    record.put("currency", currency);
    record.put("occurred_at", occurredAt.toEpochMilli());
    return record;
  }

  /**
   * Publishes the record to the {@code SaleRecorded} topic with the given key and event id. The
   * event id goes into the {@code id} header so the consumer dedupes by the durable id (not
   * offset), and the value is raw Avro bytes.
   */
  static void publish(String bootstrapServers, String key, UUID eventId, GenericRecord event) {
    publishBytes(bootstrapServers, key, eventId, AvroSerde.serialize(event));
  }

  /**
   * Publishes raw {@code value} bytes with the given {@code id} header. Used to inject a poison
   * payload (garbage that is not a valid {@code SaleRecorded} Avro record) onto the topic — the
   * valid {@code id} header makes the failure specifically the Avro DECODE, not a missing header.
   */
  static void publishBytes(String bootstrapServers, String key, UUID eventId, byte[] value) {
    try (KafkaProducer<String, byte[]> producer =
        new KafkaProducer<>(producerConfig(bootstrapServers))) {
      ProducerRecord<String, byte[]> record =
          new ProducerRecord<>(SaleRecordedSchema.TOPIC, key, value);
      record
          .headers()
          .add(
              SaleRecordedListener.EVENT_ID_HEADER,
              eventId.toString().getBytes(StandardCharsets.UTF_8));
      producer.send(record);
      producer.flush();
    }
  }

  /**
   * Publishes a valid {@code SaleRecorded} Avro value WITHOUT the {@code id} header — simulating a
   * misconfigured producer. The consumer must fail closed and DLT it (never synthesise a
   * non-durable id), so no ledger posting is written.
   */
  static void publishWithoutEventIdHeader(
      String bootstrapServers, String key, GenericRecord event) {
    byte[] payload = AvroSerde.serialize(event);
    try (KafkaProducer<String, byte[]> producer =
        new KafkaProducer<>(producerConfig(bootstrapServers))) {
      ProducerRecord<String, byte[]> record =
          new ProducerRecord<>(SaleRecordedSchema.TOPIC, key, payload);
      // Deliberately no EVENT_ID_HEADER stamped.
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
