package id.co.nativeapp.carwash;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.carwash.pricing.domain.PriceBreakdown;
import id.co.nativeapp.carwash.ticket.messaging.TicketSaleRecordedSchema;
import id.co.nativeapp.carwash.wash.messaging.SaleRecordedSchema;
import id.co.nativeapp.events.AvroSerde;
import id.co.nativeapp.money.Money;
import java.time.Instant;
import java.util.UUID;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.junit.jupiter.api.Test;

/**
 * Test (f) — the {@code SaleRecorded} PRODUCER-copy contract test (no Spring context needed; rule
 * 7).
 *
 * <p>carwash emits {@code SaleRecorded} on the SAME Avro contract finance already consumes (the
 * restaurant-service producer schema). This proves carwash's copy parses, has the expected shape
 * and full name, round-trips through {@code libs/events AvroSerde} (the exact path the outbox
 * serializes on), stays mutually backward-compatible with the producer (restaurant) schema — so
 * finance reads carwash washes through the very same consumer path — and that the back-compat gate
 * accepts an added-optional field while rejecting a new required field with no default.
 */
class SaleRecordedContractTest {

  /**
   * The restaurant-service producer schema (source of truth), inlined from docs/EVENT-CATALOG.md.
   */
  private static final Schema PRODUCER_SCHEMA =
      new Schema.Parser()
          .parse(
              """
              {
                "type": "record",
                "name": "SaleRecorded",
                "namespace": "id.co.nativeapp.events.restaurant",
                "fields": [
                  {"name": "sale_id", "type": "string"},
                  {"name": "company_id", "type": "string"},
                  {"name": "business_id", "type": "string"},
                  {"name": "amount_minor", "type": "long"},
                  {"name": "currency", "type": "string"},
                  {"name": "occurred_at", "type": {"type": "long", "logicalType": "timestamp-millis"}}
                ]
              }
              """);

  @Test
  void avscParsesWithTheExpectedShape() {
    Schema schema = SaleRecordedSchema.schema();
    assertThat(schema.getFullName()).isEqualTo("id.co.nativeapp.events.restaurant.SaleRecorded");
    assertThat(schema.getField("sale_id")).isNotNull();
    assertThat(schema.getField("company_id")).isNotNull();
    assertThat(schema.getField("business_id")).isNotNull();
    assertThat(schema.getField("amount_minor").schema().getType()).isEqualTo(Schema.Type.LONG);
    assertThat(schema.getField("currency")).isNotNull();
    assertThat(schema.getField("occurred_at").schema().getLogicalType().getName())
        .isEqualTo("timestamp-millis");
  }

  @Test
  void genericRecordRoundTripsThroughAvroSerde() {
    Schema schema = SaleRecordedSchema.schema();
    GenericRecord record = new GenericData.Record(schema);
    record.put("sale_id", "33333333-3333-3333-3333-333333333333");
    record.put("company_id", "11111111-1111-1111-1111-111111111111");
    record.put("business_id", "22222222-2222-2222-2222-222222222222"); // the carwash outlet
    record.put("amount_minor", 4_500_000L); // money is integer minor units, never a float
    record.put("currency", "IDR");
    record.put("occurred_at", 1_750_000_000_000L);

    byte[] bytes = AvroSerde.serialize(record);
    GenericRecord decoded = AvroSerde.deserialize(bytes, schema);

    assertThat(decoded.get("sale_id").toString()).isEqualTo("33333333-3333-3333-3333-333333333333");
    assertThat(decoded.get("amount_minor")).isEqualTo(4_500_000L);
    assertThat(decoded.get("currency").toString()).isEqualTo("IDR");
  }

  @Test
  void carwashCopyIsMutuallyBackwardCompatibleWithTheRestaurantProducerSchema() {
    Schema consumerCopy = SaleRecordedSchema.schema();
    // finance reads carwash's bytes with the producer schema, and vice versa — both directions must
    // be compatible so the SAME ledger path consolidates carwash + restaurant.
    assertThat(AvroSerde.isBackwardCompatible(PRODUCER_SCHEMA, consumerCopy)).isTrue();
    assertThat(AvroSerde.isBackwardCompatible(consumerCopy, PRODUCER_SCHEMA)).isTrue();
  }

  @Test
  void addingAnOptionalFieldWithDefaultIsBackwardCompatible() {
    Schema v1 = SaleRecordedSchema.schema();
    Schema v2 =
        new Schema.Parser()
            .parse(
                """
                {
                  "type": "record",
                  "name": "SaleRecorded",
                  "namespace": "id.co.nativeapp.events.restaurant",
                  "fields": [
                    {"name": "sale_id", "type": "string"},
                    {"name": "company_id", "type": "string"},
                    {"name": "business_id", "type": "string"},
                    {"name": "amount_minor", "type": "long"},
                    {"name": "currency", "type": "string"},
                    {"name": "occurred_at", "type": {"type": "long", "logicalType": "timestamp-millis"}},
                    {"name": "channel", "type": ["null", "string"], "default": null}
                  ]
                }
                """);
    assertThat(AvroSerde.isBackwardCompatible(v1, v2)).isTrue();
  }

