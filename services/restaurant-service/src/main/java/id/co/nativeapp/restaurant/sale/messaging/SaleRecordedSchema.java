package id.co.nativeapp.restaurant.sale.messaging;

import id.co.nativeapp.money.Money;
import id.co.nativeapp.restaurant.pricing.domain.PriceBreakdown;
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
 * sale_id}, {@code company_id}, {@code business_id} (strings), {@code amount_minor} (long = grand
 * total), {@code currency} (string), {@code occurred_at} (long, logicalType {@code
 * timestamp-millis}), and the optional additions:
 *
 * <ul>
 *   <li>ADR 0006 / slice 2: {@code tender_type} (nullable string)
 *   <li>Phase 2 / pricing: {@code subtotal_minor}, {@code discount_minor}, {@code
 *       service_charge_minor}, {@code tax_minor}, {@code tax_rule_version} (all nullable — null for
 *       legacy/carwash producers), {@code uses_illustrative_rules} (nullable boolean)
 * </ul>
 *
 * <p>Backward compatibility: legacy producers (carwash) set all Phase 2 fields to {@code null};
 * finance falls back to {@code subtotal == amount_minor}, no discount/service-charge/tax legs.
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
   * Builds a {@code SaleRecorded} {@link GenericRecord} with the full Phase 2 price breakdown.
   * {@code amount_minor} is the GRAND TOTAL (the customer-pays amount). The breakdown fields are
   * set from the resolved {@link PriceBreakdown}; all are in the same currency as the sale.
   *
   * @param sale the persisted sale aggregate
   * @param companyId the owning tenant (stamped on the sale from the tenant scope)
   * @param tenderType the tender enum name ({@code "CASH"}, {@code "QRIS"}, {@code "CARD"}), or
   *     {@code null} for legacy/no-payment sales (carwash always passes null)
   * @param breakdown the Phase 2 price breakdown (subtotal, discount, service charge, tax); must
   *     not be null for Phase 2 producers; null for legacy/carwash callers
   */
  @SuppressWarnings("checkstyle:ParameterNumber")
  public static GenericRecord toRecord(
      Sale sale, String companyId, String tenderType, PriceBreakdown breakdown) {
    Money amount = sale.getAmount();
    GenericRecord record = new GenericData.Record(SCHEMA);
    record.put("sale_id", sale.getId().toString());
    record.put("company_id", companyId);
    record.put("business_id", sale.getBusinessId().toString());
    // amount_minor is the GRAND TOTAL.
    record.put("amount_minor", amount.amountMinor());
    record.put("currency", amount.currency().getCurrencyCode());
    record.put("occurred_at", sale.getOccurredAt().toEpochMilli());
    // tender_type is ["null","string"] with default null.
    record.put("tender_type", tenderType);
    // Phase 2 breakdown fields — null when breakdown is null (legacy path).
    if (breakdown != null) {
      record.put("subtotal_minor", breakdown.subtotal().amountMinor());
      record.put("discount_minor", breakdown.discount().amountMinor());
      record.put("service_charge_minor", breakdown.serviceCharge().amountMinor());
      record.put("tax_minor", breakdown.tax().amountMinor());
      record.put("tax_rule_version", breakdown.taxRuleVersion());
      record.put("uses_illustrative_rules", breakdown.usesIllustrativeRules());
    } else {
      record.put("subtotal_minor", null);
      record.put("discount_minor", null);
      record.put("service_charge_minor", null);
      record.put("tax_minor", null);
      record.put("tax_rule_version", null);
      record.put("uses_illustrative_rules", null);
    }
    return record;
  }

  /**
   * Backward-compatible overload for callers that have no tender context or breakdown (e.g. legacy
   * {@code POST /sales}). Sets {@code tender_type} and all Phase 2 fields to {@code null}.
   *
   * @param sale the persisted sale aggregate
   * @param companyId the owning tenant
   */
  public static GenericRecord toRecord(Sale sale, String companyId) {
    return toRecord(sale, companyId, null, null);
  }

  /**
   * Backward-compatible overload for callers that have a tender type but no breakdown (Phase 1
   * callers — {@code SaleWriter.create}/{@code SaleWriter.recordInCurrentTx} before Phase 2).
   *
   * @param sale the persisted sale aggregate
   * @param companyId the owning tenant
   * @param tenderType the tender type string, or null
   */
  public static GenericRecord toRecord(Sale sale, String companyId, String tenderType) {
    return toRecord(sale, companyId, tenderType, null);
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
