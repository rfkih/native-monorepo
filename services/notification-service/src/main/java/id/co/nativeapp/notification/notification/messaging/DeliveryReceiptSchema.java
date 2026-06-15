package id.co.nativeapp.notification.notification.messaging;

import id.co.nativeapp.notification.notification.domain.DeliveryReceipt;
import id.co.nativeapp.notification.notification.domain.Notification;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;

/**
 * Loads notification-service's PRODUCER copy of the {@code DeliveryReceipt} Avro schema from the
 * classpath ({@code avro/DeliveryReceipt.avsc}) and builds {@link GenericRecord}s from it — no Avro
 * code-generation plugin. notification-service OWNS this event, so this is the source-of-truth
 * schema registered in {@code docs/EVENT-CATALOG.md}; this class parses it once and projects a
 * {@link Notification} + {@link DeliveryReceipt} onto it.
 *
 * <p>The record is serialized to the outbox payload via {@code libs/events} {@link
 * id.co.nativeapp.events.AvroSerde AvroSerde}. NO PII is carried (rule 6): only the ids, the
 * channel, the outcome status, and the transport provider_ref. {@code delivered_at} is epoch millis
 * (UTC) via the Avro {@code timestamp-millis} logical type; {@code group_id}/{@code provider_ref}
 * are nullable unions (the producer writes {@code null} when absent).
 */
public final class DeliveryReceiptSchema {

  /** Classpath location of the {@code .avsc} (also embedded in the event catalog). */
  public static final String RESOURCE = "avro/DeliveryReceipt.avsc";

  /** The event name as it appears in the outbox {@code event_type} column / topic. */
  public static final String EVENT_TYPE = "DeliveryReceipt";

  /** The producing aggregate kind (outbox {@code aggregate_type} / partition routing). */
  public static final String AGGREGATE_TYPE = "notification";

  private static final Schema SCHEMA = parse();

  private DeliveryReceiptSchema() {
    // static holder
  }

  /** The parsed reader/writer schema for {@code DeliveryReceipt}. */
  public static Schema schema() {
    return SCHEMA;
  }

  /**
   * Builds a {@code DeliveryReceipt} {@link GenericRecord} from a notification and its receipt.
   *
   * @param notification the delivered (or failed) notification
   * @param receipt the persisted delivery receipt (the outcome being announced)
   * @param companyId the owning tenant (stamped on the rows from the tenant scope)
   */
  public static GenericRecord toRecord(
      Notification notification, DeliveryReceipt receipt, String companyId) {
    GenericRecord record = new GenericData.Record(SCHEMA);
    record.put("notification_id", notification.getId().toString());
    record.put("company_id", companyId);
    record.put("channel", notification.getChannel().name());
    record.put("status", receipt.getStatus().name());
    record.put("provider_ref", receipt.getProviderRef());
    record.put("delivered_at", receipt.getDeliveredAt().toEpochMilli());
    return record;
  }

  private static Schema parse() {
    try (InputStream in =
        DeliveryReceiptSchema.class.getClassLoader().getResourceAsStream(RESOURCE)) {
      if (in == null) {
        throw new IllegalStateException("Avro schema not found on classpath: " + RESOURCE);
      }
      return new Schema.Parser().parse(in);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to load Avro schema " + RESOURCE, e);
    }
  }
}
