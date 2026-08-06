package id.co.nativeapp.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import id.co.nativeapp.events.AvroSerde;
import id.co.nativeapp.finance.register.messaging.RegisterSessionClosedEvent;
import id.co.nativeapp.finance.register.messaging.RegisterSessionClosedSchema;
import java.util.UUID;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.junit.jupiter.api.Test;

/**
 * Contract test for {@code RegisterSessionClosed} (ADR 0036) — consumer side, no Spring context.
 * Proves the single-source {@code .avsc} parses on finance's classpath, producer-shaped bytes
 * decode into {@link RegisterSessionClosedEvent}, the reconciliation-identity guard accepts a
 * consistent event and rejects an inconsistent one (the poison/DLT path), and schema evolution
 * stays backward-compatible in both directions (CLAUDE.md rule 7).
 */
class RegisterSessionClosedContractTest {

  private static final UUID EVENT_ID = UUID.fromString("eeeeeeee-0000-0000-0000-000000000001");

  private static GenericRecord producerRecord(
      long floatMinor, long sales, long refunds, long expected, long counted, long overShort) {
    GenericRecord record = new GenericData.Record(RegisterSessionClosedSchema.schema());
    record.put("session_id", "aaaaaaaa-1111-2222-3333-444444444444");
    record.put("company_id", "11111111-1111-1111-1111-111111111111");
    record.put("business_id", "22222222-2222-2222-2222-222222222222");
    record.put("opened_at", 1_750_000_000_000L);
    record.put("closed_at", 1_750_030_000_000L);
    record.put("opening_float_minor", floatMinor);
    record.put("cash_sales_minor", sales);
    record.put("cash_refunds_minor", refunds);
    record.put("expected_cash_minor", expected);
    record.put("counted_cash_minor", counted);
    record.put("over_short_minor", overShort);
    record.put("currency", "IDR");
    record.put("tenders", java.util.List.of());
    return record;
  }

  /** A non-cash tender reconciliation element (ADR 0038 phase 2) for the {@code tenders} array. */
  private static GenericRecord tenderRecord(
      String type, long expected, long counted, long overShort) {
    Schema item =
        RegisterSessionClosedSchema.schema().getField("tenders").schema().getElementType();
    GenericRecord line = new GenericData.Record(item);
    line.put("tender_type", type);
    line.put("expected_minor", expected);
    line.put("counted_minor", counted);
    line.put("over_short_minor", overShort);
    return line;
  }

  @Test
  void producerBytesDecodeAndConsistentIdentityPasses() {
    // Short drawer: 200k float + 1.5M sales − 50k refunds = 1.65M expected; counted 1.6M → −50k.
    byte[] bytes =
        AvroSerde.serialize(
            producerRecord(200_000L, 1_500_000L, 50_000L, 1_650_000L, 1_600_000L, -50_000L));
    GenericRecord decoded = AvroSerde.deserialize(bytes, RegisterSessionClosedSchema.schema());
    RegisterSessionClosedEvent event = RegisterSessionClosedEvent.from(EVENT_ID, decoded);

    event.assertReconciliationIdentity(); // must not throw
    assertThat(event.companyId()).isEqualTo("11111111-1111-1111-1111-111111111111");
    assertThat(event.overShortMinor()).isEqualTo(-50_000L);
    assertThat(event.currency()).isEqualTo("IDR");
    assertThat(event.closedAt()).isAfter(event.openedAt());
  }

  @Test
  void violatedExpectedIdentityIsPoison() {
    // expected claims 1.7M but float+sales−refunds = 1.65M → inconsistent → DLT path.
    byte[] bytes =
        AvroSerde.serialize(
            producerRecord(200_000L, 1_500_000L, 50_000L, 1_700_000L, 1_600_000L, -100_000L));
    RegisterSessionClosedEvent event =
        RegisterSessionClosedEvent.from(
            EVENT_ID, AvroSerde.deserialize(bytes, RegisterSessionClosedSchema.schema()));
    assertThatThrownBy(event::assertReconciliationIdentity)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("reconciliation identity violated");
  }

