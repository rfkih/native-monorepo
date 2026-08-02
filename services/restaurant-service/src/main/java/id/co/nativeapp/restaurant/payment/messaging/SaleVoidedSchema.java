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
 * Loads the {@code SaleVoided} Avro schema from the classpath ({@code avro/SaleVoided.avsc},
 * sourced from {@code libs/contracts} — ADR 0003) and builds {@link GenericRecord}s from it.
 *
 * <p>A void is a full reversal of a captured payment before settlement. The record is serialized to
 * the outbox payload via {@code libs/events} {@link id.co.nativeapp.events.AvroSerde AvroSerde}.
 * Finance consumes {@code SaleVoided} to reverse the original {@code SaleRecorded} ledger posting
 * with a contra entry (ADR 0006, slice 4).
 *
 * <p>Money is represented as {@code amount_minor} (long, integer minor units) + {@code currency}
 * (ISO-4217 string) — never a float (rule 8).
 */
public final class SaleVoidedSchema {

  /** Classpath location of the {@code .avsc} (also in {@code libs/contracts/avro/}). */
  public static final String RESOURCE = "avro/SaleVoided.avsc";

  /** The event name as it appears in the outbox {@code event_type} column. */
  public static final String EVENT_TYPE = "SaleVoided";

  /** The producing aggregate kind (outbox {@code aggregate_type}). */
  public static final String AGGREGATE_TYPE = "sale";

  private static final Schema SCHEMA = parse();

  private SaleVoidedSchema() {
    // static holder
  }

  /** The parsed writer schema for {@code SaleVoided}. */
  public static Schema schema() {
    return SCHEMA;
  }

  /**
   * Builds a {@code SaleVoided} {@link GenericRecord} for the outbox payload.
   *
   * @param voidId a unique id for this void event (the reversal idempotency key)
   * @param saleId the voided sale aggregate id
   * @param paymentId the voided payment aggregate id
   * @param companyId the owning tenant
   * @param businessId the originating business unit
   * @param amount the voided amount (integer minor units + ISO-4217; never a float)
   * @param occurredAt when the void occurred
   * @param tenderType the original tender ({@code "CASH"}, {@code "QRIS"}, {@code "CARD"}, {@code
   *     "ONLINE"}, or {@code null} for legacy)
   * @param channel the original sale's sales-channel code (ADR 0036 Phase B2) when {@code
   *     tenderType} was {@code "ONLINE"}, so finance can claw back the right per-channel platform
   *     receivable; {@code null} for every other tender
   */
  @SuppressWarnings("checkstyle:ParameterNumber")
  public static GenericRecord toRecord(
      UUID voidId,
      UUID saleId,
      UUID paymentId,
      String companyId,
      UUID businessId,
      Money amount,
      Instant occurredAt,
      String tenderType,
      String channel) {
    GenericRecord record = new GenericData.Record(SCHEMA);
    record.put("void_id", voidId.toString());
    record.put("sale_id", saleId.toString());
    record.put("payment_id", paymentId.toString());
    record.put("company_id", companyId);
    record.put("business_id", businessId.toString());
    record.put("amount_minor", amount.amountMinor());
    record.put("currency", amount.currency().getCurrencyCode());
    record.put("occurred_at", occurredAt.toEpochMilli());
    record.put("tender_type", tenderType);
    // ADR 0036 Phase B2: the original sale's channel, threaded from the voided payment.
    record.put("channel", channel);
    return record;
  }

  private static Schema parse() {
    try (InputStream in = SaleVoidedSchema.class.getClassLoader().getResourceAsStream(RESOURCE)) {
      if (in == null) {
        throw new IllegalStateException("Avro schema not found on classpath: " + RESOURCE);
      }
      return new Schema.Parser().parse(in);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to load Avro schema " + RESOURCE, e);
    }
  }
}
