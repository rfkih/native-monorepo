package id.co.nativeapp.restaurant.sale.messaging;

import id.co.nativeapp.money.Money;
import id.co.nativeapp.restaurant.sale.domain.Sale;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;

/**
 * Loads the {@code SaleRecorded} Avro schema from the classpath ({@code avro/SaleRecorded.avsc})
 * and builds {@link GenericRecord}s from it — no Avro code-generation plugin, exactly as M1.4
 * specifies. The schema is the single source of truth in {@code libs/contracts} (ADR 0003),
 * registered in {@code docs/EVENT-CATALOG.md}; this class just parses it once and projects a {@link
 * Sale} onto it.
 *
 * <p>The record is serialized to the outbox payload via {@code libs/events} {@link
 * id.co.nativeapp.events.AvroSerde AvroSerde}. Field shape matches ARCHITECTURE.md §5: {@code
 * sale_id}, {@code company_id}, {@code business_id} (strings), {@code amount_minor} (long), {@code
 * currency} (string), {@code occurred_at} (long, logicalType {@code timestamp-millis}), and the ADR
 * 0006 / slice 2 addition {@code tender_type} (nullable string — null for legacy/no-tender sales;
 * carwash leaves it null and finance defaults to CASH_CLEARING).
 */
public final class SaleRecordedSchema {

  /** Classpath location of the {@code .avsc} (also embedded in the event catalog). */
  public static final String RESOURCE = "avro/SaleRecorded.avsc";

  /** The event name as it appears in the outbox {@code event_type} column. */
  public static final String EVENT_TYPE = "SaleRecorded";

  /** The producing aggregate kind (outbox {@code aggregate_type} / partition routing). */
  public static final String AGGREGATE_TYPE = "sale";

  private static final Schema SCHEMA = parse();

  private SaleRecordedSchema() {
    // static holder
  }

  /** The parsed reader/writer schema for {@code SaleRecorded}. */
  public static Schema schema() {
    return SCHEMA;
  }

  /**
   * Builds a {@code SaleRecorded} {@link GenericRecord} from a persisted sale, including the
   * optional {@code tender_type} field (ADR 0006, slice 2). The amount is taken from the sale's
   * {@link Money} (integer minor units + ISO-4217 code), never a float.
   *
   * @param sale the persisted sale aggregate
   * @param companyId the owning tenant (stamped on the sale from the tenant scope)
   * @param tenderType the tender enum name ({@code "CASH"}, {@code "QRIS"}, {@code "CARD"}), or
   *     {@code null} for legacy/no-payment sales (carwash always passes null)
   */
  public static GenericRecord toRecord(Sale sale, String companyId, String tenderType) {
    Money amount = sale.getAmount();
    GenericRecord record = new GenericData.Record(SCHEMA);
    record.put("sale_id", sale.getId().toString());
    record.put("company_id", companyId);
    record.put("business_id", sale.getBusinessId().toString());
    record.put("amount_minor", amount.amountMinor());
    record.put("currency", amount.currency().getCurrencyCode());
    record.put("occurred_at", sale.getOccurredAt().toEpochMilli());
    // tender_type is ["null","string"] with default null — set it explicitly (null or the name).
    record.put("tender_type", tenderType);
    return record;
  }

  /**
   * Backward-compatible overload for callers that have no tender context (e.g. legacy {@code POST
   * /sales}). Sets {@code tender_type} to {@code null} on the wire.
   *
   * @param sale the persisted sale aggregate
   * @param companyId the owning tenant
   */
  public static GenericRecord toRecord(Sale sale, String companyId) {
    return toRecord(sale, companyId, null);
  }

  private static Schema parse() {
    try (InputStream in = SaleRecordedSchema.class.getClassLoader().getResourceAsStream(RESOURCE)) {
      if (in == null) {
        throw new IllegalStateException("Avro schema not found on classpath: " + RESOURCE);
      }
      return new Schema.Parser().parse(in);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to load Avro schema " + RESOURCE, e);
    }
  }
}
