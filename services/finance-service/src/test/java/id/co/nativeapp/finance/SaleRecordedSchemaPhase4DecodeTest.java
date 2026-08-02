package id.co.nativeapp.finance;

import static org.assertj.core.api.Assertions.assertThat;

import id.co.nativeapp.events.AvroSerde;
import id.co.nativeapp.finance.revenue.messaging.SaleRecordedEvent;
import id.co.nativeapp.finance.revenue.messaging.SaleRecordedSchema;
import id.co.nativeapp.money.Money;
import java.util.UUID;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.junit.jupiter.api.Test;

/**
 * Pure-unit test (no Spring, no DB) proving {@link SaleRecordedSchema#decode} correctly maps the
 * five Phase 4 (ADR 0027) Avro fields onto the matching {@link SaleRecordedEvent} accessor —
 * money-critical decode plumbing where a field-ordering slip (e.g. swapping {@code
 * loyalty_redeemed_points} and {@code loyalty_redeemed_minor}) would silently corrupt data.
 * Complements {@code SaleRecordedContractTest} (which proves the Avro SCHEMA round-trips the five
 * fields) by proving the JAVA decode path wires them to the right record component.
 */
class SaleRecordedSchemaPhase4DecodeTest {

  @Test
  void decodesAllFivePhase4FieldsIntoTheMatchingAccessor() {
    UUID saleId = UUID.randomUUID();
    UUID businessId = UUID.randomUUID();
    UUID memberId = UUID.randomUUID();
    UUID giftCardId = UUID.randomUUID();
    String companyId = UUID.randomUUID().toString();

    GenericRecord record = new GenericData.Record(SaleRecordedSchema.schema());
    record.put("sale_id", saleId.toString());
    record.put("company_id", companyId);
    record.put("business_id", businessId.toString());
    record.put("amount_minor", 45_000L);
    record.put("currency", "IDR");
    record.put("occurred_at", 1_750_000_000_000L);
    record.put("tender_type", "CASH");
    record.put("subtotal_minor", 50_000L);
    record.put("discount_minor", 0L);
    record.put("service_charge_minor", 0L);
    record.put("tax_minor", 0L);
    record.put("tax_rule_version", null);
    record.put("uses_illustrative_rules", true);
    record.put("loyalty_member_id", memberId.toString());
    record.put("loyalty_redeemed_points", 500L);
    record.put("loyalty_redeemed_minor", 5_000L);
    record.put("gift_card_id", giftCardId.toString());
    record.put("gift_card_redeemed_minor", 20_000L);
    // ADR 0036 (Phase B): no producer threads a real channel yet, but the decode plumbing must
    // wire a present value to the right accessor just like the five Phase 4 fields above — proven
    // here with a real (non-null) value even though production only ever sends explicit null.
    record.put("channel", "GOFOOD");

    byte[] bytes = AvroSerde.serialize(record);
    UUID eventId = UUID.randomUUID();
    SaleRecordedEvent event = SaleRecordedSchema.decode(eventId, bytes);

    assertThat(event.eventId()).isEqualTo(eventId);
    assertThat(event.saleId()).isEqualTo(saleId);
    assertThat(event.loyaltyMemberId()).isEqualTo(memberId.toString());
    assertThat(event.loyaltyRedeemedPoints()).isEqualTo(500L);
    assertThat(event.loyaltyRedeemedMinor()).isEqualTo(5_000L);
    assertThat(event.giftCardId()).isEqualTo(giftCardId.toString());
    assertThat(event.giftCardRedeemedMinor()).isEqualTo(20_000L);
    assertThat(event.channel()).isEqualTo("GOFOOD");

    // The effective* accessors resolve to the SAME values (sanity — not swapped/mismapped).
    assertThat(event.effectiveLoyaltyRedeemed()).isEqualTo(Money.ofMinor(5_000L, "IDR"));
    assertThat(event.effectiveGiftCardRedeemed()).isEqualTo(Money.ofMinor(20_000L, "IDR"));

    // amount(45,000) = subtotal(50,000) - loyaltyRedeemed(5,000): the identity must hold for this
    // hand-built decode fixture (regression guard against a future accidental data-shape drift).
    event.assertReconciliationIdentity();
  }

  @Test
  void decodesAllFivePhase4FieldsAsNullForAPrePhase4StyleRecordThatOmitsThem() {
    // Avro allows an explicit null for a ["null", ...] union field even when built against the
    // CURRENT (Phase 4) schema — this simulates the "field present but unset" case distinct from
    // the true old-writer/new-reader case SaleRecordedContractTest already covers at the schema
    // level.
    UUID saleId = UUID.randomUUID();
    UUID businessId = UUID.randomUUID();
    String companyId = UUID.randomUUID().toString();

    GenericRecord record = new GenericData.Record(SaleRecordedSchema.schema());
    record.put("sale_id", saleId.toString());
    record.put("company_id", companyId);
    record.put("business_id", businessId.toString());
    record.put("amount_minor", 20_000L);
    record.put("currency", "IDR");
    record.put("occurred_at", 1_750_000_000_000L);
    record.put("tender_type", null);
    record.put("subtotal_minor", null);
    record.put("discount_minor", null);
    record.put("service_charge_minor", null);
    record.put("tax_minor", null);
    record.put("tax_rule_version", null);
    record.put("uses_illustrative_rules", null);
    record.put("loyalty_member_id", null);
    record.put("loyalty_redeemed_points", null);
    record.put("loyalty_redeemed_minor", null);
    record.put("gift_card_id", null);
    record.put("gift_card_redeemed_minor", null);
    record.put("channel", null); // ADR 0036 (Phase B): every producer in this wave nulls it.

    byte[] bytes = AvroSerde.serialize(record);
    SaleRecordedEvent event = SaleRecordedSchema.decode(UUID.randomUUID(), bytes);

    assertThat(event.loyaltyMemberId()).isNull();
    assertThat(event.loyaltyRedeemedPoints()).isNull();
    assertThat(event.loyaltyRedeemedMinor()).isNull();
    assertThat(event.giftCardId()).isNull();
    assertThat(event.giftCardRedeemedMinor()).isNull();
    assertThat(event.channel()).isNull();
    assertThat(event.effectiveLoyaltyRedeemed().isZero()).isTrue();
    assertThat(event.effectiveGiftCardRedeemed().isZero()).isTrue();
    event.assertReconciliationIdentity(); // legacy shape: never throws
  }
}
