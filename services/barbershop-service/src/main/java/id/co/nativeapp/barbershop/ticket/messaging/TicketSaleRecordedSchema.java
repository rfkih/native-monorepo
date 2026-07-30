package id.co.nativeapp.barbershop.ticket.messaging;

import id.co.nativeapp.barbershop.pricing.domain.PriceBreakdown;
import id.co.nativeapp.money.Money;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.UUID;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;

/**
 * Loads barbershop-service's PRODUCER copy of the {@code SaleRecorded} Avro schema from the
 * classpath ({@code avro/SaleRecorded.avsc}, shipped by {@code libs/contracts}) and builds the
 * FULL-BREAKDOWN {@link GenericRecord}s the barbershop POS ticket produces — the SAME shared
 * contract restaurant-service and carwash-service already produce onto (ADR 0024; there is no
 * legacy null-breakdown producer in this service — the ticket flow is the ONLY revenue path).
 *
 * <p>Field-by-field mapping mirrors carwash-service's {@code ticket.messaging.
 * TicketSaleRecordedSchema} (itself mirroring restaurant-service's {@code sale.messaging.
 * SaleRecordedSchema}): {@code amount_minor} is the GRAND TOTAL, the {@code subtotal_minor}/{@code
 * discount_minor}/{@code service_charge_minor}/{@code tax_minor}/{@code tax_rule_version}/{@code
 * uses_illustrative_rules} legs are populated from the resolved {@link PriceBreakdown}, and {@code
 * tender_type} carries the checkout/capture tender's name. This schema is entity-agnostic: it takes
 * the already-decided {@code saleId} (the ticket's own id, per ADR 0023 decision 2, applied here —
 * {@code ticket.sale_id = ticket.id}), so it serves BOTH the cash-checkout path and the later
 * digital-capture path from the same helper.
 */
public final class TicketSaleRecordedSchema {

  /**
   * Classpath location of the {@code .avsc} — the same resource restaurant-service and
   * carwash-service load (shipped once by {@code libs/contracts}).
   */
  public static final String RESOURCE = "avro/SaleRecorded.avsc";

  /** The event name as it appears in the outbox {@code event_type} column. */
  public static final String EVENT_TYPE = "SaleRecorded";

  /** The producing aggregate kind (outbox {@code aggregate_type} / partition routing). */
  public static final String AGGREGATE_TYPE = "sale";

  private static final Schema SCHEMA = parse();

  private TicketSaleRecordedSchema() {
    // static holder
  }

  /** The parsed reader/writer schema for {@code SaleRecorded}. */
  public static Schema schema() {
    return SCHEMA;
  }

  /**
   * Builds a {@code SaleRecorded} {@link GenericRecord} with the full price breakdown. Backward-
   * compatible overload with every Phase 4 (ADR 0027) field {@code null}.
   *
   * @param saleId the sale id (the ticket's own id — ADR 0023 decision 2)
   * @param companyId the owning tenant (stamped on the ticket from the tenant scope)
   * @param businessId the barbershop outlet
   * @param grandTotal the amount the customer pays (must equal {@code breakdown.grandTotal()})
   * @param occurredAt when the sale was recognised (checkout for CASH, capture for digital)
   * @param tenderType the tender enum name ({@code "CASH"}, {@code "QRIS"}, {@code "CARD"}), or
   *     {@code null} when a gift card fully settled the sale (ADR 0027)
   * @param breakdown the resolved price breakdown
   */
  @SuppressWarnings("checkstyle:ParameterNumber")
  public static GenericRecord toRecord(
      UUID saleId,
      String companyId,
      UUID businessId,
      Money grandTotal,
      Instant occurredAt,
      String tenderType,
      PriceBreakdown breakdown) {
    return toRecord(
        saleId, companyId, businessId, grandTotal, occurredAt, tenderType, breakdown, null, null,
        null, null, null);
  }

  /**
   * Builds a {@code SaleRecorded} {@link GenericRecord} with the full price breakdown PLUS the
   * Phase 4 (ADR 0027) loyalty/gift-card fields. Mirrors carwash-service's identical method.
   *
   * @param loyaltyMemberId the attached loyalty member, or {@code null}
   * @param loyaltyRedeemedPoints the ACTUAL points redeemed, or {@code null}/0
   * @param loyaltyRedeemedMinor the currency value of the redeemed points, minor units, or {@code
   *     null}/0
   * @param giftCardId the gift card redeemed as a tender, or {@code null}
   * @param giftCardRedeemedMinor the ACTUAL amount redeemed from the gift card, minor units, or
   *     {@code null}/0
   */
  @SuppressWarnings("checkstyle:ParameterNumber")
  public static GenericRecord toRecord(
      UUID saleId,
      String companyId,
      UUID businessId,
      Money grandTotal,
      Instant occurredAt,
      String tenderType,
      PriceBreakdown breakdown,
      UUID loyaltyMemberId,
      Long loyaltyRedeemedPoints,
      Long loyaltyRedeemedMinor,
      UUID giftCardId,
      Long giftCardRedeemedMinor) {
    GenericRecord record = new GenericData.Record(SCHEMA);
    record.put("sale_id", saleId.toString());
    record.put("company_id", companyId);
    record.put("business_id", businessId.toString());
    record.put("amount_minor", grandTotal.amountMinor());
    record.put("currency", grandTotal.currency().getCurrencyCode());
    record.put("occurred_at", occurredAt.toEpochMilli());
    record.put("tender_type", tenderType);
    long loyaltyMinor = loyaltyRedeemedMinor != null ? loyaltyRedeemedMinor : 0L;
    record.put("subtotal_minor", breakdown.subtotal().amountMinor());
    record.put("discount_minor", breakdown.discount().amountMinor() - loyaltyMinor);
    record.put("service_charge_minor", breakdown.serviceCharge().amountMinor());
    record.put("tax_minor", breakdown.tax().amountMinor());
    record.put("tax_rule_version", breakdown.taxRuleVersion());
    record.put("uses_illustrative_rules", breakdown.usesIllustrativeRules());
    record.put("loyalty_member_id", loyaltyMemberId == null ? null : loyaltyMemberId.toString());
    record.put("loyalty_redeemed_points", loyaltyRedeemedPoints);
    record.put("loyalty_redeemed_minor", loyaltyRedeemedMinor);
    record.put("gift_card_id", giftCardId == null ? null : giftCardId.toString());
    record.put("gift_card_redeemed_minor", giftCardRedeemedMinor);
    return record;
  }

  private static Schema parse() {
    try (InputStream in =
        TicketSaleRecordedSchema.class.getClassLoader().getResourceAsStream(RESOURCE)) {
      if (in == null) {
        throw new IllegalStateException("Avro schema not found on classpath: " + RESOURCE);
      }
      return new Schema.Parser().parse(in);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to load Avro schema " + RESOURCE, e);
    }
  }
}
