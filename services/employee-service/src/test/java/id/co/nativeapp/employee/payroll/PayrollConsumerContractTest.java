package id.co.nativeapp.employee.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.employee.payroll.dto.MetricProjectedEvent;
import id.co.nativeapp.employee.payroll.dto.PeriodSealedProjectedEvent;
import id.co.nativeapp.employee.payroll.messaging.MetricPublishedConsumerSchema;
import id.co.nativeapp.employee.payroll.messaging.PeriodSealedSchema;
import id.co.nativeapp.events.AvroSerde;
import java.time.Instant;
import java.util.UUID;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.junit.jupiter.api.Test;

/**
 * Consumer-side contract triads (rule 7) for the two consumed events. For {@code MetricPublished}
 * the key assertion is that employee-service's CONSUMER copy stays BACKWARD-COMPATIBLE with
 * carwash-service's PRODUCER schema (the consumer reads producer bytes), exactly like the
 * OrgUnit/Staff consumer contract tests.
 */
class PayrollConsumerContractTest {

  /** carwash-service's producer schema for MetricPublished (the writer the consumer must read). */
  private static final String CARWASH_METRIC_PRODUCER =
      """
      {
        "type": "record",
        "name": "MetricPublished",
        "namespace": "id.co.nativeapp.events.carwash",
        "fields": [
          {"name": "metric_key", "type": "string"},
          {"name": "period", "type": "string"},
          {"name": "grain", "type": "string"},
          {"name": "subject_id", "type": "string"},
          {"name": "value", "type": "long"},
          {"name": "source_business_id", "type": "string"}
        ]
      }
      """;

  @Test
  void metricPublishedConsumerCopyParsesAndDecodesProducerBytes() {
    Schema producer = new Schema.Parser().parse(CARWASH_METRIC_PRODUCER);
    GenericRecord produced = new GenericData.Record(producer);
    produced.put("metric_key", "wash_count");
    produced.put("period", "2026-06");
    produced.put("grain", "outlet");
    produced.put("subject_id", "22222222-2222-2222-2222-222222222222");
    produced.put("value", 42L);
    produced.put("source_business_id", "22222222-2222-2222-2222-222222222222");
    byte[] bytes = AvroSerde.serialize(produced);

    // The consumer decodes producer bytes against its own copy, supplying company_id from the
    // header.
    MetricProjectedEvent event =
        MetricPublishedConsumerSchema.decode(UUID.randomUUID(), "company-a", bytes);
    assertThat(event.metricKey()).isEqualTo("wash_count");
    assertThat(event.value()).isEqualTo(42L);
    assertThat(event.companyId()).isEqualTo("company-a");
  }

  @Test
  void metricPublishedConsumerCopyIsBackwardCompatibleWithProducer() {
    Schema producer = new Schema.Parser().parse(CARWASH_METRIC_PRODUCER);
    Schema consumer = MetricPublishedConsumerSchema.schema();
    // The consumer (reader) must read producer (writer) bytes.
    assertThat(AvroSerde.isBackwardCompatible(producer, consumer)).isTrue();
  }

  @Test
  void periodSealedRoundTripsAndDecodes() {
    Schema schema = PeriodSealedSchema.schema();
    assertThat(schema.getFullName()).isEqualTo("id.co.nativeapp.events.carwash.PeriodSealed");
    assertThat(schema.getField("business_id")).isNotNull();
    assertThat(schema.getField("period")).isNotNull();

    UUID businessId = UUID.fromString("22222222-2222-2222-2222-222222222222");
    GenericRecord record =
        PeriodSealedSchema.toRecord(
            "11111111-1111-1111-1111-111111111111",
            businessId,
            "2026-06",
            Instant.ofEpochMilli(1_750_000_000_000L));
    byte[] bytes = AvroSerde.serialize(record);

    PeriodSealedProjectedEvent event = PeriodSealedSchema.decode(UUID.randomUUID(), bytes);
    assertThat(event.businessId()).isEqualTo(businessId);
    assertThat(event.period()).isEqualTo("2026-06");
    assertThat(event.companyId()).isEqualTo("11111111-1111-1111-1111-111111111111");
  }

  @Test
  void periodSealedAddingAnOptionalFieldStaysBackwardCompatible() {
    Schema current = PeriodSealedSchema.schema();
    Schema evolved =
        new Schema.Parser()
            .parse(
                current
                    .toString()
                    .replace(
                        "\"fields\":[",
                        "\"fields\":[{\"name\":\"note\",\"type\":[\"null\",\"string\"],\"default\":null},"));
    assertThat(AvroSerde.isBackwardCompatible(current, evolved)).isTrue();
  }
}
