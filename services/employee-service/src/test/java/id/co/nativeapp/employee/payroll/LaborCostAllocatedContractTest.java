package id.co.nativeapp.employee.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.events.AvroSerde;
import id.co.nativeapp.money.Money;
import java.time.Instant;
import java.util.UUID;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericRecord;
import org.junit.jupiter.api.Test;

/**
 * The {@code LaborCostAllocated} PRODUCER contract triad (rule 7): parse + shape (outlet/GL bucket,
 * NO employee_id so no individual salary is derivable — design decision vs §5), AvroSerde
 * round-trip, and backward-compat add-optional / break-on-required.
 */
class LaborCostAllocatedContractTest {

  @Test
  void avscParsesAsAnOutletGlBucketWithNoEmployeeId() {
    Schema schema = LaborCostAllocatedSchema.schema();
    assertThat(schema.getFullName())
        .isEqualTo("id.co.nativeapp.events.employee.LaborCostAllocated");
    assertThat(schema.getField("outlet_id")).isNotNull();
    assertThat(schema.getField("gl_account")).isNotNull();
    assertThat(schema.getField("amount_minor")).isNotNull();
    assertThat(schema.getField("uses_illustrative_rules")).isNotNull();
    // The UNALLOCATED-suspense marker rides the wire with a default (backward-compatible add).
    assertThat(schema.getField("unallocated")).isNotNull();
    assertThat(schema.getField("unallocated").hasDefaultValue()).isTrue();
    // employee_id is DROPPED to avoid per-person salary exposure (rule 6 / open risk).
    assertThat(schema.getField("employee_id")).isNull();
  }

  @Test
  void unallocatedSuspenseBucketIsMarkedOnTheEvent() {
    GenericRecord record =
        LaborCostAllocatedSchema.toRecord(
            UUID.fromString("55555555-5555-5555-5555-555555555555"),
            "11111111-1111-1111-1111-111111111111",
            "2026-06",
            new UUID(0L, 0L),
            "9999-UNALLOCATED-LABOR",
            Money.ofMinor(5_000_000L, "IDR"),
            false,
            true,
            Instant.ofEpochMilli(1_750_000_000_000L));

    GenericRecord decoded =
        AvroSerde.deserialize(AvroSerde.serialize(record), LaborCostAllocatedSchema.schema());
    assertThat(decoded.get("unallocated")).isEqualTo(true);
    assertThat(decoded.get("gl_account").toString()).isEqualTo("9999-UNALLOCATED-LABOR");
  }

  @Test
  void recordRoundTripsThroughAvroSerde() {
    GenericRecord record =
        LaborCostAllocatedSchema.toRecord(
            UUID.fromString("55555555-5555-5555-5555-555555555555"),
            "11111111-1111-1111-1111-111111111111",
            "2026-06",
            UUID.fromString("22222222-2222-2222-2222-222222222222"),
            "5100-SALARY",
            Money.ofMinor(20_400_000L, "IDR"),
            true,
            false,
            Instant.ofEpochMilli(1_750_000_000_000L));

    byte[] bytes = AvroSerde.serialize(record);
    GenericRecord decoded = AvroSerde.deserialize(bytes, LaborCostAllocatedSchema.schema());
    assertThat(decoded.get("amount_minor")).isEqualTo(20_400_000L);
    assertThat(decoded.get("gl_account").toString()).isEqualTo("5100-SALARY");
    assertThat(decoded.get("uses_illustrative_rules")).isEqualTo(true);
    assertThat(decoded.get("unallocated")).isEqualTo(false);
  }

  @Test
  void addingAnOptionalFieldStaysBackwardCompatibleAndARequiredFieldBreaks() {
    Schema current = LaborCostAllocatedSchema.schema();
    Schema evolved =
        new Schema.Parser()
            .parse(
                current
                    .toString()
                    .replace(
                        "\"fields\":[",
                        "\"fields\":[{\"name\":\"note\",\"type\":[\"null\",\"string\"],\"default\":null},"));
    assertThat(AvroSerde.isBackwardCompatible(current, evolved)).isTrue();

    Schema broken =
        new Schema.Parser()
            .parse(
                current
                    .toString()
                    .replace(
                        "\"fields\":[",
                        "\"fields\":[{\"name\":\"mandatory\",\"type\":\"string\"},"));
    assertThat(AvroSerde.isBackwardCompatible(current, broken)).isFalse();
  }
}
