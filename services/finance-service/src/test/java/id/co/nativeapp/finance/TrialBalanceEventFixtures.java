package id.co.nativeapp.finance;

import id.co.nativeapp.events.AvroSerde;
import id.co.nativeapp.events.Base64ByteArraySerializer;
import id.co.nativeapp.finance.grouptb.messaging.TrialBalancePublishedSchema;
import id.co.nativeapp.finance.revenue.messaging.SaleRecordedListener;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
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
 * Test fixtures for the P3d SEAM 2 {@code TrialBalancePublished} event: building the Avro {@link
 * GenericRecord} (via finance's consumer-copy schema) and publishing it as raw Avro bytes the way
 * the producer + Debezium would — group id as the partition key, the durable event id in the {@code
 * id} header. NOT a Schema Registry serde. (The PRODUCER itself is SEAM 3/4 and not wired; tests
 * publish a directly-built event.)
 */
final class TrialBalanceEventFixtures {

  private TrialBalanceEventFixtures() {}

  /** A single trial-balance line spec (no intercompany metadata). */
  static GenericRecord line(
      String glAccountCode,
      String accountType,
      String postingType,
      long amountMinor,
      String currency) {
    Schema lineSchema =
        TrialBalancePublishedSchema.schema().getField("lines").schema().getElementType();
    GenericRecord record = new GenericData.Record(lineSchema);
    record.put("gl_account_code", glAccountCode);
    record.put("account_type", accountType);
    record.put("posting_type", postingType);
    record.put("amount_minor", amountMinor);
    record.put("currency", currency);
    record.put("related_party_counterparty_id", null);
    record.put("intercompany_ref", null);
    return record;
  }

  static GenericRecord trialBalancePublished(
      UUID memberCompanyId,
      UUID groupId,
      String period,
      String baseCurrency,
      boolean reconciled,
      boolean usesIllustrativeRules,
      List<GenericRecord> lines) {
    GenericRecord record = new GenericData.Record(TrialBalancePublishedSchema.schema());
    record.put("company_id", memberCompanyId.toString());
    record.put("group_id", groupId.toString());
    record.put("period", period);
    record.put("base_currency", baseCurrency);
    record.put("reconciled", reconciled);
    record.put("uses_illustrative_rules", usesIllustrativeRules);
    record.put("lines", new ArrayList<>(lines));
    return record;
  }

  static void publish(String bootstrapServers, UUID groupId, UUID eventId, GenericRecord event) {
    byte[] value = AvroSerde.serialize(event);
    try (KafkaProducer<String, byte[]> producer =
        new KafkaProducer<>(producerConfig(bootstrapServers))) {
      ProducerRecord<String, byte[]> record =
          new ProducerRecord<>(TrialBalancePublishedSchema.TOPIC, groupId.toString(), value);
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
