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
 * Pure-unit test (no Spring, no DB) proving {@link ExpenseClaimVoidedSchema#decode} correctly
 * round-trips REAL producer bytes into the matching record accessor — mirroring {@code
 * SaleRecordedSchemaPhase4DecodeTest} (review W2). Asserts the {@link Money} reconstruction and
 * BOTH {@code approved_at} (the ORIGINAL approval instant) and {@code voided_at} (the void instant)
 * decode into DISTINCT {@link Instant} values — a field-ordering slip here would silently corrupt
 * which period/mapping-rule-version the contra resolves against.
 */
class ExpenseClaimVoidedSchemaDecodeTest {

  @Test
  void decodesRealProducerBytesIntoTheMatchingEventAccessors() {
    UUID claimId = UUID.randomUUID();
    UUID orgUnitId = UUID.randomUUID();
    UUID employeeId = UUID.randomUUID();
    String companyId = UUID.randomUUID().toString();
    Instant approvedAt = Instant.parse("2026-08-01T10:00:00Z");
    Instant voidedAt = Instant.parse("2026-08-02T09:00:00Z");

    GenericRecord record = new GenericData.Record(ExpenseClaimVoidedSchema.schema());
    record.put("claim_id", claimId.toString());
    record.put("company_id", companyId);
    record.put("org_unit_id", orgUnitId.toString());
    record.put("employee_id", employeeId.toString());
    record.put("amount_minor", 250_000L);
    record.put("currency", "IDR");
    record.put("gl_hint", "supplies");
    record.put("approved_at", approvedAt.toEpochMilli());
    record.put("voided_at", voidedAt.toEpochMilli());

    byte[] bytes = AvroSerde.serialize(record);
    UUID eventId = UUID.randomUUID();
    ExpenseClaimVoidedEvent event = ExpenseClaimVoidedSchema.decode(eventId, bytes);

    assertThat(event.eventId()).isEqualTo(eventId);
    assertThat(event.claimId()).isEqualTo(claimId);
    assertThat(event.companyId()).isEqualTo(companyId);
    assertThat(event.orgUnitId()).isEqualTo(orgUnitId);
    assertThat(event.employeeId()).isEqualTo(employeeId);
    assertThat(event.amount()).isEqualTo(Money.ofMinor(250_000L, "IDR"));
    assertThat(event.glHint()).isEqualTo("supplies");
    // approved_at and voided_at must NOT be swapped — they drive different behaviour (mapping
    // resolution vs. the posting period).
    assertThat(event.approvedAt()).isEqualTo(approvedAt);
    assertThat(event.voidedAt()).isEqualTo(voidedAt);
    assertThat(event.approvedAt()).isNotEqualTo(event.voidedAt());
  }
}
