package id.co.nativeapp.finance.empexpense.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.events.AvroSerde;
import id.co.nativeapp.money.Money;
import java.time.Instant;
import java.util.UUID;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.junit.jupiter.api.Test;

/**
 * Pure-unit test (no Spring, no DB) proving {@link ExpenseReimbursementSettledSchema#decode}
 * correctly round-trips REAL producer bytes for BOTH settlement kinds — mirroring {@code
 * SaleRecordedSchemaPhase4DecodeTest} (review W2). The DIRECT case is the previously-untested union
 * branch: {@code payroll_run_id}/{@code run_seq} explicitly {@code null} (the {@code ["null", ...]}
 * union idiom), which must decode to {@code null}, not {@code "null"} or a spurious default.
 */
class ExpenseReimbursementSettledSchemaDecodeTest {

  @Test
  void decodesADirectSettlementWithNullPayrollFields() {
    UUID claimId = UUID.randomUUID();
    UUID orgUnitId = UUID.randomUUID();
    UUID employeeId = UUID.randomUUID();
    String companyId = UUID.randomUUID().toString();
    Instant settledAt = Instant.parse("2026-08-03T09:00:00Z");

    GenericRecord record = new GenericData.Record(ExpenseReimbursementSettledSchema.schema());
    record.put("claim_id", claimId.toString());
    record.put("company_id", companyId);
    record.put("org_unit_id", orgUnitId.toString());
    record.put("employee_id", employeeId.toString());
    record.put("amount_minor", 250_000L);
    record.put("currency", "IDR");
    record.put("settlement_kind", "DIRECT");
    record.put("payroll_run_id", null);
    record.put("run_seq", null);
    record.put("settled_at", settledAt.toEpochMilli());

    byte[] bytes = AvroSerde.serialize(record);
    UUID eventId = UUID.randomUUID();
    ExpenseReimbursementSettledEvent event =
        ExpenseReimbursementSettledSchema.decode(eventId, bytes);

    assertThat(event.eventId()).isEqualTo(eventId);
    assertThat(event.claimId()).isEqualTo(claimId);
    assertThat(event.companyId()).isEqualTo(companyId);
    assertThat(event.orgUnitId()).isEqualTo(orgUnitId);
    assertThat(event.employeeId()).isEqualTo(employeeId);
    assertThat(event.amount()).isEqualTo(Money.ofMinor(250_000L, "IDR"));
    assertThat(event.settlementKind()).isEqualTo("DIRECT");
    assertThat(event.payrollRunId()).isNull();
    assertThat(event.runSeq()).isNull();
    assertThat(event.settledAt()).isEqualTo(settledAt);
  }

  @Test
  void decodesAPayrollSettlementWithThePayrollFieldsPopulated() {
    UUID claimId = UUID.randomUUID();
    UUID orgUnitId = UUID.randomUUID();
    UUID employeeId = UUID.randomUUID();
    UUID payrollRunId = UUID.randomUUID();
    String companyId = UUID.randomUUID().toString();
    Instant settledAt = Instant.parse("2026-08-05T09:00:00Z");

    GenericRecord record = new GenericData.Record(ExpenseReimbursementSettledSchema.schema());
    record.put("claim_id", claimId.toString());
    record.put("company_id", companyId);
    record.put("org_unit_id", orgUnitId.toString());
    record.put("employee_id", employeeId.toString());
    record.put("amount_minor", 500_000L);
    record.put("currency", "IDR");
    record.put("settlement_kind", "PAYROLL");
    record.put("payroll_run_id", payrollRunId.toString());
    record.put("run_seq", 3);
    record.put("settled_at", settledAt.toEpochMilli());

    byte[] bytes = AvroSerde.serialize(record);
    UUID eventId = UUID.randomUUID();
    ExpenseReimbursementSettledEvent event =
        ExpenseReimbursementSettledSchema.decode(eventId, bytes);

    assertThat(event.amount()).isEqualTo(Money.ofMinor(500_000L, "IDR"));
    assertThat(event.settlementKind()).isEqualTo("PAYROLL");
    assertThat(event.payrollRunId()).isEqualTo(payrollRunId);
    assertThat(event.runSeq()).isEqualTo(3);
    assertThat(event.settledAt()).isEqualTo(settledAt);
  }
}
