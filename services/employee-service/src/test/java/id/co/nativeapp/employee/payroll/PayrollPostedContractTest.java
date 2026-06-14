package id.co.nativeapp.employee.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.events.AvroSerde;
import java.util.List;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.junit.jupiter.api.Test;

/**
 * The {@code PayrollPosted} PRODUCER contract triad (rule 7): the schema parses with the expected
 * shape (company totals + frozen rule_versions + the illustrative flag), a record round-trips
 * through {@link AvroSerde} (incl. the {@code rule_versions} array + the {@code timestamp-millis}
 * logical type), and an additive change stays backward-compatible while a new required field breaks
 * it. The event carries NO PII (rule 6) — no per-person amounts.
 */
class PayrollPostedContractTest {

  @Test
  void avscParsesWithCompanyTotalsAndNoPerPersonFields() {
    Schema schema = PayrollPostedSchema.schema();
    assertThat(schema.getFullName()).isEqualTo("id.co.nativeapp.events.employee.PayrollPosted");
    assertThat(schema.getField("gross_total_minor")).isNotNull();
    assertThat(schema.getField("net_total_minor")).isNotNull();
    assertThat(schema.getField("uses_illustrative_rules")).isNotNull();
    assertThat(schema.getField("rule_versions").schema().getType()).isEqualTo(Schema.Type.ARRAY);
    assertThat(schema.getField("posted_at").schema().getLogicalType().getName())
        .isEqualTo("timestamp-millis");
    // NO PII / per-person fields.
    assertThat(schema.getField("employee_id")).isNull();
    assertThat(schema.getField("nik")).isNull();
    assertThat(schema.getField("salary")).isNull();
    assertThat(schema.getField("amount_minor")).isNull();
  }

  @Test
  void recordRoundTripsThroughAvroSerde() {
    Schema schema = PayrollPostedSchema.schema();
    GenericRecord record = new GenericData.Record(schema);
    record.put("payroll_run_id", "55555555-5555-5555-5555-555555555555");
    record.put("company_id", "11111111-1111-1111-1111-111111111111");
    record.put("period", "2026-06");
    record.put("base_currency", "IDR");
    record.put("gross_total_minor", 20_000_000L);
    record.put("employee_deduction_total_minor", 2_201_667L);
    record.put("employer_contribution_total_minor", 400_000L);
    record.put("net_total_minor", 17_798_333L);
    Schema rvElement = schema.getField("rule_versions").schema().getElementType();
    GenericRecord rv = new GenericData.Record(rvElement);
    rv.put("rule_key", "PPH21_PROGRESSIVE");
    rv.put("rule_version", "ILLUSTRATIVE-2026.1");
    record.put("rule_versions", List.of(rv));
    record.put("uses_illustrative_rules", true);
    record.put("posted_at", 1_750_000_000_000L);

    byte[] bytes = AvroSerde.serialize(record);
    GenericRecord decoded = AvroSerde.deserialize(bytes, schema);

    assertThat(decoded.get("net_total_minor")).isEqualTo(17_798_333L);
    assertThat(decoded.get("uses_illustrative_rules")).isEqualTo(true);
    assertThat(((List<?>) decoded.get("rule_versions"))).hasSize(1);
  }

  @Test
  void addingAnOptionalFieldStaysBackwardCompatibleAndARequiredFieldBreaks() {
    Schema current = PayrollPostedSchema.schema();
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
