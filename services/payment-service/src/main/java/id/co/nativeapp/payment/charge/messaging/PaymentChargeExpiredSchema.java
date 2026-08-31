package id.co.nativeapp.payment.charge.messaging;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.UUID;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;

/**
 * Loads the {@code PaymentChargeExpired} Avro schema from the classpath ({@code
 * avro/PaymentChargeExpired.avsc}) and builds {@link GenericRecord}s from it — no Avro
 * code-generation, matching {@link PaymentChargeSucceededSchema} and every other schema holder. The
 * schema is single-sourced in {@code libs/contracts} (ADR 0003) and registered in {@code
 * docs/EVENT-CATALOG.md}.
 *
 * <p>Emitted when a dynamic-QRIS gateway charge (ADR 0045) that had already ISSUED its QR reaches a
 * terminal state without settling (EXPIRED / CANCELED / FAILED) — in the SAME transaction as the
 * status transition (rule 3). The POS vertical named in {@code vertical} consumes it and RELEASES
 * the PENDING tender it was holding for the charge (no money moves). Money is integer minor units +
 * ISO-4217 (rule 8); no PII rides the event (rule 6).
 */
public final class PaymentChargeExpiredSchema {

  /** Classpath location of the {@code .avsc} (also embedded in the event catalog). */
  public static final String RESOURCE = "avro/PaymentChargeExpired.avsc";

  /** The event name as it appears in the outbox {@code event_type} column. */
  public static final String EVENT_TYPE = "PaymentChargeExpired";

  /** The producing aggregate kind (outbox {@code aggregate_type} / partition routing). */
  public static final String AGGREGATE_TYPE = "payment_charge";

  private static final Schema SCHEMA = parse();

  private PaymentChargeExpiredSchema() {
    // static holder
  }

  /** The parsed reader/writer schema for {@code PaymentChargeExpired}. */
  public static Schema schema() {
    return SCHEMA;
  }

  /**
   * Builds a {@code PaymentChargeExpired} record.
   *
   * @param chargeId the payment_charge id (partition key)
   * @param companyId the owning tenant
   * @param vertical {@code restaurant} | {@code carwash} | {@code barbershop} (lowercase)
   * @param paymentId the vertical's payment row id (the release anchor)
   * @param referenceId the vertical's release key when different (carwash/barbershop ticket id);
   *     {@code null} for restaurant
   * @param businessId the outlet the charge was rung at
   * @param amountMinor the charge amount, minor units (audit/observability only — no capture)
   * @param currency ISO-4217 code ({@code IDR})
   * @param reason why the charge terminated without settling ({@code EXPIRED}/{@code CANCELED}/{@code
   *     FAILED})
   * @param occurredAt when the terminal transition was recorded
   */
  public static GenericRecord toRecord(
      UUID chargeId,
      String companyId,
      String vertical,
      UUID paymentId,
      UUID referenceId,
      UUID businessId,
      long amountMinor,
      String currency,
      String reason,
      Instant occurredAt) {
    GenericRecord record = new GenericData.Record(SCHEMA);
    record.put("charge_id", chargeId.toString());
    record.put("company_id", companyId);
    record.put("vertical", vertical);
    record.put("payment_id", paymentId.toString());
    record.put("reference_id", referenceId == null ? null : referenceId.toString());
    record.put("business_id", businessId.toString());
    record.put("amount_minor", amountMinor);
    record.put("currency", currency);
    record.put("reason", reason);
    record.put("occurred_at", occurredAt.toEpochMilli());
    return record;
  }

  private static Schema parse() {
    try (InputStream in =
        PaymentChargeExpiredSchema.class.getClassLoader().getResourceAsStream(RESOURCE)) {
      if (in == null) {
        throw new IllegalStateException("Avro schema not found on classpath: " + RESOURCE);
      }
      return new Schema.Parser().parse(in);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to load Avro schema " + RESOURCE, e);
    }
  }
}
