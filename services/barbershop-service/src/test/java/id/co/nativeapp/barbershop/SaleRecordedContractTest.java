package id.co.nativeapp.barbershop;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.barbershop.pricing.domain.PriceBreakdown;
import id.co.nativeapp.barbershop.ticket.messaging.TicketSaleRecordedSchema;
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
 * 7). Ported from carwash-service's {@code SaleRecordedContractTest}, adapted for ADR 0024: unlike
 * carwash (which still carries a legacy null-breakdown {@code wash.messaging.SaleRecordedSchema}
 * producer alongside its full-breakdown ticket producer), barbershop-service has NO legacy revenue
 * path — {@link TicketSaleRecordedSchema} is the ONLY {@code SaleRecorded} producer in this
 * service, so there is no "legacy vs. ticket" comparison to make.
 *
 * <p>barbershop emits {@code SaleRecorded} on the SAME Avro contract restaurant-service and
 * carwash-service already produce onto (the restaurant-service producer schema — the namespace
 * stays "restaurant", the historic producer-of-record, exactly as carwash's copy does). This proves
 * barbershop's copy parses, has the expected shape and full name, round-trips through {@code
 * libs/events AvroSerde} (the exact path the outbox serializes on), stays mutually
 * backward-compatible with the producer (restaurant) schema — so finance reads barbershop tickets
 * through the very same consumer path — and that the back-compat gate accepts an added-optional
 * field while rejecting a new required field with no default.
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
    Schema schema = TicketSaleRecordedSchema.schema();
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
    Schema schema = TicketSaleRecordedSchema.schema();
    GenericRecord record = new GenericData.Record(schema);
    record.put("sale_id", "33333333-3333-3333-3333-333333333333");
    record.put("company_id", "11111111-1111-1111-1111-111111111111");
    record.put("business_id", "22222222-2222-2222-2222-222222222222"); // the barbershop outlet
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
  void barbershopCopyIsMutuallyBackwardCompatibleWithTheRestaurantProducerSchema() {
    Schema consumerCopy = TicketSaleRecordedSchema.schema();
    // finance reads barbershop's bytes with the producer schema, and vice versa — both directions
    // must be compatible so the SAME ledger path consolidates restaurant + carwash + barbershop.
    assertThat(AvroSerde.isBackwardCompatible(PRODUCER_SCHEMA, consumerCopy)).isTrue();
    assertThat(AvroSerde.isBackwardCompatible(consumerCopy, PRODUCER_SCHEMA)).isTrue();
  }

  @Test
  void addingAnOptionalFieldWithDefaultIsBackwardCompatible() {
    Schema v1 = TicketSaleRecordedSchema.schema();
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
    Schema v1 = TicketSaleRecordedSchema.schema();
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
   * The ticket checkout populates the FULL price breakdown + {@code tender_type} — the only
   * producer shape this service ever writes (there is no legacy null-breakdown path in
   * barbershop-service, unlike carwash's grandfathered {@code POST /washes}).
   */
  @Test
  void ticketFullBreakdownProducerBuildsAValidRecordAgainstTheSharedSchema() {
    Schema schema = TicketSaleRecordedSchema.schema();

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

    // Still mutually backward-compatible with the restaurant producer schema — same shared
    // contract.
    assertThat(AvroSerde.isBackwardCompatible(PRODUCER_SCHEMA, schema)).isTrue();
    assertThat(AvroSerde.isBackwardCompatible(schema, PRODUCER_SCHEMA)).isTrue();
  }
}
