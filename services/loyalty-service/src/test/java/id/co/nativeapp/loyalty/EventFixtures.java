package id.co.nativeapp.loyalty;

import id.co.nativeapp.events.AvroSerde;
import id.co.nativeapp.events.Base64ByteArraySerializer;
import id.co.nativeapp.loyalty.ingest.messaging.GiftCardSoldConsumerSchema;
import id.co.nativeapp.loyalty.ingest.messaging.SaleRecordedConsumerSchema;
import id.co.nativeapp.loyalty.ingest.messaging.SaleReversalConsumerSchema;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;

/**
 * Test fixtures for publishing the events loyalty-service consumes the way a vertical producer +
 * Debezium would: raw Avro bytes (built via {@code libs/events AvroSerde}) as the message value,
 * the partition key, and the durable event id stamped into the {@code id} header (the Debezium
 * outbox-router event-id header the listeners dedupe on). NOT a Schema Registry serde — exactly the
 * transport loyalty-service consumes in production. Mirrors barbershop-service's {@code
 * EventFixtures}.
 */
final class EventFixtures {

  private EventFixtures() {}

  // ---- SaleRecorded (against the shared libs/contracts schema) ----

  @SuppressWarnings("checkstyle:ParameterNumber")
  static GenericRecord saleRecorded(
      UUID saleId,
      String companyId,
      UUID businessId,
      long amountMinor,
      String currency,
      Long subtotalMinor,
      Long discountMinor,
      UUID loyaltyMemberId,
      Long loyaltyRedeemedPoints,
      Long loyaltyRedeemedMinor,
      UUID giftCardId,
      Long giftCardRedeemedMinor) {
    Schema schema = SaleRecordedConsumerSchema.schema();
    GenericRecord record = new GenericData.Record(schema);
    record.put("sale_id", saleId.toString());
    record.put("company_id", companyId);
    record.put("business_id", businessId.toString());
    record.put("amount_minor", amountMinor);
    record.put("currency", currency);
    record.put("occurred_at", Instant.now().toEpochMilli());
    record.put("tender_type", "CASH");
    record.put("subtotal_minor", subtotalMinor);
    record.put("discount_minor", discountMinor);
    record.put("service_charge_minor", 0L);
    record.put("tax_minor", 0L);
    record.put("tax_rule_version", null);
    record.put("uses_illustrative_rules", null);
    record.put("loyalty_member_id", loyaltyMemberId == null ? null : loyaltyMemberId.toString());
    record.put("loyalty_redeemed_points", loyaltyRedeemedPoints);
    record.put("loyalty_redeemed_minor", loyaltyRedeemedMinor);
    record.put("gift_card_id", giftCardId == null ? null : giftCardId.toString());
    record.put("gift_card_redeemed_minor", giftCardRedeemedMinor);
    // ADR 0036 (Phase B): no producer threads a real channel yet; explicit null (loyalty-service
    // does not read this field, so it stays absent from this fixture's parameter list).
    record.put("channel", null);
    return record;
  }

  static void publishSaleRecorded(String bootstrapServers, UUID eventId, GenericRecord event) {
    publish(
        bootstrapServers,
        SaleRecordedConsumerSchema.TOPIC,
        event.get("sale_id").toString(),
        eventId,
        AvroSerde.serialize(event));
  }

  // ---- GiftCardSold ----

  static GenericRecord giftCardSold(
      UUID giftCardId, String companyId, UUID businessId, long amountMinor, String currency) {
    Schema schema = GiftCardSoldConsumerSchema.schema();
    GenericRecord record = new GenericData.Record(schema);
    record.put("gift_card_sale_id", UUID.randomUUID().toString());
    record.put("gift_card_id", giftCardId.toString());
    record.put("company_id", companyId);
    record.put("business_id", businessId.toString());
    record.put("amount_minor", amountMinor);
    record.put("currency", currency);
    record.put("tender_type", "CASH");
    record.put("occurred_at", Instant.now().toEpochMilli());
    return record;
  }

  static void publishGiftCardSold(String bootstrapServers, UUID eventId, GenericRecord event) {
    publish(
        bootstrapServers,
        GiftCardSoldConsumerSchema.TOPIC,
        event.get("gift_card_id").toString(),
        eventId,
        AvroSerde.serialize(event));
  }

  // ---- SaleVoided / SaleRefunded ----

  static GenericRecord saleVoided(
      UUID saleId, String companyId, UUID businessId, long amountMinor, String currency) {
    Schema schema = SaleReversalConsumerSchema.voidedSchema();
    GenericRecord record = new GenericData.Record(schema);
    record.put("void_id", UUID.randomUUID().toString());
    record.put("sale_id", saleId.toString());
    record.put("payment_id", UUID.randomUUID().toString());
    record.put("company_id", companyId);
    record.put("business_id", businessId.toString());
    record.put("amount_minor", amountMinor);
    record.put("currency", currency);
    record.put("occurred_at", Instant.now().toEpochMilli());
    record.put("tender_type", "CASH");
    return record;
  }

  static void publishSaleVoided(String bootstrapServers, UUID eventId, GenericRecord event) {
    publish(
        bootstrapServers,
        SaleReversalConsumerSchema.VOIDED_TOPIC,
        event.get("sale_id").toString(),
        eventId,
        AvroSerde.serialize(event));
  }

  static GenericRecord saleRefunded(
      UUID saleId, String companyId, UUID businessId, long refundAmountMinor, String currency) {
    Schema schema = SaleReversalConsumerSchema.refundedSchema();
    GenericRecord record = new GenericData.Record(schema);
    record.put("refund_id", UUID.randomUUID().toString());
    record.put("sale_id", saleId.toString());
    record.put("payment_id", UUID.randomUUID().toString());
    record.put("company_id", companyId);
    record.put("business_id", businessId.toString());
    record.put("refund_amount_minor", refundAmountMinor);
    record.put("currency", currency);
    record.put("total_refunded_minor", refundAmountMinor);
    record.put("occurred_at", Instant.now().toEpochMilli());
    record.put("tender_type", "CASH");
    return record;
  }

  static void publishSaleRefunded(String bootstrapServers, UUID eventId, GenericRecord event) {
    publish(
        bootstrapServers,
        SaleReversalConsumerSchema.REFUNDED_TOPIC,
        event.get("sale_id").toString(),
        eventId,
        AvroSerde.serialize(event));
  }

  private static void publish(
      String bootstrapServers, String topic, String key, UUID eventId, byte[] value) {
    try (KafkaProducer<String, byte[]> producer =
        new KafkaProducer<>(producerConfig(bootstrapServers))) {
      ProducerRecord<String, byte[]> record = new ProducerRecord<>(topic, key, value);
      record.headers().add("id", eventId.toString().getBytes(StandardCharsets.UTF_8));
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
