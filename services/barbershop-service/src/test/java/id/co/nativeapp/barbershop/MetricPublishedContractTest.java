package id.co.nativeapp.barbershop;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.barbershop.metric.domain.BarbershopMetricContract;
import id.co.nativeapp.barbershop.metric.domain.MetricGrain;
import id.co.nativeapp.barbershop.metric.messaging.MetricPublishedSchema;
import id.co.nativeapp.events.AvroSerde;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericRecord;
import org.junit.jupiter.api.Test;

/**
 * Test (f) — the contract triad for the PRODUCED {@code MetricPublished} event (no Spring context
 * needed; CLAUDE.md rule 7 / ENGINEERING-STANDARDS §3.2). Ported from carwash-service's {@code
 * MetricPublishedContractTest} (ADR 0024).
 *
 * <p>Proves the {@code MetricPublished.avsc} on the classpath (a) parses with the expected shape
 * (full name + the §5 key fields metric_key/period/grain/subject_id/value/source_business_id), (b)
 * round-trips a {@link GenericRecord} through {@code libs/events AvroSerde} (the exact path the
 * outbox serializes on), and (c) backward-compatibility: the schema is compatible with itself and
 * with an added-optional-field variant, while a new required field with no default is rejected.
 *
 * <p><strong>The full name is still {@code id.co.nativeapp.events.carwash.MetricPublished}</strong>
 * — {@code MetricPublished.avsc} is ONE shared physical schema (from {@code libs/contracts}) that
 * restaurant, carwash, AND barbershop all produce onto; the namespace stays "carwash" (the historic
 * producer-of-record) rather than forking a barbershop-specific copy. See the Javadoc on {@link
 * BarbershopMetricContract} for why {@code service_count} replaces {@code wash_count} with no
 * {@code upsell_amount} analog.
 */
class MetricPublishedContractTest {

  @Test
  void avscParsesWithTheExpectedShape() {
    Schema schema = MetricPublishedSchema.schema();
    assertThat(schema.getFullName()).isEqualTo("id.co.nativeapp.events.carwash.MetricPublished");
    assertThat(schema.getField("metric_key")).isNotNull();
    assertThat(schema.getField("period")).isNotNull();
    assertThat(schema.getField("grain")).isNotNull();
    assertThat(schema.getField("subject_id")).isNotNull();
    assertThat(schema.getField("value").schema().getType()).isEqualTo(Schema.Type.LONG);
    assertThat(schema.getField("source_business_id")).isNotNull();
  }

  @Test
  void roundTripsThroughAvroSerde() {
    GenericRecord record =
        MetricPublishedSchema.toRecord(
            BarbershopMetricContract.SERVICE_COUNT,
            "2026-06-14",
            "outlet",
            "22222222-2222-2222-2222-222222222222",
            1L,
            "22222222-2222-2222-2222-222222222222");
    byte[] bytes = AvroSerde.serialize(record);
    GenericRecord decoded = AvroSerde.deserialize(bytes, MetricPublishedSchema.schema());
    assertThat(decoded.get("metric_key").toString()).isEqualTo("service_count");
    assertThat(decoded.get("grain").toString()).isEqualTo("outlet");
    assertThat(decoded.get("value")).isEqualTo(1L);
    assertThat(decoded.get("source_business_id").toString())
        .isEqualTo("22222222-2222-2222-2222-222222222222");
  }

  @Test
  void isBackwardCompatibleWithItselfAndAnAddedOptionalField() {
    Schema v1 = MetricPublishedSchema.schema();
    assertThat(AvroSerde.isBackwardCompatible(v1, v1)).isTrue();
    Schema v2 =
        new Schema.Parser()
            .parse(
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
                    {"name": "source_business_id", "type": "string"},
                    {"name": "unit", "type": ["null", "string"], "default": null}
                  ]
                }
                """);
    assertThat(AvroSerde.isBackwardCompatible(v1, v2)).isTrue();
  }

  @Test
  void rejectsANewRequiredFieldWithoutDefault() {
    Schema v1 = MetricPublishedSchema.schema();
    Schema incompatible =
        new Schema.Parser()
            .parse(
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
                    {"name": "source_business_id", "type": "string"},
                    {"name": "currency", "type": "string"}
                  ]
                }
                """);
    assertThat(AvroSerde.isBackwardCompatible(v1, incompatible)).isFalse();
  }

  /**
   * The barbershop POS ticket's employee-grain barber-commission feed (ADR 0024, mirroring ADR 0023
   * decision 4): {@code sales_amount} at the {@code employee} grain, {@code subject_id} = the
   * barber's employee id (NOT the outlet, and NOT the cashier — deliberately unlike restaurant's
   * cashier-attributed metric). {@link BarbershopMetricContract#SALES_AMOUNT} rides the SAME
   * generic schema (no new field) — a new metric key is a data change, not a schema change.
   */
  @Test
  void salesAmountAtTheEmployeeGrainRoundTripsThroughAvroSerde() {
    String barberEmployeeId = "44444444-4444-4444-4444-444444444444";
    String outletId = "22222222-2222-2222-2222-222222222222";
    GenericRecord record =
        MetricPublishedSchema.toRecord(
            BarbershopMetricContract.SALES_AMOUNT,
            "2026-07-30",
            MetricGrain.EMPLOYEE.wireValue(),
            barberEmployeeId,
            77_700_00L,
            outletId);
    byte[] bytes = AvroSerde.serialize(record);
    GenericRecord decoded = AvroSerde.deserialize(bytes, MetricPublishedSchema.schema());
    assertThat(decoded.get("metric_key").toString()).isEqualTo("sales_amount");
    assertThat(decoded.get("grain").toString()).isEqualTo("employee");
    assertThat(decoded.get("subject_id").toString()).isEqualTo(barberEmployeeId);
    assertThat(decoded.get("value")).isEqualTo(77_700_00L);
    assertThat(decoded.get("source_business_id").toString()).isEqualTo(outletId);
  }

  /**
   * Deliberate difference from carwash: barbershop declares ONLY {@code service_count} at the
   * outlet grain — no {@code upsell_amount} analog (see {@link BarbershopMetricContract} javadoc).
   */
  @Test
  void declarationsContainOnlyServiceCountAtTheOutletGrain() {
    assertThat(BarbershopMetricContract.DECLARATIONS).hasSize(1);
    assertThat(BarbershopMetricContract.DECLARATIONS.getFirst().metricKey())
        .isEqualTo("service_count");
    assertThat(BarbershopMetricContract.DECLARATIONS.getFirst().grain())
        .isEqualTo(MetricGrain.OUTLET);
  }
}
