package id.co.nativeapp.entitlement;

import id.co.nativeapp.entitlement.entitlement.CompanyCreatedListener;
import id.co.nativeapp.entitlement.entitlement.CompanyCreatedSchema;
import id.co.nativeapp.events.AvroSerde;
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
 * Test fixtures for publishing a {@code CompanyCreated} the way the producer + Debezium would: raw
 * Avro bytes (built via {@code libs/events AvroSerde}) as the message value, the company id as the
 * key, and the durable event id stamped into the {@code id} header (the Debezium outbox-router
 * event-id header the {@link CompanyCreatedListener} dedupes on). NOT a Schema Registry serde —
 * exactly the transport entitlement-service consumes in production.
 */
final class CompanyCreatedFixtures {

  private CompanyCreatedFixtures() {}

  /** Builds a {@code CompanyCreated} {@link GenericRecord} against the consumer-copy schema. */
  static GenericRecord record(String companyId, String baseCurrency, String defaultLanguage) {
    GenericRecord record = new GenericData.Record(CompanyCreatedSchema.schema());
    record.put("company_id", companyId);
    record.put("legal_employer_id", companyId);
    record.put("base_currency", baseCurrency);
    record.put("default_language", defaultLanguage);
    return record;
  }

  /**
   * Publishes the record to the {@code CompanyCreated} topic with the given key and event id. The
   * event id goes into the {@code id} header so the consumer dedupes by the durable id (not
   * offset), and the value is raw Avro bytes.
   */
  static void publish(String bootstrapServers, String key, UUID eventId, GenericRecord event) {
    try (KafkaProducer<String, byte[]> producer =
        new KafkaProducer<>(producerConfig(bootstrapServers))) {
      ProducerRecord<String, byte[]> record =
          new ProducerRecord<>(CompanyCreatedSchema.TOPIC, key, AvroSerde.serialize(event));
      record
          .headers()
          .add(
              CompanyCreatedListener.EVENT_ID_HEADER,
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
