package id.co.nativeapp.restaurant;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.events.AvroSerde;
import id.co.nativeapp.restaurant.register.messaging.RegisterSessionClosedSchema;
import java.time.Instant;
import java.util.UUID;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericRecord;
import org.junit.jupiter.api.Test;

/**
 * Contract test for {@code RegisterSessionClosed} (ADR 0035, closing kasir) — producer side, no
 * Spring context. Proves the single-source {@code .avsc} parses, a record built by the schema
 * holder round-trips through {@code libs/events} {@link AvroSerde}, the reconciliation-identity
 * fields survive the wire (finance DLTs on violation, so the producer-side shape is load-bearing),
 * and the backward-compatibility gate holds: the schema is self-compatible, an added-optional-field
 * evolution is accepted, and a new REQUIRED field without default is rejected (CLAUDE.md rule 7).
 */
class RegisterSessionClosedContractTest {

  @Test
  void avscParsesFromClasspath() {
    Schema schema = RegisterSessionClosedSchema.schema();
    assertThat(schema.getFullName())
        .isEqualTo("id.co.nativeapp.events.restaurant.RegisterSessionClosed");
    assertThat(schema.getField("session_id")).isNotNull();
    assertThat(schema.getField("company_id")).isNotNull();
    assertThat(schema.getField("business_id")).isNotNull();
    assertThat(schema.getField("opened_at").schema().getLogicalType().getName())
        .isEqualTo("timestamp-millis");
    assertThat(schema.getField("closed_at").schema().getLogicalType().getName())
        .isEqualTo("timestamp-millis");
    // Every money field is a required long — minor units, never a float (rule 8).
    for (String moneyField :
        new String[] {
          "opening_float_minor",
          "cash_sales_minor",
          "cash_refunds_minor",
          "expected_cash_minor",
          "counted_cash_minor",
          "over_short_minor"
        }) {
      assertThat(schema.getField(moneyField).schema().getType()).isEqualTo(Schema.Type.LONG);
    }
    assertThat(schema.getField("currency")).isNotNull();
  }

  @Test
  void recordRoundTripsThroughAvroSerdeWithReconciliationIdentityIntact() {
    UUID sessionId = UUID.fromString("aaaaaaaa-1111-2222-3333-444444444444");
    UUID businessId = UUID.fromString("22222222-2222-2222-2222-222222222222");
    // A SHORT drawer: float 200k + sales 1.5M − refunds 50k = expected 1.65M; counted 1.6M.
    GenericRecord record =
        RegisterSessionClosedSchema.toRecord(
            sessionId,
            "11111111-1111-1111-1111-111111111111",
            businessId,
            Instant.ofEpochMilli(1_750_000_000_000L),
            Instant.ofEpochMilli(1_750_030_000_000L),
            200_000L,
            1_500_000L,
            50_000L,
            1_650_000L,
            1_600_000L,
            -50_000L,
            "IDR");

    byte[] bytes = AvroSerde.serialize(record);
    GenericRecord decoded = AvroSerde.deserialize(bytes, RegisterSessionClosedSchema.schema());

    assertThat(decoded.get("session_id").toString()).isEqualTo(sessionId.toString());
    assertThat(decoded.get("business_id").toString()).isEqualTo(businessId.toString());
    assertThat(decoded.get("opened_at")).isEqualTo(1_750_000_000_000L);
    assertThat(decoded.get("closed_at")).isEqualTo(1_750_030_000_000L);
    assertThat(decoded.get("opening_float_minor")).isEqualTo(200_000L);
    assertThat(decoded.get("cash_sales_minor")).isEqualTo(1_500_000L);
    assertThat(decoded.get("cash_refunds_minor")).isEqualTo(50_000L);
    assertThat(decoded.get("expected_cash_minor")).isEqualTo(1_650_000L);
    assertThat(decoded.get("counted_cash_minor")).isEqualTo(1_600_000L);
    assertThat(decoded.get("over_short_minor")).isEqualTo(-50_000L);
    assertThat(decoded.get("currency").toString()).isEqualTo("IDR");
    // The identities finance re-asserts (violation = poison → DLT) hold on the wire shape.
    long expected =
        (long) decoded.get("opening_float_minor")
            + (long) decoded.get("cash_sales_minor")
            - (long) decoded.get("cash_refunds_minor");
    assertThat(decoded.get("expected_cash_minor")).isEqualTo(expected);
    long overShort =
        (long) decoded.get("counted_cash_minor") - (long) decoded.get("expected_cash_minor");
    assertThat(decoded.get("over_short_minor")).isEqualTo(overShort);
  }

  @Test
  void schemaIsSelfCompatibleAndAcceptsAddedOptionalField() {
    Schema v1 = RegisterSessionClosedSchema.schema();
    assertThat(AvroSerde.isBackwardCompatible(v1, v1)).isTrue();

    // An additive evolution — a trailing optional field with default null — must be accepted:
    // a reader on v2 can decode v1 bytes (missing field → default).
    Schema v2WithOptional =
        new Schema.Parser()
            .parse(
                v1.toString()
                    .replace(
                        "{\"name\":\"currency\",\"type\":\"string\"",
                        "{\"name\":\"currency\",\"type\":\"string\"")
                    .replaceFirst(
                        "\\]\\}$",
                        ",{\"name\":\"cash_drops_minor\",\"type\":[\"null\",\"long\"],"
                            + "\"default\":null}]}"));
    assertThat(AvroSerde.isBackwardCompatible(v1, v2WithOptional)).isTrue();
  }

  @Test
  void addingARequiredFieldWithoutDefaultBreaksBackwardCompatibility() {
    Schema v1 = RegisterSessionClosedSchema.schema();
    Schema incompatible =
        new Schema.Parser()
            .parse(v1.toString().replaceFirst("\\]\\}$", ",{\"name\":\"cashier_id\",\"type\":\"string\"}]}"));
    // A reader on the new schema cannot read v1 data — no value for cashier_id.
    assertThat(AvroSerde.isBackwardCompatible(v1, incompatible)).isFalse();
  }
}
