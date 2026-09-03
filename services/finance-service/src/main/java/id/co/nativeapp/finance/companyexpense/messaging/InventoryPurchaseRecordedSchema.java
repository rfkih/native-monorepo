package id.co.nativeapp.finance.companyexpense.messaging;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;

/**
 * Loads the {@code InventoryPurchaseRecorded} Avro schema from the classpath ({@code
 * avro/InventoryPurchaseRecorded.avsc} in {@code libs/contracts} — the single source of truth) and
 * builds the producer-side {@link GenericRecord} (ADR 0072).
 *
 * <p>Produced by finance from BOTH purchase inputs — {@code
 * companyexpense.service.CompanyExpenseWriter} ({@code source = EXPENSE}) and {@code
 * ap.service.BillWriter} ({@code source = BILL}) — in the same transaction as the money's journal
 * entry (rule 3). Consumed by restaurant-service, which applies each line as a priced goods receipt
 * keyed on {@code line_id}. No Avro code-generation, no Schema Registry serde — raw bytes via
 * {@code libs/events} {@code AvroSerde}, like every producer in this fleet. {@code
 * InventoryPurchaseRecordedContractTest} (both services) asserts back-compat (rule 7).
 */
public final class InventoryPurchaseRecordedSchema {

  /** Classpath location of the {@code .avsc} (the shared libs/contracts copy). */
  public static final String RESOURCE = "avro/InventoryPurchaseRecorded.avsc";

  /** The Kafka topic / outbox {@code event_type} (one topic per event type). */
  public static final String TOPIC = "InventoryPurchaseRecorded";

  /** The event name as it appears in the outbox {@code event_type} column. */
  public static final String EVENT_TYPE = "InventoryPurchaseRecorded";

  /**
   * The producing aggregate kind (outbox {@code aggregate_type}). The partition key (the outbox
   * {@code aggregate_id}) is the {@code purchase_id} — the company_expense or bill id.
   */
  public static final String AGGREGATE_TYPE = "inventory_purchase";

  /** {@code source} value for a company-expense INVENTORY submit. */
  public static final String SOURCE_EXPENSE = "EXPENSE";

  /** {@code source} value for a posted AP bill with ingredient-linked inventory lines. */
  public static final String SOURCE_BILL = "BILL";

  private static final Schema SCHEMA = parse();

  private static final Schema LINE_SCHEMA = SCHEMA.getField("lines").schema().getElementType();

  private InventoryPurchaseRecordedSchema() {
    // static holder
  }

  /** The parsed schema for {@code InventoryPurchaseRecorded}. */
  public static Schema schema() {
    return SCHEMA;
  }

  /**
   * Builds the event record. Every line's {@code value_minor} must already be net of recoverable
   * VAT and in {@code currency}'s minor units; {@code lineId} is the per-line replay anchor the
   * consumer stores as {@code goods_receipt.idempotency_key}.
   */
  public static GenericRecord toRecord(
      UUID purchaseId,
      String source,
      String companyId,
      String currency,
      Instant occurredAt,
      List<Line> lines) {
    GenericRecord record = new GenericData.Record(SCHEMA);
    record.put("purchase_id", purchaseId.toString());
    record.put("source", source);
    record.put("company_id", companyId);
    record.put("currency", currency);
    record.put("occurred_at", occurredAt.toEpochMilli());
    List<GenericRecord> lineRecords = new ArrayList<>(lines.size());
    for (Line line : lines) {
      GenericRecord lineRecord = new GenericData.Record(LINE_SCHEMA);
      lineRecord.put("line_id", line.lineId().toString());
      lineRecord.put("ingredient_id", line.ingredientId().toString());
      lineRecord.put("qty_base", line.qtyBase());
      lineRecord.put("value_minor", line.valueMinor());
      lineRecords.add(lineRecord);
    }
    record.put("lines", lineRecords);
    return record;
  }

  /** One wire line (see the {@code .avsc} field docs). */
  public record Line(UUID lineId, UUID ingredientId, long qtyBase, long valueMinor) {}

  private static Schema parse() {
    try (InputStream in =
        InventoryPurchaseRecordedSchema.class.getClassLoader().getResourceAsStream(RESOURCE)) {
      if (in == null) {
        throw new IllegalStateException("Avro schema not found on classpath: " + RESOURCE);
      }
      return new Schema.Parser().parse(in);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to load Avro schema " + RESOURCE, e);
    }
  }
}