  @Test
  void violatedVarianceIdentityIsPoison() {
    // counted − expected = −50k but over_short claims −60k.
    byte[] bytes =
        AvroSerde.serialize(
            producerRecord(200_000L, 1_500_000L, 50_000L, 1_650_000L, 1_600_000L, -60_000L));
    RegisterSessionClosedEvent event =
        RegisterSessionClosedEvent.from(
            EVENT_ID, AvroSerde.deserialize(bytes, RegisterSessionClosedSchema.schema()));
    assertThatThrownBy(event::assertReconciliationIdentity)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("variance identity violated");
  }

  @Test
  void negativeCountedCashIsPoison() {
    byte[] bytes = AvroSerde.serialize(producerRecord(0L, 100_000L, 0L, 100_000L, -1L, -100_001L));
    RegisterSessionClosedEvent event =
        RegisterSessionClosedEvent.from(
            EVENT_ID, AvroSerde.deserialize(bytes, RegisterSessionClosedSchema.schema()));
    assertThatThrownBy(event::assertReconciliationIdentity)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("sign guard violated");
  }

  @Test
  void perTenderReconciliationDecodesAndConsistentIdentityPasses() {
    // Cash balanced; CARD short 10k (expected 800k, counted 790k), QRIS balanced (ADR 0038 phase
    // 2).
    GenericRecord record = producerRecord(0L, 0L, 0L, 0L, 0L, 0L);
    record.put(
        "tenders",
        java.util.List.of(
            tenderRecord("CARD", 800_000L, 790_000L, -10_000L),
            tenderRecord("QRIS", 430_000L, 430_000L, 0L)));

    GenericRecord decoded =
        AvroSerde.deserialize(AvroSerde.serialize(record), RegisterSessionClosedSchema.schema());
    RegisterSessionClosedEvent event = RegisterSessionClosedEvent.from(EVENT_ID, decoded);

    event.assertReconciliationIdentity(); // must not throw
    assertThat(event.tenders()).hasSize(2);
    assertThat(event.tenders().get(0).tenderType()).isEqualTo("CARD");
    assertThat(event.tenders().get(0).overShortMinor()).isEqualTo(-10_000L);
  }

  @Test
  void violatedTenderVarianceIdentityIsPoison() {
    // CARD counted − expected = −10k but over_short claims −20k → poison.
    GenericRecord record = producerRecord(0L, 0L, 0L, 0L, 0L, 0L);
    record.put("tenders", java.util.List.of(tenderRecord("CARD", 800_000L, 790_000L, -20_000L)));
    RegisterSessionClosedEvent event =
        RegisterSessionClosedEvent.from(
            EVENT_ID,
            AvroSerde.deserialize(
                AvroSerde.serialize(record), RegisterSessionClosedSchema.schema()));
    assertThatThrownBy(event::assertReconciliationIdentity)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("tender variance identity violated");
  }

  @Test
  void evolutionStaysBackwardCompatibleBothDirections() {
    Schema v1 = RegisterSessionClosedSchema.schema();
    assertThat(AvroSerde.isBackwardCompatible(v1, v1)).isTrue();
    // v2 = v1 + trailing optional field: a v2 reader decodes v1 bytes (default), and a v1 reader
    // decodes v2 bytes (unknown trailing field dropped) — rule 7's both-direction gate.
    Schema v2 =
        new Schema.Parser()
            .parse(
                v1.toString()
                    .replaceFirst(
                        "\\]\\}$",
                        ",{\"name\":\"cash_drops_minor\",\"type\":[\"null\",\"long\"],"
                            + "\"default\":null}]}"));
    assertThat(AvroSerde.isBackwardCompatible(v1, v2)).isTrue();
    assertThat(AvroSerde.isBackwardCompatible(v2, v1)).isTrue();
  }
}