  @Test
  void addingARequiredFieldWithoutDefaultBreaksBackwardCompatibility() {
    Schema v1 = SaleRecordedSchema.schema();
    Schema incompatible =
        new Schema.Parser()
            .parse(
                """
                {
                  "type": "record",
                  "name": "SaleRecorded",
                  "namespace": "id.co.nativeapp.events.restaurant",
                  "fields": [
                    {"name": "sale_id", "type": "string"},
                    {"name": "company_id", "type": "string"},
                    {"name": "business_id", "type": "string"},
                    {"name": "amount_minor", "type": "long"},
                    {"name": "currency", "type": "string"},
                    {"name": "occurred_at", "type": {"type": "long", "logicalType": "timestamp-millis"}},
                    {"name": "attendant_id", "type": "string"}
                  ]
                }
                """);
    assertThat(AvroSerde.isBackwardCompatible(v1, incompatible)).isFalse();
  }

  /**
   * carwash's SECOND {@code SaleRecorded} producer (ADR 0023): the ticket checkout populates the
   * FULL price breakdown + {@code tender_type}, unlike {@code wash.messaging.SaleRecordedSchema}
   * (the legacy null-breakdown producer). Both producers load the SAME classpath resource ({@code
   * avro/SaleRecorded.avsc}, byte-identical to restaurant's producer schema), so this proves the
   * full-breakdown path parses, round-trips, and stays mutually backward-compatible with the
   * restaurant producer schema — finance consolidates BOTH carwash producers through the identical
   * consumer path.
   */
  @Test
  void ticketFullBreakdownProducerBuildsAValidRecordAgainstTheSharedSchema() {
    Schema schema = TicketSaleRecordedSchema.schema();
    // Both carwash producers (legacy null-breakdown wash, full-breakdown ticket) share the IDENTICAL
    // classpath resource, so their schema shape (full name included) is byte-identical.
    assertThat(schema.getFullName()).isEqualTo(SaleRecordedSchema.schema().getFullName());

    PriceBreakdown breakdown =
        new PriceBreakdown(
            Money.ofMinor(70_000_00L, "IDR"),
            Money.ofMinor(0L, "IDR"),
            Money.ofMinor(70_000_00L, "IDR"),
            Money.ofMinor(0L, "IDR"),
            Money.ofMinor(7_700_00L, "IDR"),
            Money.ofMinor(77_700_00L, "IDR"),
            "ILLUSTRATIVE-2026.1",
            true);
    UUID saleId = UUID.randomUUID();
    UUID businessId = UUID.fromString("22222222-2222-2222-2222-222222222222");

    GenericRecord record =
        TicketSaleRecordedSchema.toRecord(
            saleId,
            "11111111-1111-1111-1111-111111111111",
            businessId,
            breakdown.grandTotal(),
            Instant.parse("2026-06-14T08:30:00Z"),
            "CASH",
            breakdown);

    byte[] bytes = AvroSerde.serialize(record);
    GenericRecord decoded = AvroSerde.deserialize(bytes, schema);
    assertThat(decoded.get("sale_id").toString()).isEqualTo(saleId.toString());
    assertThat(decoded.get("business_id").toString()).isEqualTo(businessId.toString());
    assertThat(decoded.get("amount_minor")).isEqualTo(77_700_00L);
    assertThat(decoded.get("currency").toString()).isEqualTo("IDR");
    assertThat(decoded.get("tender_type").toString()).isEqualTo("CASH");
    assertThat(decoded.get("subtotal_minor")).isEqualTo(70_000_00L);
    assertThat(decoded.get("tax_minor")).isEqualTo(7_700_00L);
    assertThat(decoded.get("tax_rule_version").toString()).isEqualTo("ILLUSTRATIVE-2026.1");
    assertThat(decoded.get("uses_illustrative_rules")).isEqualTo(true);

    // Still mutually backward-compatible with the restaurant producer schema — same shared contract.
    assertThat(AvroSerde.isBackwardCompatible(PRODUCER_SCHEMA, schema)).isTrue();
    assertThat(AvroSerde.isBackwardCompatible(schema, PRODUCER_SCHEMA)).isTrue();
  }

  /**
   * The legacy-null (wash) and full-breakdown (ticket) producers are two call sites over the SAME
   * Avro contract, not two different schemas — proving neither can silently drift from the other.
   */
  @Test
  void legacyNullAndTicketFullProducersShareTheIdenticalSchema() {
    assertThat(SaleRecordedSchema.schema().toString())
        .isEqualTo(TicketSaleRecordedSchema.schema().toString());
  }
}
