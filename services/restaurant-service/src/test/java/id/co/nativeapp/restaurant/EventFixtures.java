package id.co.nativeapp.restaurant;

import id.co.nativeapp.events.AvroSerde;
import id.co.nativeapp.events.Base64ByteArraySerializer;
import id.co.nativeapp.restaurant.entitlement.messaging.EntitlementEventListener;
import id.co.nativeapp.restaurant.entitlement.messaging.EntitlementEventSchemas;
import id.co.nativeapp.restaurant.payment.messaging.PaymentChargeSucceededConsumerSchema;
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
 * Test fixtures for publishing {@code EntitlementGranted}/{@code EntitlementRevoked} the way
 * entitlement-service + Debezium would: raw Avro bytes (built via {@code libs/events AvroSerde}) as
 * the message value, the partition key, and the durable event id stamped into the {@code id} header
 * (the Debezium outbox-router event-id header {@link EntitlementEventListener} dedupes on). NOT a
 * Schema Registry serde — exactly the transport this service consumes in production. Mirrors
 * barbershop-service's/carwash-service's {@code EventFixtures} exactly.
 */
public final class EventFixtures {

  private EventFixtures() {}

  public static GenericRecord entitlementGranted(String companyId, String moduleKey) {
    GenericRecord record = new GenericData.Record(EntitlementEventSchemas.grantedSchema());
    record.put("company_id", companyId);
    record.put("module_key", moduleKey);
    record.put("granted_at", Instant.parse("2026-07-31T00:00:00Z").toEpochMilli());
    return record;
  }

  public static GenericRecord entitlementRevoked(String companyId, String moduleKey) {
    GenericRecord record = new GenericData.Record(EntitlementEventSchemas.revokedSchema());
    record.put("company_id", companyId);
    record.put("module_key", moduleKey);
    record.put("revoked_at", Instant.parse("2026-07-31T01:00:00Z").toEpochMilli());
    return record;
  }

  public static void publishEntitlementGranted(
      String bootstrapServers, String companyId, UUID eventId, GenericRecord event) {
    publish(
        bootstrapServers,
        EntitlementEventSchemas.GRANTED_TOPIC,
        companyId,
        eventId,
        AvroSerde.serialize(event));
  }

  public static void publishEntitlementRevoked(
      String bootstrapServers, String companyId, UUID eventId, GenericRecord event) {
    publish(
        bootstrapServers,
        EntitlementEventSchemas.REVOKED_TOPIC,
        companyId,
        eventId,
        AvroSerde.serialize(event));
  }

  /**
   * Builds a {@code PaymentChargeSucceeded} record (ADR 0045) the way payment-service + Debezium
   * would emit it. {@code referenceId} is {@code null} for the restaurant shape (its capture key is
   * {@code paymentId}).
   */
  @SuppressWarnings("checkstyle:ParameterNumber")
  public static GenericRecord paymentChargeSucceeded(
      UUID chargeId,
      String companyId,
      String vertical,
      UUID paymentId,
      UUID businessId,
      long amountMinor,
      String currency) {
    GenericRecord record = new GenericData.Record(PaymentChargeSucceededConsumerSchema.schema());
    record.put("charge_id", chargeId.toString());
    record.put("company_id", companyId);
    record.put("vertical", vertical);
    record.put("payment_id", paymentId.toString());
    record.put("reference_id", null);
    record.put("business_id", businessId.toString());
    record.put("amount_minor", amountMinor);
    record.put("currency", currency);
    record.put("provider", "MIDTRANS");
    record.put("provider_txn_id", "mt-txn-test");
    record.put("succeeded_at", Instant.now().toEpochMilli());
    return record;
  }

  public static void publishPaymentChargeSucceeded(
      String bootstrapServers, String companyId, UUID eventId, GenericRecord event) {
    publish(
        bootstrapServers,
        PaymentChargeSucceededConsumerSchema.TOPIC,
        companyId,
        eventId,
        AvroSerde.serialize(event));
  }

  private static void publish(
      String bootstrapServers, String topic, String key, UUID eventId, byte[] value) {
    try (KafkaProducer<String, byte[]> producer =
        new KafkaProducer<>(producerConfig(bootstrapServers))) {
      ProducerRecord<String, byte[]> record = new ProducerRecord<>(topic, key, value);
      record
          .headers()
          .add(
              EntitlementEventListener.EVENT_ID_HEADER,
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
