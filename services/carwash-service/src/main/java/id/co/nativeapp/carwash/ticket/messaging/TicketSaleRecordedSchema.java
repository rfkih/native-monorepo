package id.co.nativeapp.carwash.ticket.messaging;

import id.co.nativeapp.carwash.pricing.domain.PriceBreakdown;
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
 * Loads carwash-service's PRODUCER copy of the {@code SaleRecorded} Avro schema from the classpath
 * ({@code avro/SaleRecorded.avsc}, shipped by {@code libs/contracts}) and builds the FULL-BREAKDOWN
 * {@link GenericRecord}s the carwash POS ticket produces — the second carwash producer alongside
 * {@link id.co.nativeapp.carwash.wash.messaging.SaleRecordedSchema
 * wash.messaging.SaleRecordedSchema} (which stays a null-breakdown legacy producer, untouched — ADR
 * 0023 decision 6).
 *
 * <p>Field-by-field mapping mirrors restaurant-service's {@code sale.messaging.SaleRecordedSchema}
 * (the schema's Phase 2 producer): {@code amount_minor} is the GRAND TOTAL, the {@code
 * subtotal_minor}/{@code discount_minor}/{@code service_charge_minor}/{@code tax_minor}/{@code
 * tax_rule_version}/{@code uses_illustrative_rules} legs are populated from the resolved {@link
 * PriceBreakdown}, and {@code tender_type} carries the checkout/capture tender's name. Unlike
 * {@code wash.messaging.SaleRecordedSchema} (which builds a record from a persisted {@code Wash}
 * entity), this schema is entity-agnostic: it takes the already-decided {@code saleId} (the
 * ticket's own id, per ADR 0023 decision 2 — {@code ticket.sale_id = ticket.id}), so it serves BOTH
 * the cash-checkout path and the later digital-capture path from the same helper.
 */
public final class TicketSaleRecordedSchema {

  /**
   * Classpath location of the {@code .avsc} — the same resource {@code wash.messaging.
   * SaleRecordedSchema} loads (shipped once by {@code libs/contracts}).
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
   * Builds a {@code SaleRecorded} {@link GenericRecord} with the full price breakdown.
   *
   * @param saleId the sale id (the ticket's own id — ADR 0023 decision 2)
   * @param companyId the owning tenant (stamped on the ticket from the tenant scope)
   * @param businessId the carwash outlet
   * @param grandTotal the amount the customer pays (must equal {@code breakdown.grandTotal()})
   * @param occurredAt when the sale was recognised (checkout for CASH, capture for digital)
   * @param tenderType the tender enum name ({@code "CASH"}, {@code "QRIS"}, {@code "CARD"})
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
    GenericRecord record = new GenericData.Record(SCHEMA);
    record.put("sale_id", saleId.toString());
    record.put("company_id", companyId);
    record.put("business_id", businessId.toString());
    record.put("amount_minor", grandTotal.amountMinor());
    record.put("currency", grandTotal.currency().getCurrencyCode());
    record.put("occurred_at", occurredAt.toEpochMilli());
    record.put("tender_type", tenderType);
    record.put("subtotal_minor", breakdown.subtotal().amountMinor());
    record.put("discount_minor", breakdown.discount().amountMinor());
    record.put("service_charge_minor", breakdown.serviceCharge().amountMinor());
    record.put("tax_minor", breakdown.tax().amountMinor());
    record.put("tax_rule_version", breakdown.taxRuleVersion());
    record.put("uses_illustrative_rules", breakdown.usesIllustrativeRules());
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
