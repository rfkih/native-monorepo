package id.co.nativeapp.employee.expense;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.employee.expense.domain.ExpenseClaim;
import id.co.nativeapp.employee.expense.domain.ReimbursementMethod;
import id.co.nativeapp.employee.expense.messaging.ExpenseReimbursementSettledSchema;
import id.co.nativeapp.events.AvroSerde;
import id.co.nativeapp.money.Money;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * The {@code ExpenseReimbursementSettled} PRODUCER contract test (rule-7 triad, no Spring context;
 * ADR 0030 E0). Locks the nullable-with-default idiom on {@code payroll_run_id}/{@code run_seq}: a
 * DIRECT settlement omits both; a PAYROLL settlement carries both.
 */
class ExpenseReimbursementSettledContractTest {

  @Test
  void schemaParsesFromClasspathWithTheDocumentedShape() {
    Schema schema = ExpenseReimbursementSettledSchema.schema();
    assertThat(schema.getFullName())
        .isEqualTo("id.co.nativeapp.events.employee.ExpenseReimbursementSettled");
    assertThat(schema.getField("settlement_kind")).isNotNull();
    assertThat(schema.getField("payroll_run_id").schema().getType()).isEqualTo(Schema.Type.UNION);
    assertThat(schema.getField("payroll_run_id").hasDefaultValue()).isTrue();
    assertThat(schema.getField("run_seq").schema().getType()).isEqualTo(Schema.Type.UNION);
    assertThat(schema.getField("run_seq").hasDefaultValue()).isTrue();
    assertThat(schema.getField("settled_at").schema().getLogicalType().getName())
        .isEqualTo("timestamp-millis");
  }

  @Test
  void directSettlementRoundTripsWithNullRunFields() {
    Schema schema = ExpenseReimbursementSettledSchema.schema();
    GenericRecord record = new GenericData.Record(schema);
    record.put("claim_id", "55555555-5555-5555-5555-555555555555");
    record.put("company_id", "11111111-1111-1111-1111-111111111111");
    record.put("org_unit_id", "22222222-2222-2222-2222-222222222222");
    record.put("employee_id", "33333333-3333-3333-3333-333333333333");
    record.put("amount_minor", 250_000L);
    record.put("currency", "IDR");
    record.put("settlement_kind", ExpenseReimbursementSettledSchema.KIND_DIRECT);
    record.put("payroll_run_id", null);
    record.put("run_seq", null);
    record.put("settled_at", 1_786_200_000_000L);

    GenericRecord decoded = AvroSerde.deserialize(AvroSerde.serialize(record), schema);

    assertThat(decoded.get("settlement_kind").toString()).isEqualTo("DIRECT");
    assertThat(decoded.get("payroll_run_id")).isNull();
    assertThat(decoded.get("run_seq")).isNull();
  }

  @Test
  void payrollSettlementRoundTripsWithRunFields() {
    Schema schema = ExpenseReimbursementSettledSchema.schema();
    GenericRecord record = new GenericData.Record(schema);
    record.put("claim_id", "55555555-5555-5555-5555-555555555555");
    record.put("company_id", "11111111-1111-1111-1111-111111111111");
    record.put("org_unit_id", "22222222-2222-2222-2222-222222222222");
    record.put("employee_id", "33333333-3333-3333-3333-333333333333");
    record.put("amount_minor", 250_000L);
    record.put("currency", "IDR");
    record.put("settlement_kind", ExpenseReimbursementSettledSchema.KIND_PAYROLL);
    record.put("payroll_run_id", "44444444-4444-4444-4444-444444444444");
    record.put("run_seq", 2);
    record.put("settled_at", 1_786_200_000_000L);

    GenericRecord decoded = AvroSerde.deserialize(AvroSerde.serialize(record), schema);

    assertThat(decoded.get("settlement_kind").toString()).isEqualTo("PAYROLL");
    assertThat(decoded.get("payroll_run_id").toString())
        .isEqualTo("44444444-4444-4444-4444-444444444444");
    assertThat(decoded.get("run_seq")).isEqualTo(2);
  }

