package id.co.nativeapp.finance;

import id.co.nativeapp.events.AvroSerde;
import id.co.nativeapp.finance.group.messaging.GroupDefinedSchema;
import id.co.nativeapp.finance.group.messaging.GroupMembershipChangedSchema;
import id.co.nativeapp.finance.revenue.messaging.SaleRecordedListener;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
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
 * Test fixtures for publishing the org-service group events the way the producer + Debezium would:
 * raw Avro bytes (built via finance's consumer-copy schema + {@code libs/events AvroSerde}) as the
 * message value, the group id as the key, and the durable event id stamped into the {@code id}
 * header (the Debezium outbox-router event-id header the consumer dedupes on). NOT a Schema
 * Registry serde — exactly the transport finance consumes in production.
 */
final class GroupEventFixtures {

  static final LocalDate OPEN_ENDED = LocalDate.of(9999, 12, 31);

  private GroupEventFixtures() {}

  static GenericRecord groupDefined(
      UUID groupId, UUID leadCompanyId, String reportingCurrency, String name) {
    GenericRecord record = new GenericData.Record(GroupDefinedSchema.schema());
    record.put("group_id", groupId.toString());
    record.put("lead_company_id", leadCompanyId.toString());
    record.put("reporting_currency", reportingCurrency);
    record.put("name", name);
    return record;
  }

  static GenericRecord membershipChanged(
      UUID groupId,
      UUID memberCompanyId,
      String changeKind,
      LocalDate effectiveFrom,
      LocalDate effectiveTo) {
    GenericRecord record = new GenericData.Record(GroupMembershipChangedSchema.schema());
    record.put("group_id", groupId.toString());
    record.put("member_company_id", memberCompanyId.toString());
    record.put("change_kind", changeKind);
    record.put("effective_from", (int) effectiveFrom.toEpochDay());
    record.put("effective_to", (int) effectiveTo.toEpochDay());
    return record;
  }

  static void publishGroupDefined(
      String bootstrapServers, UUID groupId, UUID eventId, GenericRecord event) {
    publish(bootstrapServers, GroupDefinedSchema.TOPIC, groupId.toString(), eventId, event);
  }

  static void publishMembershipChanged(
      String bootstrapServers, UUID groupId, UUID eventId, GenericRecord event) {
    publish(
        bootstrapServers, GroupMembershipChangedSchema.TOPIC, groupId.toString(), eventId, event);
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
        ByteArraySerializer.class,
        ProducerConfig.ACKS_CONFIG,
        "all");
  }
}
