package id.co.nativeapp.finance;

import id.co.nativeapp.events.AvroSerde;
import id.co.nativeapp.finance.expense.ExpenseRecordedSchema;
import id.co.nativeapp.finance.revenue.SaleRecordedListener;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
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
 * Test fixtures for publishing an {@code ExpenseRecorded} the way the producer + Debezium would:
 * raw Avro bytes (built via {@code libs/events AvroSerde}) as the message value, the expense id as
 * the key, and the durable event id stamped into the {@code id} header (the Debezium outbox-router
 * event-id header the consumer dedupes on). NOT a Schema Registry serde — exactly the transport
 * finance consumes in production.
 */
final class ExpenseRecordedFixtures {

  private ExpenseRecordedFixtures() {}

  /**
   * Builds an {@code ExpenseRecorded} {@link GenericRecord} against finance's consumer-copy schema.
   */
  static GenericRecord record(
      UUID expenseId,
      String companyId,
      UUID businessId,
      long amountMinor,
      String currency,
      String glHint,
      Instant occurredAt) {
    GenericRecord record = new GenericData.Record(ExpenseRecordedSchema.schema());
    record.put("expense_id", expenseId.toString());
    record.put("company_id", companyId);
    record.put("business_id", businessId.toString());
    record.put("amount_minor", amountMinor);
    record.put("currency", currency);
    record.put("gl_hint", glHint);
    record.put("occurred_at", occurredAt.toEpochMilli());
    return record;
  }

  /**
   * Publishes the record to the {@code ExpenseRecorded} topic with the given key and event id. The
   * event id goes into the {@code id} header so the consumer dedupes by the durable id (not
   * offset), and the value is raw Avro bytes.
   */
  static void publish(String bootstrapServers, String key, UUID eventId, GenericRecord event) {
    try (KafkaProducer<String, byte[]> producer =
        new KafkaProducer<>(producerConfig(bootstrapServers))) {
      ProducerRecord<String, byte[]> record =
          new ProducerRecord<>(ExpenseRecordedSchema.TOPIC, key, AvroSerde.serialize(event));
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
