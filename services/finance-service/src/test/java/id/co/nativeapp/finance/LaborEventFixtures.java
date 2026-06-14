package id.co.nativeapp.finance;

import id.co.nativeapp.events.AvroSerde;
import id.co.nativeapp.finance.labor.LaborCostAllocatedSchema;
import id.co.nativeapp.finance.labor.PayrollPostedSchema;
import id.co.nativeapp.finance.revenue.SaleRecordedListener;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
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
 * Test fixtures for publishing {@code LaborCostAllocated} and {@code PayrollPosted} the way the
 * producer + Debezium would: raw Avro bytes (built via {@code libs/events AvroSerde}) as the value,
 * the run id as the key, and the durable event id stamped into the {@code id} header (the dedupe
 * key the finance consumers use). NOT a Schema Registry serde — exactly the transport finance
 * consumes.
 */
final class LaborEventFixtures {

  private LaborEventFixtures() {}

  /**
   * Builds a {@code LaborCostAllocated} {@link GenericRecord} against finance's consumer schema.
   */
  static GenericRecord laborBucket(
      UUID payrollRunId,
      int runSeq,
      String companyId,
      String period,
      UUID outletId,
      String glAccount,
      long amountMinor,
      String currency,
      boolean usesIllustrative,
      boolean unallocated,
      Instant occurredAt) {
    GenericRecord record = new GenericData.Record(LaborCostAllocatedSchema.schema());
    record.put("payroll_run_id", payrollRunId.toString());
    record.put("company_id", companyId);
    record.put("period", period);
    record.put("outlet_id", outletId.toString());
    record.put("gl_account", glAccount);
    record.put("amount_minor", amountMinor);
    record.put("currency", currency);
    record.put("run_seq", runSeq);
    record.put("uses_illustrative_rules", usesIllustrative);
    record.put("unallocated", unallocated);
    record.put("occurred_at", occurredAt.toEpochMilli());
    return record;
  }

  /** Builds a {@code PayrollPosted} {@link GenericRecord} against finance's consumer schema. */
  static GenericRecord payrollPosted(
      UUID payrollRunId,
      int runSeq,
      String companyId,
      String period,
      String baseCurrency,
      long grossMinor,
      long employeeDeductionMinor,
      long employerContributionMinor,
      long netMinor,
      boolean usesIllustrative,
      Instant postedAt) {
    GenericRecord record = new GenericData.Record(PayrollPostedSchema.schema());
    record.put("payroll_run_id", payrollRunId.toString());
    record.put("company_id", companyId);
    record.put("period", period);
    record.put("base_currency", baseCurrency);
    record.put("gross_total_minor", grossMinor);
    record.put("employee_deduction_total_minor", employeeDeductionMinor);
    record.put("employer_contribution_total_minor", employerContributionMinor);
    record.put("net_total_minor", netMinor);
    record.put("rule_versions", List.of());
    record.put("run_seq", runSeq);
    record.put("uses_illustrative_rules", usesIllustrative);
    record.put("posted_at", postedAt.toEpochMilli());
    return record;
  }

  /**
   * Publishes a record to {@code topic} with the given key and event id (stamped in the header).
   */
  static void publish(
      String bootstrapServers, String topic, String key, UUID eventId, GenericRecord event) {
    publishBytes(bootstrapServers, topic, key, eventId, AvroSerde.serialize(event));
  }

  /**
   * Publishes raw {@code value} bytes with the {@code id} header (used to inject a poison value).
   */
  static void publishBytes(
      String bootstrapServers, String topic, String key, UUID eventId, byte[] value) {
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
