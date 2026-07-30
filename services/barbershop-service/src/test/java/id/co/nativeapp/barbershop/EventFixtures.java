package id.co.nativeapp.barbershop;

import id.co.nativeapp.barbershop.entitlement.messaging.EntitlementEventListener;
import id.co.nativeapp.barbershop.entitlement.messaging.EntitlementEventSchemas;
import id.co.nativeapp.barbershop.loyaltyref.messaging.GiftCardStateChangedConsumerSchema;
import id.co.nativeapp.barbershop.loyaltyref.messaging.GiftCardStateChangedListener;
import id.co.nativeapp.barbershop.loyaltyref.messaging.LoyaltyBalanceChangedConsumerSchema;
import id.co.nativeapp.barbershop.loyaltyref.messaging.LoyaltyBalanceChangedListener;
import id.co.nativeapp.barbershop.staff.messaging.StaffEventListener;
import id.co.nativeapp.barbershop.staff.messaging.StaffEventSchemas;
import id.co.nativeapp.events.AvroSerde;
import id.co.nativeapp.events.Base64ByteArraySerializer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;

/**
 * Test fixtures for publishing the events barbershop-service consumes the way the producer +
 * Debezium would: raw Avro bytes (built via {@code libs/events AvroSerde}) as the message value,
 * the partition key, and the durable event id stamped into the {@code id} header (the Debezium
 * outbox-router event-id header the listeners dedupe on). NOT a Schema Registry serde — exactly the
 * transport barbershop-service consumes in production.
 */
final class EventFixtures {

  private EventFixtures() {}

  // ---- EntitlementGranted / EntitlementRevoked (against the consumer-copy schemas) ----

  static GenericRecord entitlementGranted(String companyId, String moduleKey) {
    GenericRecord record = new GenericData.Record(EntitlementEventSchemas.grantedSchema());
    record.put("company_id", companyId);
    record.put("module_key", moduleKey);
    record.put("granted_at", Instant.parse("2026-06-14T00:00:00Z").toEpochMilli());
    return record;
  }

  static GenericRecord entitlementRevoked(String companyId, String moduleKey) {
    GenericRecord record = new GenericData.Record(EntitlementEventSchemas.revokedSchema());
    record.put("company_id", companyId);
    record.put("module_key", moduleKey);
    record.put("revoked_at", Instant.parse("2026-06-14T01:00:00Z").toEpochMilli());
    return record;
  }

  static void publishEntitlementGranted(
      String bootstrapServers, String companyId, UUID eventId, GenericRecord event) {
    publish(
        bootstrapServers,
        EntitlementEventSchemas.GRANTED_TOPIC,
        companyId,
        eventId,
        AvroSerde.serialize(event),
        EntitlementEventListener.EVENT_ID_HEADER);
  }

  static void publishEntitlementRevoked(
      String bootstrapServers, String companyId, UUID eventId, GenericRecord event) {
    publish(
        bootstrapServers,
        EntitlementEventSchemas.REVOKED_TOPIC,
        companyId,
        eventId,
        AvroSerde.serialize(event),
        EntitlementEventListener.EVENT_ID_HEADER);
  }

  // ---- EmployeeChanged / AssignmentChanged (against the consumer-copy schemas) ----

  static GenericRecord employeeChanged(String employeeId, String companyId, String status) {
    GenericRecord record = new GenericData.Record(StaffEventSchemas.employeeSchema());
    record.put("employee_id", employeeId);
    record.put("company_id", companyId);
    record.put("status", status);
    return record;
  }

  static GenericRecord assignmentChanged(
      String assignmentId, String employeeId, String companyId, String orgUnitId, String role) {
    GenericRecord record = new GenericData.Record(StaffEventSchemas.assignmentSchema());
    record.put("assignment_id", assignmentId);
    record.put("employee_id", employeeId);
    record.put("company_id", companyId);
    record.put("org_unit_id", orgUnitId);
    record.put("reporting_to", null);
    record.put("role", role);
    record.put("effective_from", (int) LocalDate.parse("2026-06-01").toEpochDay());
    record.put("effective_to", (int) LocalDate.parse("9999-12-31").toEpochDay());
    return record;
  }

  static void publishEmployeeChanged(
      String bootstrapServers, String employeeId, UUID eventId, GenericRecord event) {
    publish(
        bootstrapServers,
        StaffEventSchemas.EMPLOYEE_TOPIC,
        employeeId,
        eventId,
        AvroSerde.serialize(event),
        StaffEventListener.EVENT_ID_HEADER);
  }

  static void publishAssignmentChanged(
      String bootstrapServers, String employeeId, UUID eventId, GenericRecord event) {
    publish(
        bootstrapServers,
        StaffEventSchemas.ASSIGNMENT_TOPIC,
        employeeId,
        eventId,
        AvroSerde.serialize(event),
        StaffEventListener.EVENT_ID_HEADER);
  }

  // ---- LoyaltyBalanceChanged / GiftCardStateChanged (ADR 0027, against the consumer-copy
  // schemas) — published by loyalty-service in production; these fixtures publish the identical
  // wire shape (raw Avro bytes + the durable "id" header) so the real @KafkaListener consumes them
  // exactly as it would in production. ----

  static GenericRecord loyaltyBalanceChanged(
      String memberId, String companyId, long pointsBalance, long balanceSeq, String reason) {
    GenericRecord record = new GenericData.Record(LoyaltyBalanceChangedConsumerSchema.schema());
    record.put("member_id", memberId);
    record.put("company_id", companyId);
    record.put("points_balance", pointsBalance);
    record.put("balance_seq", balanceSeq);
    record.put("reason", reason);
    record.put("occurred_at", Instant.parse("2026-07-31T00:00:00Z").toEpochMilli());
    return record;
  }

  static GenericRecord giftCardStateChanged(
      String giftCardId,
      String companyId,
      String state,
      long balanceMinor,
      String currency,
      long balanceSeq) {
    GenericRecord record = new GenericData.Record(GiftCardStateChangedConsumerSchema.schema());
    record.put("gift_card_id", giftCardId);
    record.put("company_id", companyId);
    record.put("state", state);
    record.put("balance_minor", balanceMinor);
    record.put("currency", currency);
    record.put("balance_seq", balanceSeq);
    record.put("occurred_at", Instant.parse("2026-07-31T00:00:00Z").toEpochMilli());
    return record;
  }

  static void publishLoyaltyBalanceChanged(
      String bootstrapServers, String memberId, UUID eventId, GenericRecord event) {
    publish(
        bootstrapServers,
        LoyaltyBalanceChangedConsumerSchema.TOPIC,
        memberId,
        eventId,
        AvroSerde.serialize(event),
        LoyaltyBalanceChangedListener.EVENT_ID_HEADER);
  }

  static void publishGiftCardStateChanged(
      String bootstrapServers, String giftCardId, UUID eventId, GenericRecord event) {
    publish(
        bootstrapServers,
        GiftCardStateChangedConsumerSchema.TOPIC,
        giftCardId,
        eventId,
        AvroSerde.serialize(event),
        GiftCardStateChangedListener.EVENT_ID_HEADER);
  }

  private static void publish(
      String bootstrapServers,
      String topic,
      String key,
      UUID eventId,
      byte[] value,
      String eventIdHeader) {
    try (KafkaProducer<String, byte[]> producer =
        new KafkaProducer<>(producerConfig(bootstrapServers))) {
      ProducerRecord<String, byte[]> record = new ProducerRecord<>(topic, key, value);
      record.headers().add(eventIdHeader, eventId.toString().getBytes(StandardCharsets.UTF_8));
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