  @Test
  void toRecordPayrollBuildsAGenuinePayrollKindRecordThatRoundTrips() {
    // S3 (E5 review): exercises the REAL producer method (ExpenseReimbursementSettledSchema
    // #toRecordPayroll), not a hand-built GenericRecord — payrollSettlementRoundTripsWithRunFields
    // above only proves the SCHEMA shape accepts PAYROLL fields; this proves the actual builder
    // wires them correctly from a real (settled) ExpenseClaim.
    ExpenseClaim claim =
        new ExpenseClaim(
            UUID.fromString("33333333-3333-3333-3333-333333333333"),
            UUID.fromString("66666666-6666-6666-6666-666666666666"),
            UUID.fromString("22222222-2222-2222-2222-222222222222"),
            Money.ofMinor(250_000L, "IDR"),
            LocalDate.of(2026, 7, 15),
            null,
            null,
            ReimbursementMethod.PAYROLL);
    claim.setCompanyId("11111111-1111-1111-1111-111111111111");
    claim.submit();
    claim.approve("manager-sub", "ok", Instant.parse("2026-07-19T09:00:00Z"));
    UUID payrollRunId = UUID.fromString("44444444-4444-4444-4444-444444444444");
    ReflectionTestUtils.setField(claim, "reimbursementRunId", payrollRunId);
    Instant settledAt = Instant.parse("2026-07-25T09:00:00Z");
    claim.settleByPayrollRun(settledAt);

    GenericRecord record =
        ExpenseReimbursementSettledSchema.toRecordPayroll(claim, payrollRunId, 2, settledAt);
    GenericRecord decoded =
        AvroSerde.deserialize(
            AvroSerde.serialize(record), ExpenseReimbursementSettledSchema.schema());

    assertThat(decoded.get("claim_id").toString()).isEqualTo(claim.getId().toString());
    assertThat(decoded.get("company_id").toString())
        .isEqualTo("11111111-1111-1111-1111-111111111111");
    assertThat(decoded.get("employee_id").toString())
        .isEqualTo("33333333-3333-3333-3333-333333333333");
    assertThat(decoded.get("amount_minor")).isEqualTo(250_000L);
    assertThat(decoded.get("currency").toString()).isEqualTo("IDR");
    assertThat(decoded.get("settlement_kind").toString())
        .isEqualTo(ExpenseReimbursementSettledSchema.KIND_PAYROLL);
    assertThat(decoded.get("payroll_run_id").toString()).isEqualTo(payrollRunId.toString());
    assertThat(decoded.get("run_seq")).isEqualTo(2);
    assertThat(decoded.get("settled_at")).isEqualTo(settledAt.toEpochMilli());
  }

  @Test
  void addingARequiredFieldWithoutDefaultBreaksBackwardCompatibility() {
    Schema registered = ExpenseReimbursementSettledSchema.schema();
    Schema incompatible =
        new Schema.Parser()
            .parse(
                """
                {
                  "type": "record",
                  "name": "ExpenseReimbursementSettled",
                  "namespace": "id.co.nativeapp.events.employee",
                  "fields": [
                    {"name": "claim_id", "type": "string"},
                    {"name": "company_id", "type": "string"},
                    {"name": "org_unit_id", "type": "string"},
                    {"name": "employee_id", "type": "string"},
                    {"name": "amount_minor", "type": "long"},
                    {"name": "currency", "type": "string"},
                    {"name": "settlement_kind", "type": "string"},
                    {"name": "payroll_run_id", "type": ["null", "string"], "default": null},
                    {"name": "run_seq", "type": ["null", "int"], "default": null},
                    {"name": "settled_at", "type": {"type": "long", "logicalType": "timestamp-millis"}},
                    {"name": "bank_reference", "type": "string"}
                  ]
                }
                """);
    assertThat(AvroSerde.isBackwardCompatible(registered, incompatible)).isFalse();
  }

  @Test
  void addingAnOptionalFieldWithADefaultStaysBackwardCompatible() {
    Schema registered = ExpenseReimbursementSettledSchema.schema();
    Schema evolved =
        new Schema.Parser()
            .parse(
                """
                {
                  "type": "record",
                  "name": "ExpenseReimbursementSettled",
                  "namespace": "id.co.nativeapp.events.employee",
                  "fields": [
                    {"name": "claim_id", "type": "string"},
                    {"name": "company_id", "type": "string"},
                    {"name": "org_unit_id", "type": "string"},
                    {"name": "employee_id", "type": "string"},
                    {"name": "amount_minor", "type": "long"},
                    {"name": "currency", "type": "string"},
                    {"name": "settlement_kind", "type": "string"},
                    {"name": "payroll_run_id", "type": ["null", "string"], "default": null},
                    {"name": "run_seq", "type": ["null", "int"], "default": null},
                    {"name": "settled_at", "type": {"type": "long", "logicalType": "timestamp-millis"}},
                    {"name": "bank_reference", "type": ["null", "string"], "default": null}
                  ]
                }
                """);
    assertThat(AvroSerde.isBackwardCompatible(registered, evolved)).isTrue();
  }
}
