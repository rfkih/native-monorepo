package id.co.nativeapp.finance.empexpense.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.events.AvroSerde;
import id.co.nativeapp.money.Money;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.junit.jupiter.api.Test;

/**
 * Pure-unit test (no Spring, no DB) proving {@link ExpenseClaimApprovedSchema#decode} correctly
 * round-trips REAL producer bytes (built + serialized against the schema, not a hand-built {@link
 * ExpenseClaimApprovedEvent}) into the matching record accessor — money-critical decode plumbing,
 * mirroring {@code SaleRecordedSchemaPhase4DecodeTest} (review W2). Asserts the {@link Money}
 * reconstruction, the {@code expense_date} epoch-day → {@link LocalDate} conversion, and the {@code
 * approved_at} epoch-millis → {@link Instant} conversion.
 */
class ExpenseClaimApprovedSchemaDecodeTest {

  @Test
  void decodesRealProducerBytesIntoTheMatchingEventAccessors() {
    UUID claimId = UUID.randomUUID();
    UUID orgUnitId = UUID.randomUUID();
    UUID employeeId = UUID.randomUUID();
    String companyId = UUID.randomUUID().toString();
    LocalDate expenseDate = LocalDate.of(2026, 7, 15);
    Instant approvedAt = Instant.parse("2026-08-02T09:30:00Z");

    GenericRecord record = new GenericData.Record(ExpenseClaimApprovedSchema.schema());
    record.put("claim_id", claimId.toString());
    record.put("company_id", companyId);
    record.put("org_unit_id", orgUnitId.toString());
    record.put("employee_id", employeeId.toString());
    record.put("amount_minor", 375_000L);
    record.put("currency", "IDR");
    record.put("gl_hint", "supplies");
    record.put("expense_date", (int) expenseDate.toEpochDay());
    record.put("approved_at", approvedAt.toEpochMilli());

    byte[] bytes = AvroSerde.serialize(record);
    UUID eventId = UUID.randomUUID();
    ExpenseClaimApprovedEvent event = ExpenseClaimApprovedSchema.decode(eventId, bytes);

    assertThat(event.eventId()).isEqualTo(eventId);
    assertThat(event.claimId()).isEqualTo(claimId);
    assertThat(event.companyId()).isEqualTo(companyId);
    assertThat(event.orgUnitId()).isEqualTo(orgUnitId);
    assertThat(event.employeeId()).isEqualTo(employeeId);
    // Money reconstructed as integer minor units + ISO-4217 currency, never a float (rule 8).
    assertThat(event.amount()).isEqualTo(Money.ofMinor(375_000L, "IDR"));
    assertThat(event.glHint()).isEqualTo("supplies");
    assertThat(event.expenseDate()).isEqualTo(expenseDate);
    assertThat(event.approvedAt()).isEqualTo(approvedAt);
  }

  @Test
  void decodesTheAllZerosOrgUnitSentinelForAnUnassignedEmployee() {
    UUID claimId = UUID.randomUUID();
    UUID employeeId = UUID.randomUUID();
    String companyId = UUID.randomUUID().toString();
    UUID sentinel = new UUID(0L, 0L);

    GenericRecord record = new GenericData.Record(ExpenseClaimApprovedSchema.schema());
    record.put("claim_id", claimId.toString());
    record.put("company_id", companyId);
    record.put("org_unit_id", sentinel.toString());
    record.put("employee_id", employeeId.toString());
    record.put("amount_minor", 1_000L);
    record.put("currency", "IDR");
    record.put("gl_hint", "");
    record.put("expense_date", (int) LocalDate.of(2026, 7, 1).toEpochDay());
    record.put("approved_at", Instant.parse("2026-08-02T00:00:00Z").toEpochMilli());

    byte[] bytes = AvroSerde.serialize(record);
    ExpenseClaimApprovedEvent event = ExpenseClaimApprovedSchema.decode(UUID.randomUUID(), bytes);

    assertThat(event.orgUnitId()).isEqualTo(sentinel);
    assertThat(event.glHint()).isEmpty();
  }
}
