package id.co.nativeapp.employee;

import id.co.nativeapp.employee.org.OrgUnitEventListener;
import id.co.nativeapp.employee.org.OrgUnitEventSchemas;
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
 * Test fixtures for publishing {@code OrgUnitCreated} / {@code OrgUnitChanged} the way org-service
 * + Debezium would: raw Avro bytes (built via {@code libs/events AvroSerde}) as the message value,
 * the org_unit_id as the key, and the durable event id stamped into the {@code id} header (the
 * Debezium outbox-router event-id header the {@link OrgUnitEventListener} dedupes on). NOT a Schema
 * Registry serde — exactly the transport employee-service consumes in production.
 */
final class OrgUnitEventFixtures {

  private OrgUnitEventFixtures() {}

  /**
   * Builds an {@code OrgUnitCreated} {@link GenericRecord} against employee's consumer-copy schema.
   */
  static GenericRecord created(
      UUID orgUnitId, String companyId, UUID legalEmployerId, String type) {
    GenericRecord record = new GenericData.Record(OrgUnitEventSchemas.createdSchema());
    record.put("org_unit_id", orgUnitId.toString());
    record.put("company_id", companyId);
    record.put("type", type);
    record.put("parent_id", null);
    record.put("legal_employer_id", legalEmployerId.toString());
    record.put("name", "Test " + type);
    return record;
  }

  /** Builds an {@code OrgUnitChanged} {@link GenericRecord} (carries new type + active). */
  static GenericRecord changed(
      UUID orgUnitId, String companyId, String type, String changeKind, boolean active) {
    GenericRecord record = new GenericData.Record(OrgUnitEventSchemas.changedSchema());
    record.put("org_unit_id", orgUnitId.toString());
    record.put("company_id", companyId);
    record.put("type", type);
    record.put("parent_id", null);
    record.put("change_kind", changeKind);
    record.put("name", "Test " + type);
    record.put("active", active);
    return record;
  }

  /**
   * Publishes an {@code OrgUnitCreated} to its topic with the given key + event id (in the id
   * header).
   */
  static void publishCreated(
      String bootstrapServers, String key, UUID eventId, GenericRecord event) {
    publish(bootstrapServers, OrgUnitEventSchemas.CREATED_TOPIC, key, eventId, event);
  }

  /** Publishes an {@code OrgUnitChanged} to its topic with the given key + event id. */
  static void publishChanged(
      String bootstrapServers, String key, UUID eventId, GenericRecord event) {
    publish(bootstrapServers, OrgUnitEventSchemas.CHANGED_TOPIC, key, eventId, event);
  }

  private static void publish(
      String bootstrapServers, String topic, String key, UUID eventId, GenericRecord event) {
    byte[] payload = AvroSerde.serialize(event);
    try (KafkaProducer<String, byte[]> producer =
        new KafkaProducer<>(producerConfig(bootstrapServers))) {
      ProducerRecord<String, byte[]> record = new ProducerRecord<>(topic, key, payload);
      record
          .headers()
          .add(
              OrgUnitEventListener.EVENT_ID_HEADER,
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
