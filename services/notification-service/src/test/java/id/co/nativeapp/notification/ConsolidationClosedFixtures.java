package id.co.nativeapp.notification;

import id.co.nativeapp.events.AvroSerde;
import id.co.nativeapp.notification.notification.messaging.ConsolidationClosedListener;
import id.co.nativeapp.notification.notification.messaging.ConsolidationClosedSchema;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;

/**
 * Test fixtures for publishing a {@code ConsolidationClosed} the way finance + Debezium would: raw
 * Avro bytes (built via {@code libs/events AvroSerde}) as the message value, the company id as the
 * key, and the durable event id stamped into the {@code id} header (the Debezium outbox-router
 * event-id header the {@link ConsolidationClosedListener} dedupes on). NOT a Schema Registry serde
 * — exactly the transport notification-service consumes in production.
 */
final class ConsolidationClosedFixtures {

  private ConsolidationClosedFixtures() {}

  /** Builds a company-level {@code ConsolidationClosed} against notification's consumer schema. */
  static GenericRecord record(String companyId, String period) {
    return record(companyId, null, period);
  }

  /** Builds a {@code ConsolidationClosed} with an optional group id. */
  static GenericRecord record(String companyId, String groupId, String period) {
    GenericRecord record = new GenericData.Record(ConsolidationClosedSchema.schema());
    record.put("company_id", companyId);
    record.put("group_id", groupId);
    record.put("period", period);
    return record;
  }

  /**
   * Publishes the record to the {@code ConsolidationClosed} topic with the given key and event id.
   * The event id goes into the {@code id} header so the consumer dedupes by the durable id (not
   * offset), and the value is raw Avro bytes.
   */
  static void publish(String bootstrapServers, String key, UUID eventId, GenericRecord event) {
    publishBytes(bootstrapServers, key, eventId, AvroSerde.serialize(event));
  }

  /**
   * Publishes raw {@code value} bytes with the given {@code id} header. Used to inject a poison
   * payload (garbage that is not a valid {@code ConsolidationClosed} Avro record) onto the topic —
   * the valid {@code id} header makes the failure specifically the Avro DECODE, not a missing
   * header.
   */
  static void publishBytes(String bootstrapServers, String key, UUID eventId, byte[] value) {
    try (KafkaProducer<String, byte[]> producer =
        new KafkaProducer<>(producerConfig(bootstrapServers))) {
      ProducerRecord<String, byte[]> record =
          new ProducerRecord<>(ConsolidationClosedSchema.TOPIC, key, value);
      record
          .headers()
          .add(
              ConsolidationClosedListener.EVENT_ID_HEADER,
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
        ByteArraySerializer.class,
        ProducerConfig.ACKS_CONFIG,
        "all");
  }
}
