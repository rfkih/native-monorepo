package id.co.nativeapp.payment;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.events.AvroSerde;
import id.co.nativeapp.payment.charge.messaging.PaymentChargeExpiredSchema;
import java.time.Instant;
import java.util.UUID;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericRecord;
import org.junit.jupiter.api.Test;

/**
 * Contract test for {@code PaymentChargeExpired} (ADR 0045) — producer side, no Spring context.
 * Proves the single-source {@code .avsc} parses, a record built by the schema holder round-trips
 * through {@code libs/events} {@link AvroSerde} (including the nullable {@code reference_id} in
 * both states), and the backward-compatibility gate holds: self-compatible, added-optional-field
 * accepted, new REQUIRED field without default rejected (CLAUDE.md rule 7).
 */
class PaymentChargeExpiredContractTest {

  private static final UUID CHARGE_ID = UUID.fromString("aaaaaaaa-1111-2222-3333-444444444444");
  private static final UUID PAYMENT_ID = UUID.fromString("bbbbbbbb-1111-2222-3333-444444444444");
  private static final UUID TICKET_ID = UUID.fromString("cccccccc-1111-2222-3333-444444444444");
  private static final UUID BUSINESS_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
  private static final String TENANT = "11111111-1111-1111-1111-111111111111";

  @Test
  void avscParsesFromClasspath() {
    Schema schema = PaymentChargeExpiredSchema.schema();
    assertThat(schema.getFullName())
        .isEqualTo("id.co.nativeapp.events.payment.PaymentChargeExpired");
    assertThat(schema.getField("charge_id")).isNotNull();
    assertThat(schema.getField("company_id")).isNotNull();
    assertThat(schema.getField("vertical")).isNotNull();
    assertThat(schema.getField("payment_id")).isNotNull();
    assertThat(schema.getField("business_id")).isNotNull();
    assertThat(schema.getField("reason")).isNotNull();
    // Money is a required long — minor units, never a float (rule 8) — plus its currency.
    assertThat(schema.getField("amount_minor").schema().getType()).isEqualTo(Schema.Type.LONG);
    assertThat(schema.getField("currency")).isNotNull();
    // The optional differs-per-vertical key defaults to null so restaurant may omit it (rule 7).
    assertThat(schema.getField("reference_id").schema().getType()).isEqualTo(Schema.Type.UNION);
    assertThat(schema.getField("occurred_at").schema().getLogicalType().getName())
        .isEqualTo("timestamp-millis");
  }

  @Test
  void aRestaurantExpiryRoundTripsWithNullReferenceId() {
    GenericRecord record =
        PaymentChargeExpiredSchema.toRecord(
            CHARGE_ID,
            TENANT,
            "restaurant",
            PAYMENT_ID,
            null,
            BUSINESS_ID,
            185_000L,
            "IDR",
            "EXPIRED",
            Instant.ofEpochMilli(1_750_000_000_000L));

    GenericRecord decoded =
        AvroSerde.deserialize(AvroSerde.serialize(record), PaymentChargeExpiredSchema.schema());

    assertThat(decoded.get("charge_id").toString()).isEqualTo(CHARGE_ID.toString());
    assertThat(decoded.get("company_id").toString()).isEqualTo(TENANT);
    assertThat(decoded.get("vertical").toString()).isEqualTo("restaurant");
    assertThat(decoded.get("payment_id").toString()).isEqualTo(PAYMENT_ID.toString());
    assertThat(decoded.get("reference_id")).isNull();
    assertThat(decoded.get("business_id").toString()).isEqualTo(BUSINESS_ID.toString());
    assertThat(decoded.get("amount_minor")).isEqualTo(185_000L);
    assertThat(decoded.get("currency").toString()).isEqualTo("IDR");
    assertThat(decoded.get("reason").toString()).isEqualTo("EXPIRED");
    assertThat(decoded.get("occurred_at")).isEqualTo(1_750_000_000_000L);
  }

  @Test
  void aTicketVerticalExpiryCarriesTheTicketReferenceIdAndAnyReason() {
    // Carwash/barbershop release writers are keyed by TICKET id — reference_id carries it; the
    // reason is free to be CANCELED/FAILED, not only EXPIRED.
    GenericRecord record =
        PaymentChargeExpiredSchema.toRecord(
            CHARGE_ID,
            TENANT,
            "carwash",
            PAYMENT_ID,
            TICKET_ID,
            BUSINESS_ID,
            75_000L,
            "IDR",
            "CANCELED",
            Instant.ofEpochMilli(1_750_000_000_000L));

    GenericRecord decoded =
        AvroSerde.deserialize(AvroSerde.serialize(record), PaymentChargeExpiredSchema.schema());

    assertThat(decoded.get("vertical").toString()).isEqualTo("carwash");
    assertThat(decoded.get("reference_id").toString()).isEqualTo(TICKET_ID.toString());
    assertThat(decoded.get("reason").toString()).isEqualTo("CANCELED");
  }

  @Test
  void schemaIsSelfCompatibleAndAcceptsAddedOptionalField() {
    Schema v1 = PaymentChargeExpiredSchema.schema();
    assertThat(AvroSerde.isBackwardCompatible(v1, v1)).isTrue();

    // An additive evolution — a trailing optional field with default null — must be accepted.
    Schema v2WithOptional =
        new Schema.Parser()
            .parse(
                v1.toString()
                    .replaceFirst(
                        "\\]\\}$",
                        ",{\"name\":\"provider\",\"type\":[\"null\",\"string\"],"
                            + "\"default\":null}]}"));
    assertThat(AvroSerde.isBackwardCompatible(v1, v2WithOptional)).isTrue();
  }

  @Test
  void addingARequiredFieldWithoutDefaultBreaksBackwardCompatibility() {
    Schema v1 = PaymentChargeExpiredSchema.schema();
    Schema incompatible =
        new Schema.Parser()
            .parse(
                v1.toString()
                    .replaceFirst("\\]\\}$", ",{\"name\":\"swept_by\",\"type\":\"string\"}]}"));
    assertThat(AvroSerde.isBackwardCompatible(v1, incompatible)).isFalse();
  }
}
