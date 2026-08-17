package id.co.nativeapp.restaurant.inventory.messaging;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.UUID;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;

/**
 * Loads the {@code StockReceived} Avro schema ({@code avro/StockReceived.avsc}, single-sourced from
 * libs/contracts — ADR 0003) — no Avro code-gen (the codebase convention). Emitted when a PRICED
 * goods receipt is recorded (a moving-average receive, ADR 0056) so finance-service can capitalize
 * the landed value to Inventory (ADR 0067 §1, "{@code StockReceived}"). {@code ingredient_id} is a
 * UUID reference, not PII. Money is integer minor units + ISO-4217, never a float (rule 8).
 *
 * <p>ADR 0067 Phase B: {@link #toRecord} builds the payload written by {@code
 * inventory.service.IngredientWriter}'s priced-receive branch, in the same transaction as the
 * {@code goods_receipt} idempotency anchor row.
 */
public final class StockReceivedSchema {

  /** Classpath location of the {@code .avsc} (also embedded in the event catalog). */
  public static final String RESOURCE = "avro/StockReceived.avsc";

  /** The outbox {@code event_type} column value / Kafka topic. */
  public static final String EVENT_TYPE = "StockReceived";

  /** The producing aggregate kind (outbox {@code aggregate_type} / partition routing). */
  public static final String AGGREGATE_TYPE = "goods_receipt";

  private static final Schema SCHEMA = parse();

  private StockReceivedSchema() {
    // static holder
  }

  /** The parsed reader/writer schema. */
  public static Schema schema() {
    return SCHEMA;
  }

  /**
   * Builds a {@code StockReceived} record. {@code valueMinor} is the EXACT total paid for this
   * receipt, in {@code currency}'s minor units (never a float, rule 8).
   *
   * @param receiptId the {@code goods_receipt} aggregate id (partition key + finance idempotency
   *     key)
   * @param companyId the owning tenant
   * @param businessId the originating outlet
   * @param ingredientId the received ingredient (traceability only)
   * @param qty units received, in the ingredient's own base unit
   * @param valueMinor the exact total paid, in {@code currency}'s minor units
   * @param currency ISO-4217 code of {@code valueMinor}
   * @param receivedAt when the goods were received — drives the accounting period
   */
  public static GenericRecord toRecord(
      UUID receiptId,
      String companyId,
      UUID businessId,
      UUID ingredientId,
      long qty,
      long valueMinor,
      String currency,
      Instant receivedAt) {
    GenericRecord record = new GenericData.Record(SCHEMA);
    record.put("receipt_id", receiptId.toString());
    record.put("company_id", companyId);
    record.put("business_id", businessId.toString());
    record.put("ingredient_id", ingredientId.toString());
    record.put("qty", qty);
    record.put("value_minor", valueMinor);
    record.put("currency", currency);
    record.put("received_at", receivedAt.toEpochMilli());
    return record;
  }

  private static Schema parse() {
    try (InputStream in =
        StockReceivedSchema.class.getClassLoader().getResourceAsStream(RESOURCE)) {
      if (in == null) {
        throw new IllegalStateException("Avro schema not found on classpath: " + RESOURCE);
      }
      return new Schema.Parser().parse(in);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to load Avro schema " + RESOURCE, e);
    }
  }
}
