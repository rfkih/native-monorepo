package id.co.nativeapp.restaurant.payment.messaging;

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
 * Loads the {@code SaleRefunded} Avro schema from the classpath ({@code avro/SaleRefunded.avsc},
 * sourced from {@code libs/contracts} — ADR 0003) and builds {@link GenericRecord}s from it.
 *
 * <p>A refund is a partial or full reversal of a captured payment after settlement. The record is
 * serialized to the outbox payload via {@code libs/events} {@link id.co.nativeapp.events.AvroSerde
 * AvroSerde}. Finance consumes {@code SaleRefunded} to post a proportional contra entry reversing
 * the original {@code SaleRecorded} ledger posting by the refunded amount (ADR 0006, slice 4).
 *
 * <p>Money is represented as {@code refund_amount_minor} / {@code total_refunded_minor} (long,
 * integer minor units) + {@code currency} (ISO-4217 string) — never a float (rule 8).
 */
public final class SaleRefundedSchema {

  /** Classpath location of the {@code .avsc} (also in {@code libs/contracts/avro/}). */
  public static final String RESOURCE = "avro/SaleRefunded.avsc";

  /** The event name as it appears in the outbox {@code event_type} column. */
  public static final String EVENT_TYPE = "SaleRefunded";

  /** The producing aggregate kind (outbox {@code aggregate_type}). */
  public static final String AGGREGATE_TYPE = "sale";

  private static final Schema SCHEMA = parse();

  private SaleRefundedSchema() {
    // static holder
  }

  /** The parsed writer schema for {@code SaleRefunded}. */
  public static Schema schema() {
    return SCHEMA;
  }

  /**
   * Builds a {@code SaleRefunded} {@link GenericRecord} for the outbox payload.
   *
   * @param refundId a unique id for this refund event (the reversal idempotency key)
   * @param saleId the sale being refunded
   * @param paymentId the payment aggregate being refunded
   * @param companyId the owning tenant
   * @param businessId the originating business unit
   * @param refundAmount the refunded amount for this event (integer minor units; never a float)
   * @param totalRefundedMinor the cumulative total refunded (including this refund) in minor units
   * @param occurredAt when the refund occurred
   * @param tenderType the original tender ({@code "CASH"}, {@code "QRIS"}, {@code "CARD"}, or
   *     {@code null})
   */
  @SuppressWarnings("checkstyle:ParameterNumber")
  public static GenericRecord toRecord(
      UUID refundId,
      UUID saleId,
      UUID paymentId,
      String companyId,
      UUID businessId,
      Money refundAmount,
      long totalRefundedMinor,
      Instant occurredAt,
      String tenderType) {
    GenericRecord record = new GenericData.Record(SCHEMA);
    record.put("refund_id", refundId.toString());
    record.put("sale_id", saleId.toString());
    record.put("payment_id", paymentId.toString());
    record.put("company_id", companyId);
    record.put("business_id", businessId.toString());
    record.put("refund_amount_minor", refundAmount.amountMinor());
    record.put("currency", refundAmount.currency().getCurrencyCode());
    record.put("total_refunded_minor", totalRefundedMinor);
    record.put("occurred_at", occurredAt.toEpochMilli());
    record.put("tender_type", tenderType);
    return record;
  }

  private static Schema parse() {
    try (InputStream in = SaleRefundedSchema.class.getClassLoader().getResourceAsStream(RESOURCE)) {
      if (in == null) {
        throw new IllegalStateException("Avro schema not found on classpath: " + RESOURCE);
      }
      return new Schema.Parser().parse(in);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to load Avro schema " + RESOURCE, e);
    }
  }
}
