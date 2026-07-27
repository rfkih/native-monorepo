package id.co.nativeapp.finance;

import id.co.nativeapp.events.AvroSerde;
import id.co.nativeapp.events.Base64ByteArraySerializer;
import id.co.nativeapp.finance.orgref.messaging.OrgUnitRefSchemas;
import id.co.nativeapp.finance.revenue.messaging.SaleRecordedListener;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;

/**
 * Test fixtures for publishing the org-service org-unit events the way the producer + Debezium
 * would: raw Avro bytes (built via finance's consumer-view schema + {@code libs/events AvroSerde})
 * as the message value, the org-unit id as the key, and the durable event id stamped into the
 * {@code id} header (the Debezium outbox-router event-id header the consumer dedupes on). NOT a
 * Schema Registry serde — exactly the transport finance consumes in production.
 */
final class OrgUnitRefEventFixtures {

  private OrgUnitRefEventFixtures() {}

  static GenericRecord orgUnitCreated(
      UUID orgUnitId, UUID companyId, String type, UUID parentId, String name) {
    GenericRecord record = new GenericData.Record(OrgUnitRefSchemas.createdSchema());
    record.put("org_unit_id", orgUnitId.toString());
    record.put("company_id", companyId.toString());
    record.put("type", type);
    record.put("parent_id", parentId != null ? parentId.toString() : null);
    record.put("legal_employer_id", companyId.toString()); // co-located for tests
    record.put("name", name);
    return record;
  }

  static GenericRecord orgUnitChanged(
      UUID orgUnitId,
      UUID companyId,
      String type,
      UUID parentId,
      String changeKind,
      String name,
      boolean active) {
    GenericRecord record = new GenericData.Record(OrgUnitRefSchemas.changedSchema());
    record.put("org_unit_id", orgUnitId.toString());
    record.put("company_id", companyId.toString());
    record.put("type", type);
    record.put("parent_id", parentId != null ? parentId.toString() : null);
    record.put("change_kind", changeKind);
    record.put("name", name);
    record.put("active", active);
    return record;
  }

  static void publishCreated(
      String bootstrapServers, UUID orgUnitId, UUID eventId, GenericRecord event) {
    publish(
        bootstrapServers, OrgUnitRefSchemas.CREATED_TOPIC, orgUnitId.toString(), eventId, event);
  }

  static void publishChanged(
      String bootstrapServers, UUID orgUnitId, UUID eventId, GenericRecord event) {
    publish(
        bootstrapServers, OrgUnitRefSchemas.CHANGED_TOPIC, orgUnitId.toString(), eventId, event);
  }

  private static void publish(
      String bootstrapServers, String topic, String key, UUID eventId, GenericRecord event) {
    byte[] value = AvroSerde.serialize(event);
    try (KafkaProducer<String, byte[]> producer =
        new KafkaProducer<>(producerConfig(bootstrapServers))) {
      ProducerRecord<String, byte[]> record = new ProducerRecord<>(topic, key, value);
      record
          .headers()
          .add(
              SaleRecordedListener.EVENT_ID_HEADER,
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
